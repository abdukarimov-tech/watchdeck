import Foundation
import PulseCore

/// Конфиг сервера. Секреты (токен, админ-токен, SSH-секреты) лежат в Keychain,
/// в UserDefaults (JSON) — только нечувствительные поля.
struct ServerConfig: Codable, Identifiable, Equatable {
    var id: String
    var label: String
    /// База URL релея: ws://IP:8080 / wss://IP:8443 / wss://relay.example.com
    var url: String
    /// PEM самоподписанного TLS-сертификата релея (пиннинг); пусто — системное доверие.
    var certPem: String
    /// Отпечаток SSH host key сервера (SHA256:...) — для терминала и апдейта агента.
    var sshFp: String
    var sshPort: Int
    var sshUser: String
    /// "password" | "key"
    var sshAuthKind: String
    var isDemo: Bool
    var sshHost: String? = nil

    var endpoint: PulseEndpoint {
        PulseEndpoint(baseURL: url, token: token ?? "", certPem: certPem)
    }

    // MARK: - Keychain-backed secrets

    var token: String? {
        get { KeychainStore.get(account: "server.\(id).token") }
        nonmutating set { setSecret(newValue, account: "server.\(id).token") }
    }
    var adminToken: String? {
        get { KeychainStore.get(account: "server.\(id).admin") }
        nonmutating set { setSecret(newValue, account: "server.\(id).admin") }
    }
    /// Пароль SSH или содержимое PEM-ключа.
    var sshSecret: String? {
        get { KeychainStore.get(account: "server.\(id).sshSecret") }
        nonmutating set { setSecret(newValue, account: "server.\(id).sshSecret") }
    }
    var sshKeyPass: String? {
        get { KeychainStore.get(account: "server.\(id).sshKeyPass") }
        nonmutating set { setSecret(newValue, account: "server.\(id).sshKeyPass") }
    }

    var sshAuth: SSHAuth? {
        guard let secret = sshSecret else { return nil }
        return sshAuthKind == "key"
            ? .key(pem: secret, passphrase: sshKeyPass)
            : .password(secret)
    }

    private func setSecret(_ value: String?, account: String) {
        if let value, !value.isEmpty {
            KeychainStore.set(value, account: account)
        } else {
            KeychainStore.delete(account: account)
        }
    }

    static func demo() -> ServerConfig {
        ServerConfig(id: "demo", label: NSLocalizedString("demo_label", comment: ""),
                     url: "", certPem: "", sshFp: "", sshPort: 22, sshUser: "root",
                     sshAuthKind: "password", isDemo: true)
    }
}

/// Хранилище списка серверов (аналог ServersStore из Android).
enum ServersStore {
    private static let defaults = UserDefaults.standard
    private static let serversKey = "servers"
    private static let activeKey = "active_id"
    private static let appLockKey = "app_lock"

    static func load() -> [ServerConfig] {
        guard let data = defaults.data(forKey: serversKey) else { return [] }
        return (try? JSONDecoder().decode([ServerConfig].self, from: data)) ?? []
    }

    static func save(_ servers: [ServerConfig]) {
        if let data = try? JSONEncoder().encode(servers) {
            defaults.set(data, forKey: serversKey)
        }
    }

    static var activeId: String? {
        get { defaults.string(forKey: activeKey) }
        set { defaults.set(newValue, forKey: activeKey) }
    }

    static var appLockEnabled: Bool {
        get { defaults.bool(forKey: appLockKey) }
        set { defaults.set(newValue, forKey: appLockKey) }
    }

    /// Полная очистка: серверы + секреты из Keychain + настройки.
    static func wipeAll() {
        for server in load() {
            for account in ["token", "admin", "sshSecret", "sshKeyPass"] {
                KeychainStore.delete(account: "server.\(server.id).\(account)")
            }
        }
        defaults.removeObject(forKey: serversKey)
        defaults.removeObject(forKey: activeKey)
    }
}
