import Citadel
import Crypto
import Foundation
import NIOCore
import Security

/// Установка сервера по SSH (bootstrap), порт Kotlin-инсталлятора из Android-клиента.
///
/// Один вызов `install()` делает всё:
///  1. если релея на сервере нет — скачивает и ставит его (systemd), генерирует
///     админ-токен на устройстве, выпускает самоподписанный TLS-сертификат
///     (если есть openssl) и настраивает wss;
///  2. выпускает клиентский и агентский токены через локальный API релея
///     (curl 127.0.0.1 прямо на сервере);
///  3. ставит агента и регистрирует его на релее.
public struct SSHInstallParams: Sendable {
    public var host: String
    public var port: Int
    public var username: String
    public var auth: SSHAuth

    public init(host: String, port: Int, username: String, auth: SSHAuth) {
        self.host = host
        self.port = port
        self.username = username
        self.auth = auth
    }
}

public struct SSHInstallResult: Sendable {
    public var relayUrl: String      // wss://IP:8443 или ws://IP:8080 (без openssl)
    public var adminToken: String
    public var clientToken: String
    public var agentLabel: String
    public var relayCertPem: String  // пусто, если TLS не удалось выпустить
    public var hostKeyFp: String     // отпечаток host key (для терминала/апдейта)
}

public final class SSHInstaller: @unchecked Sendable {
    public static let releaseBase = "https://github.com/watchdeck-app/watchdeck/releases/latest/download"

    public var onLog: (@Sendable (String) -> Void)?
    /// Интерактивное подтверждение host key при первом контакте с сервером.
    public var onHostKeyPrompt: (@Sendable (String) async -> Bool)?

    private let params: SSHInstallParams

    public init(params: SSHInstallParams) {
        self.params = params
    }

    // MARK: - Connect

    private func connect(pinnedFingerprint: String) async throws -> (SSHClient, SSHHostKeyDelegate) {
        let method = try params.auth.authenticationMethod(username: params.username)
        let delegate = SSHHostKeyDelegate(pinnedFingerprint: pinnedFingerprint, prompt: onHostKeyPrompt)
        var settings = SSHClientSettings(
            host: params.host,
            port: params.port,
            authenticationMethod: { method },
            hostKeyValidator: .custom(delegate)
        )
        settings.connectTimeout = TimeAmount.seconds(15)
        let client = try await withTimeout(seconds: 30) {
            try await SSHClient.connect(to: settings)
        }
        return (client, delegate)
    }

    // MARK: - Install

    public func install() async throws -> SSHInstallResult {
        onLog?("connecting to \(params.host):\(params.port)")
        let (client, delegate) = try await connect(pinnedFingerprint: "")
        defer { Task { try? await client.close() } }
        onLog?("authorized as \(params.username)")

        let runner = SSHCommandRunner(client: client)
        let password = sudoPassword()
        let isRoot = try await runner.exec("id -u").trimmingCharacters(in: .whitespacesAndNewlines) == "0"
        guard isRoot || password != nil else {
            throw SSHError.commandFailed("SSH user must be root, or use password auth (for sudo)")
        }

        func sh(_ cmd: String) async throws -> String {
            try await runner.sudo(cmd, isRoot: isRoot, sudoPassword: password)
        }

        // 1. архитектура, утилиты, публичный адрес
        let archName = try await sh("uname -m").trimmingCharacters(in: .whitespacesAndNewlines)
        let arch: String
        switch archName {
        case "x86_64": arch = "x86_64"
        case "aarch64", "arm64": arch = "aarch64"
        default: throw SSHError.commandFailed("unsupported architecture")
        }
        try await sh("command -v curl >/dev/null || command -v wget >/dev/null")
        onLog?("arch: \(arch)")

        let publicIpRaw = try await sh(
            "curl -s --max-time 5 https://ifconfig.me || hostname -I | awk '{print $1}'")
        let publicIp = publicIpRaw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "\n").last
            .map(String.init)?
            .trimmingCharacters(in: .whitespaces) ?? ""
        guard !publicIp.isEmpty, publicIp != "127.0.0.1" else {
            throw SSHError.commandFailed("failed to detect public IP of the server")
        }
        onLog?("public address: \(publicIp)")

