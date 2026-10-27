package app.pulse.net

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Установка сервера по SSH (bootstrap).
 *
 * Один вызов [installServer] делает всё:
 *  1. если релея еще нет на этом сервере — скачивает и ставит его (systemd),
 *     генерирует админ-токен на устройстве, выпускает самоподписанный
 *     TLS-сертификат (если есть openssl) и настраивает wss;
 *  2. выпускает клиентский и агентский токены через локальный API релея
 *     (curl 127.0.0.1:8080 прямо на сервере);
 *  3. ставит агента и регистрирует его на релеи.
 *
 * Возвращает всё, что приложению нужно для подключения и хранения.
 * Требуется root или sudo (с паролем пользователя при парольной аутентификации).
 */
class SshInstaller(private val onLog: (String) -> Unit) {

    data class SshConfig(
        val host: String,
        val port: Int,
        val username: String,
        val auth: Auth,
    ) {
        sealed interface Auth {
            data class Password(val password: String) : Auth
            data class Key(val privateKey: String, val passphrase: String?) : Auth
        }
    }

    data class InstallResult(
        val relayUrl: String,        // wss://IP:8443 или ws://IP:8080 (без openssl)
        val adminToken: String,
        val clientToken: String,
        val agentLabel: String,
        val relayCertPem: String,    // пусто, если TLS не удалось выпустить
        val hostKeyFp: String,       // отпечаток SSH-ключа сервера (для терминала)
    )