        // 2. релей: ставим, если ещё нет
        let health = try await sh("curl -s --max-time 3 http://127.0.0.1:8080/health || true")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var certPem = ""
        var relayUrl: String
        let adminToken: String
        if health.contains("ok") {
            onLog?("relay already running — reusing")
            adminToken = try await sh(
                "grep -oP '(?<=PULSE_ADMIN_TOKEN=).*' /etc/pulse-relay/pulse-relay.env || true")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if adminToken.isEmpty {
                throw SSHError.commandFailed("relay is running but admin token not found")
            }
            relayUrl = "ws://\(publicIp):8080"
            certPem = try await sh("cat /etc/pulse-relay/tls.crt 2>/dev/null || true")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !certPem.isEmpty { relayUrl = "wss://\(publicIp):8443" }
        } else {
            adminToken = Self.newHexToken()
            onLog?("installing relay…")
            let relayDownload = "\(Self.releaseBase)/pulse-relay-\(arch)"
            try await sh(
                "curl -fsSL '\(relayDownload)' -o /usr/local/bin/pulse-relay || wget -qO /usr/local/bin/pulse-relay '\(relayDownload)'")
            try await sh("chmod 0755 /usr/local/bin/pulse-relay")
            try await sh("id pulse-relay >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-relay")
            try await sh("mkdir -p /etc/pulse-relay /var/lib/pulse-relay")

            let hasOpenssl = try await sh("command -v openssl >/dev/null && echo yes || echo no")
                .trimmingCharacters(in: .whitespacesAndNewlines) == "yes"
            var tlsEnv = ""
            if hasOpenssl {
                onLog?("issuing self-signed TLS certificate…")
                let tls = try await sh(
                    "openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes " +
                    "-keyout /etc/pulse-relay/tls.key -out /etc/pulse-relay/tls.crt -days 3650 " +
                    "-subj '/CN=pulse' -addext 'subjectAltName=IP:\(publicIp)' >/dev/null 2>&1 && echo ok || echo fail")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if tls == "ok" {
                    certPem = try await sh("cat /etc/pulse-relay/tls.crt")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    tlsEnv = "PULSE_TLS_CERT=/etc/pulse-relay/tls.crt\nPULSE_TLS_KEY=/etc/pulse-relay/tls.key\n"
                    onLog?("TLS enabled (self-signed, pinned in app)")
                }
            }

            try await Self.writeFile(sh: sh, path: "/etc/pulse-relay/pulse-relay.env",
                "PULSE_BIND=0.0.0.0:8080\nPULSE_ADMIN_TOKEN=\(adminToken)\nPULSE_DB=/var/lib/pulse-relay/relay.db\n\(tlsEnv)")
            try await sh("chmod 600 /etc/pulse-relay/pulse-relay.env")
            try await Self.writeFile(sh: sh, path: "/etc/systemd/system/pulse-relay.service", Self.relayUnit)
            try await sh("systemctl daemon-reload")
            try await sh("systemctl enable --now pulse-relay")
            let relayActive = try await sh("systemctl is-active pulse-relay")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard relayActive == "active" else {
                throw SSHError.commandFailed("relay service is not active: \(relayActive)")
            }
            onLog?("relay installed on this server")
            relayUrl = tlsEnv.isEmpty ? "ws://\(publicIp):8080" : "wss://\(publicIp):8443"
        }

        // 3. токены через локальный API релея
        let relayPort = relayUrl.hasPrefix("wss://") ? "8443" : "8080"
        func issueToken(_ label: String, _ role: String) async throws -> String {
            let raw = try await sh(
                "curl -sk -XPOST http://127.0.0.1:\(relayPort)/api/tokens " +
                "-H 'X-Admin-Token: \(adminToken)' -H 'Content-Type: application/json' " +
                "-d '{\"label\":\"\(label)\",\"role\":\"\(role)\"}'")
            guard let start = raw.range(of: "\"token\":\"") else {
                throw SSHError.commandFailed("failed to issue \(role) token: \(raw.prefix(120))")
            }
            let rest = raw[start.upperBound...]
            guard let end = rest.firstIndex(of: "\"") else {
                throw SSHError.commandFailed("failed to issue \(role) token: \(raw.prefix(120))")
            }
            return String(rest[..<end])
        }
        onLog?("issuing tokens…")
        let clientToken = try await issueToken("phone", "client")
        let agentToken = try await issueToken(params.host, "agent")

        // 4. агент
        onLog?("installing agent…")
        let binaryUrl = "\(Self.releaseBase)/pulse-agent-\(arch)"
        try await sh(
            "curl -fsSL '\(binaryUrl)' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '\(binaryUrl)'")
        try await sh("chmod 0755 /usr/local/bin/pulse-agent")
        try await sh("id pulse-agent >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-agent")
        try await sh("(getent group docker >/dev/null 2>&1 && usermod -aG docker pulse-agent) || true")
        try await sh("mkdir -p /etc/pulse-agent")
        try await Self.writeFile(sh: sh, path: "/etc/pulse-agent/config.toml",
                                 "relay_url = \"\(relayUrl)\"\ntoken = \"\(agentToken)\"\n")
        try await sh("chmod 600 /etc/pulse-agent/config.toml")
        try await sh("chown -R pulse-agent:pulse-agent /etc/pulse-agent")
        try await Self.writeFile(sh: sh, path: "/etc/systemd/system/pulse-agent.service", Self.agentUnit)
        try await sh("systemctl daemon-reload")
        try await sh("systemctl enable --now pulse-agent")
        let active = try await sh("systemctl is-active pulse-agent")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard active == "active" else {
            throw SSHError.commandFailed("agent service is not active: \(active)")
        }
        onLog?("agent service is active")

        try await sh("(ufw allow \(relayPort)/tcp >/dev/null 2>&1) || true")
        onLog?("note: open TCP \(relayPort) in the cloud firewall if unreachable")

        return SSHInstallResult(
            relayUrl: relayUrl,
            adminToken: adminToken,
            clientToken: clientToken,
            agentLabel: params.host,
            relayCertPem: certPem,
            hostKeyFp: delegate.acceptedFingerprint ?? ""
        )
    }

    // MARK: - Update agent

    /// Обновление бинарника агента (download + restart). Соединение по сохраненному отпечатку.
    public func updateAgent(expectedFp: String) async throws {
        onLog?("connecting to \(params.host):\(params.port)")
        let (client, _) = try await connect(pinnedFingerprint: expectedFp)
        defer { Task { try? await client.close() } }

        let runner = SSHCommandRunner(client: client)
        let password = sudoPassword()
        let isRoot = try await runner.exec("id -u").trimmingCharacters(in: .whitespacesAndNewlines) == "0"
        guard isRoot || password != nil else {
            throw SSHError.commandFailed("root or password auth required")
        }
        func sh(_ cmd: String) async throws -> String {
            try await runner.sudo(cmd, isRoot: isRoot, sudoPassword: password)
        }

        let archName = try await sh("uname -m").trimmingCharacters(in: .whitespacesAndNewlines)
        let arch: String
        switch archName {
        case "x86_64": arch = "x86_64"
        case "aarch64", "arm64": arch = "aarch64"
        default: throw SSHError.commandFailed("unsupported architecture")
        }
        onLog?("downloading agent (\(arch))…")
        let binaryUrl = "\(Self.releaseBase)/pulse-agent-\(arch)"
        try await sh(
            "curl -fsSL '\(binaryUrl)' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '\(binaryUrl)'")
        try await sh("chmod 0755 /usr/local/bin/pulse-agent")
        try await sh("systemctl restart pulse-agent")
        let active = try await sh("systemctl is-active pulse-agent")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard active == "active" else {
            throw SSHError.commandFailed("agent service is not active: \(active)")
        }
        onLog?("agent updated and active")
    }

    // MARK: - Helpers

    private func sudoPassword() -> String? {
        if case .password(let password) = params.auth { return password }
        return nil
    }

    static func newHexToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func writeFile(sh: (String) async throws -> String, path: String, _ content: String) async throws {
        try await sh("tee \(path) >/dev/null <<'PULSE_EOF'\n\(content)PULSE_EOF")
    }

    static let relayUnit = """
    [Unit]
    Description=Pulse relay (agent/client router + FCM push)
    After=network-online.target
    Wants=network-online.target

    [Service]
    Type=simple
    EnvironmentFile=/etc/pulse-relay/pulse-relay.env
    ExecStart=/usr/local/bin/pulse-relay
    Restart=always
    RestartSec=5
    NoNewPrivileges=true
    ProtectSystem=strict
    ProtectHome=read-only
    PrivateTmp=true
    StateDirectory=pulse-relay

    [Install]
    WantedBy=multi-user.target
    """

    static let agentUnit = """
    [Unit]
    Description=Pulse monitoring agent (VPS/Docker metrics)
    After=network-online.target docker.service
    Wants=network-online.target

    [Service]
    Type=simple
    User=pulse-agent
    Group=docker
    ExecStart=/usr/local/bin/pulse-agent /etc/pulse-agent/config.toml
    Restart=always
    RestartSec=5
    NoNewPrivileges=true
    ProtectSystem=strict
    ProtectHome=read-only
    PrivateTmp=true

    [Install]
    WantedBy=multi-user.target
    """
}

/// Выполнение команд через SSH с проверкой кодов возврата и sudo-оберткой.
final class SSHCommandRunner: Sendable {
    private let client: SSHClient

    init(client: SSHClient) {
        self.client = client
    }

    /// Выполняет команду, возвращает stdout; ненулевой код — ошибка.
    func exec(_ command: String) async throws -> String {
        do {
            let buffer = try await client.executeCommand(command)
            return String(decoding: buffer.readableBytesView, as: UTF8.self)
        } catch let failure as SSHClient.CommandFailed {
            throw SSHError.commandFailed("command failed (\(failure.exitCode)): \(command)")
        }
    }

    /// Обертка с sudo (как в Android-инсталляторе): от root — напрямую,
    /// иначе `sudo -S` с паролем пользователя на stdin (только password auth).
    func sudo(_ command: String, isRoot: Bool, sudoPassword: String?) async throws -> String {
        if isRoot {
            return try await exec(command)
        }
        let inner = SSHShell.quote(command)
        if let sudoPassword {
            return try await exec("echo \(SSHShell.quote(sudoPassword)) | sudo -S -p '' sh -c \(inner)")
        }
        return try await exec("sudo -p '' sh -c \(inner)")
    }
}