    suspend fun installServer(
        cfg: SshConfig,
        verifyHostKey: suspend (fingerprint: String) -> Boolean,
    ): InstallResult = withContext(Dispatchers.IO) {
        var acceptedFingerprint = ""
        val ssh = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 30_000
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                    val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                        digest,
                        android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    )
                    onLog("host key ($hostname): $fingerprint")
                    // блокируем transport-поток до решения пользователя — как в OpenSSH
                    val accepted = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(120_000) { verifyHostKey(fingerprint) }
                    }
                    if (accepted) acceptedFingerprint = fingerprint
                    return accepted
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
        }
        try {
            onLog("connecting to ${cfg.host}:${cfg.port}")
            ssh.connect(cfg.host, cfg.port)
            when (val a = cfg.auth) {
                is SshConfig.Auth.Password -> ssh.authPassword(cfg.username, a.password)
                is SshConfig.Auth.Key -> {
                    val key = ssh.loadKeys(a.privateKey, a.passphrase)
                    ssh.authPublickey(cfg.username, key)
                }
            }
            onLog("authorized as ${cfg.username}")

            val sudoPassword = (cfg.auth as? SshConfig.Auth.Password)?.password

            // session-канал выполняет ровно одну команду — на каждую команду
            // открываем новый канал и закрываем его (иначе после MaxSessions
            // команд сервер начнет отказывать в новых каналах)
            fun exec(cmd: String, stdin: String? = null): String =
                ssh.startSession().use { session ->
                    val command = session.exec(cmd)
                    stdin?.let { command.outputStream.write(it.toByteArray()); command.outputStream.flush() }
                    val out = command.inputStream.bufferedReader().readText()
                    val err = command.errorStream.bufferedReader().readText()
                    command.join()
                    val code = command.exitStatus ?: -1
                    if (code != 0) throw IOException("command failed ($code): $cmd\n$err")
                    out
                }

            val isRoot = exec("id -u").trim() == "0"
            if (!isRoot && sudoPassword == null) {
                throw IOException("SSH user must be root, or use password auth (for sudo)")
            }
            fun sh(cmd: String): String =
                if (isRoot) exec(cmd)
                else exec("sudo -S -p '' sh -c '${cmd.replace("'", "'\\''")}'", (sudoPassword ?: "") + "\n")

            // ---------- 1. архитектура, утилиты, публичный адрес ----------
            val arch = when (sh("uname -m").trim()) {
                "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw IOException("unsupported architecture")
            }
            sh("command -v curl >/dev/null || command -v wget >/dev/null")
            onLog("arch: $arch")

            val publicIp = sh(
                "curl -s --max-time 5 https://ifconfig.me || hostname -I | awk '{print \$1}'",
            ).trim().lines().last().trim()
            if (publicIp.isEmpty() || publicIp == "127.0.0.1") {
                throw IOException("failed to detect public IP of the server")
            }
            onLog("public address: $publicIp")

            // ---------- 2. релей: ставим, если еще нет ----------
            // -k: самоподписанный сертификат; TLS-релей слушает 8443, plain — 8080
            val health = sh(
                "curl -sk --max-time 3 https://127.0.0.1:8443/health || " +
                    "curl -s --max-time 3 http://127.0.0.1:8080/health || true",
            ).trim()
            val adminToken: String
            var certPem = ""
            var relayUrl: String
            if (health.contains("ok")) {
                onLog("relay already running — reusing")
                adminToken = sh(
                    "grep -oP '(?<=PULSE_ADMIN_TOKEN=).*' /etc/pulse-relay/pulse-relay.env || true",
                ).trim().ifEmpty { throw IOException("relay is running but admin token not found") }
                relayUrl = "ws://$publicIp:8080"
                certPem = sh("cat /etc/pulse-relay/tls.crt 2>/dev/null || true").trim()
                if (certPem.isNotEmpty()) relayUrl = "wss://$publicIp:8443"
            } else {
                adminToken = newHexToken()
                onLog("installing relay…")
                val relayDownload = "$RELEASE_BASE/pulse-relay-$arch"
                sh("curl -fsSL '$relayDownload' -o /usr/local/bin/pulse-relay || wget -qO /usr/local/bin/pulse-relay '$relayDownload'")
                sh("chmod 0755 /usr/local/bin/pulse-relay")
                sh("id pulse-relay >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-relay")
                sh("mkdir -p /etc/pulse-relay /var/lib/pulse-relay")

                // самоподписанный TLS-сертификат, если на сервере есть openssl
                val hasOpenssl = sh("command -v openssl >/dev/null && echo yes || echo no").trim() == "yes"
                var tlsEnv = ""
                if (hasOpenssl) {
                    onLog("issuing self-signed TLS certificate…")
                    val tls = sh(
                        "openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes " +
                            "-keyout /etc/pulse-relay/tls.key -out /etc/pulse-relay/tls.crt -days 3650 " +
                            "-subj '/CN=pulse' -addext 'subjectAltName=IP:$publicIp' >/dev/null 2>&1 && echo ok || echo fail",
                    ).trim()
                    if (tls == "ok") {
                        certPem = sh("cat /etc/pulse-relay/tls.crt").trim()
                        tlsEnv = "PULSE_TLS_CERT=/etc/pulse-relay/tls.crt\nPULSE_TLS_KEY=/etc/pulse-relay/tls.key\n"
                        onLog("TLS enabled (self-signed, pinned in app)")
                    }
                }

                // при TLS релей слушает 8443 (relayUrl ниже), без TLS — 8080
                val bindPort = if (tlsEnv.isEmpty()) "8080" else "8443"
                writeFile(::sh,
                    "/etc/pulse-relay/pulse-relay.env",
                    "PULSE_BIND=0.0.0.0:$bindPort\nPULSE_ADMIN_TOKEN=$adminToken\nPULSE_DB=/var/lib/pulse-relay/relay.db\n$tlsEnv",
                )
                sh("chmod 600 /etc/pulse-relay/pulse-relay.env")
                writeFile(::sh, "/etc/systemd/system/pulse-relay.service", RELAY_UNIT)
                sh("systemctl daemon-reload")
                sh("systemctl enable --now pulse-relay")
                // Type=simple: дать сервису секунду — иначе is-active гонка
                sh("sleep 1")
                // is-active возвращает != 0 для failed/inactive — не роняем sh()
                val relayActive = sh("systemctl is-active pulse-relay || true").trim()
                if (relayActive != "active") {
                    // причина падения — в журнал в терминал установки
                    onLog("relay service state: $relayActive")
                    onLog(sh("journalctl -u pulse-relay -n 40 --no-pager 2>&1 | tail -40 || true"))
                    throw IOException("relay service is not active: $relayActive (see journal above)")
                }
                onLog("relay installed on this server")
                relayUrl = if (tlsEnv.isEmpty()) "ws://$publicIp:8080" else "wss://$publicIp:8443"
            }

            // ---------- 3. токены через локальный API релея ----------
            fun issueToken(label: String, role: String): String {
                val body = """{"label":"$label","role":"$role"}"""
                val tls = relayUrl.startsWith("wss://")
                val port = if (tls) "8443" else "8080"
                // -k: самоподписанный сертификат; на TLS-порту нужен https
                val scheme = if (tls) "https" else "http"
                val raw = sh(
                    "curl -sk -XPOST $scheme://127.0.0.1:$port/api/tokens " +
                        "-H 'X-Admin-Token: $adminToken' -H 'Content-Type: application/json' " +
                        "-d '$body'",
                )
                val token = Regex("\"token\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                    ?: throw IOException("failed to issue $role token: $raw")
                return token
            }
            onLog("issuing tokens…")
            val clientToken = issueToken("phone", "client")
            val agentToken = issueToken(cfg.host, "agent")

            // ---------- 4. агент ----------
            onLog("installing agent…")
            val binaryUrl = "$RELEASE_BASE/pulse-agent-$arch"
            sh("curl -fsSL '$binaryUrl' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '$binaryUrl'")
            sh("chmod 0755 /usr/local/bin/pulse-agent")
            sh("id pulse-agent >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-agent")
            // docker-группа нужна агенту для статистики контейнеров; без Docker
            // на сервере группу из юнита надо убрать, иначе systemd не стартует
            val hasDocker = sh("getent group docker >/dev/null 2>&1 && echo yes || echo no").trim() == "yes"
            if (hasDocker) sh("usermod -aG docker pulse-agent")
            sh("mkdir -p /etc/pulse-agent")
            var agentConfig = "relay_url = \"$relayUrl\"\ntoken = \"$agentToken\"\n"
            if (certPem.isNotEmpty()) {
                // агент доверяет самоподписанному сертификату релея через CA-файл
                writeFile(::sh, "/etc/pulse-agent/relay-ca.crt", "$certPem\n")
                agentConfig += "ca_cert = \"/etc/pulse-agent/relay-ca.crt\"\n"
            }
            writeFile(::sh, "/etc/pulse-agent/config.toml", agentConfig)
            sh("chmod 600 /etc/pulse-agent/config.toml")
            sh("chown -R pulse-agent:pulse-agent /etc/pulse-agent")
            writeFile(::sh, "/etc/systemd/system/pulse-agent.service",
                if (hasDocker) AGENT_UNIT else AGENT_UNIT.replace("Group=docker\n", ""))
            sh("systemctl daemon-reload")
            sh("systemctl enable pulse-agent")
            // restart, а не enable --now: юнит может висеть в failed/activating
            // с прошлой попытки — restart гарантирует старт с новым конфигом
            sh("systemctl restart pulse-agent")
            sh("sleep 1")
            val active = sh("systemctl is-active pulse-agent || true").trim()
            if (active != "active") {
                onLog("agent service state: $active")
                onLog(sh("journalctl -u pulse-agent -n 40 --no-pager 2>&1 | tail -40 || true"))
                throw IOException("agent service is not active: $active (see journal above)")
            }
            onLog("agent service is active")

            // порт релея должен быть открыт наружу (облачный firewall — вне SSH)
            val relayPort = if (relayUrl.startsWith("wss://")) "8443" else "8080"
            sh("(ufw allow $relayPort/tcp >/dev/null 2>&1) || true")
            onLog("note: open TCP $relayPort in the cloud firewall if unreachable")

            InstallResult(
                relayUrl = relayUrl,
                adminToken = adminToken,
                clientToken = clientToken,
                agentLabel = cfg.host,
                relayCertPem = certPem,
                hostKeyFp = acceptedFingerprint,
            )
        } finally {
            ssh.disconnect()
        }
    }

    /**
     * Обновление бинарника агента (download + restart). Соединение проверяется
     * по сохраненному отпечатку.
     */
    suspend fun updateAgent(
        cfg: SshConfig,
        expectedFp: String,
        onLog: (String) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val ssh = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 30_000
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                    if (expectedFp.isBlank()) return true
                    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                    val fp = "SHA256:" + android.util.Base64.encodeToString(
                        digest,
                        android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    )
                    if (fp != expectedFp) {
                        onLog("host key MISMATCH: $fp (expected $expectedFp) — aborting")
                        return false
                    }
                    return true
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
        }
        try {
            ssh.connect(cfg.host, cfg.port)
            when (val a = cfg.auth) {
                is SshConfig.Auth.Password -> ssh.authPassword(cfg.username, a.password)
                is SshConfig.Auth.Key -> {
                    val key = ssh.loadKeys(a.privateKey, a.passphrase)
                    ssh.authPublickey(cfg.username, key)
                }
            }
            val sudoPassword = (cfg.auth as? SshConfig.Auth.Password)?.password

            // как в installServer: новый session-канал на каждую команду
            fun exec(cmd: String, stdin: String? = null): String =
                ssh.startSession().use { session ->
                    val command = session.exec(cmd)
                    stdin?.let { command.outputStream.write(it.toByteArray()); command.outputStream.flush() }
                    val out = command.inputStream.bufferedReader().readText()
                    command.join()
                    out
                }
            val isRoot = exec("id -u").trim() == "0"
            if (!isRoot && sudoPassword == null) throw IOException("root or password auth required")
            fun sh(cmd: String): String =
                if (isRoot) exec(cmd)
                else exec("sudo -S -p '' sh -c '${cmd.replace("'", "'\\''")}'", (sudoPassword ?: "") + "\n")

            val arch = when (sh("uname -m").trim()) {
                "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw IOException("unsupported architecture")
            }
            onLog("downloading agent ($arch)…")
            val binaryUrl = "$RELEASE_BASE/pulse-agent-$arch"
            sh("curl -fsSL '$binaryUrl' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '$binaryUrl'")
            sh("chmod 0755 /usr/local/bin/pulse-agent")
            sh("systemctl restart pulse-agent")
            val active = sh("systemctl is-active pulse-agent").trim()
            if (active != "active") throw IOException("agent service is not active: $active")
            onLog("agent updated and active")
        } finally {
            ssh.disconnect()
        }
    }

    private fun writeFile(sh: (String) -> String, path: String, content: String) {
        sh("tee $path >/dev/null <<'PULSE_EOF'\n$content${"PULSE_EOF"}")
    }

    companion object {
        // GitHub Release, куда release.yml (по тегу v*) выкладывает бинари
        const val RELEASE_BASE = "https://github.com/abdukarimov-tech/watchdeck/releases/latest/download"

        fun newHexToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private val RELAY_UNIT = """[Unit]
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

        private val AGENT_UNIT = """[Unit]
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
}
