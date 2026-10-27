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
 *     генерирует админ-токен на устройстве;
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
        val relayUrl: String,    // ws://PUBLIC_IP:8080
        val adminToken: String,
        val clientToken: String,
        val agentLabel: String,
    )

    suspend fun installServer(
        cfg: SshConfig,
        verifyHostKey: suspend (fingerprint: String) -> Boolean,
    ): InstallResult = withContext(Dispatchers.IO) {
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
                    return kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(120_000) { verifyHostKey(fingerprint) }
                    }
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

            val session = ssh.startSession()
            val sudoPassword = (cfg.auth as? SshConfig.Auth.Password)?.password

            fun exec(cmd: String, stdin: String? = null): String {
                val command = session.exec(cmd)
                stdin?.let { command.outputStream.write(it.toByteArray()); command.outputStream.flush() }
                val out = command.inputStream.bufferedReader().readText()
                val err = command.errorStream.bufferedReader().readText()
                command.join()
                val code = command.exitStatus ?: -1
                if (code != 0) throw IOException("command failed ($code): $cmd\n$err")
                return out
            }

            val isRoot = exec("id -u").trim() == "0"
            if (!isRoot && sudoPassword == null) {
                throw IOException("SSH user must be root, or use password auth (for sudo)")
            }
            fun sh(cmd: String): String =
                if (isRoot) exec(cmd)
                else exec("sudo -S -p '' sh -c '${cmd.replace("'", "'\\''")}'", (sudoPassword ?: "") + "\n")

            // ---------- 1. архитектура и утилиты ----------
            val arch = when (sh("uname -m").trim()) {
                "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw IOException("unsupported architecture")
            }
            sh("command -v curl >/dev/null || command -v wget >/dev/null")
            onLog("arch: $arch")

            // ---------- 2. релей: ставим, если еще нет ----------
            val health = sh("curl -s --max-time 3 http://127.0.0.1:8080/health || true").trim()
            val adminToken: String
            if (health.contains("ok")) {
                onLog("relay already running — reusing")
                adminToken = sh(
                    "grep -oP '(?<=PULSE_ADMIN_TOKEN=).*' /etc/pulse-relay/pulse-relay.env || true",
                ).trim().ifEmpty { throw IOException("relay is running but admin token not found") }
            } else {
                adminToken = newHexToken()
                onLog("installing relay…")
                val relayDownload = "$RELEASE_BASE/pulse-relay-$arch"
                sh("curl -fsSL '$relayDownload' -o /usr/local/bin/pulse-relay || wget -qO /usr/local/bin/pulse-relay '$relayDownload'")
                sh("chmod 0755 /usr/local/bin/pulse-relay")
                sh("id pulse-relay >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-relay")
                sh("mkdir -p /etc/pulse-relay /var/lib/pulse-relay")
                writeFile({ c -> sh(c) },
                    "/etc/pulse-relay/pulse-relay.env",
                    "PULSE_BIND=0.0.0.0:8080\nPULSE_ADMIN_TOKEN=$adminToken\nPULSE_DB=/var/lib/pulse-relay/relay.db\n",
                )
                sh("chmod 600 /etc/pulse-relay/pulse-relay.env")
                writeFile({ c -> sh(c) }, "/etc/systemd/system/pulse-relay.service", RELAY_UNIT)
                sh("systemctl daemon-reload")
                sh("systemctl enable --now pulse-relay")
                val relayActive = sh("systemctl is-active pulse-relay").trim()
                if (relayActive != "active") throw IOException("relay service is not active: $relayActive")
                onLog("relay installed on this server")
            }

            // ---------- 3. токены через локальный API релея ----------
            fun issueToken(label: String, role: String): String {
                val body = """{"label":"$label","role":"$role"}"""
                val raw = sh(
                    "curl -s -XPOST http://127.0.0.1:8080/api/tokens " +
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

            // ---------- 4. публичный адрес релея ----------
            val publicIp = sh(
                "curl -s --max-time 5 https://ifconfig.me || hostname -I | awk '{print \$1}'",
            ).trim().lines().last().trim()
            if (publicIp.isEmpty() || publicIp == "127.0.0.1") {
                throw IOException("failed to detect public IP of the relay")
            }
            val relayUrl = "ws://$publicIp:8080"
            onLog("relay: $relayUrl")

            // ---------- 5. агент ----------
            onLog("installing agent…")
            val binaryUrl = "$RELEASE_BASE/pulse-agent-$arch"
            sh("curl -fsSL '$binaryUrl' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '$binaryUrl'")
            sh("chmod 0755 /usr/local/bin/pulse-agent")
            sh("id pulse-agent >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-agent")
            sh("(getent group docker >/dev/null 2>&1 && usermod -aG docker pulse-agent) || true")
            sh("mkdir -p /etc/pulse-agent")
            writeFile({ c -> sh(c) },
                "/etc/pulse-agent/config.toml",
                "relay_url = \"$relayUrl\"\ntoken = \"$agentToken\"\n",
            )
            sh("chmod 600 /etc/pulse-agent/config.toml")
            sh("chown -R pulse-agent:pulse-agent /etc/pulse-agent")
            writeFile({ c -> sh(c) }, "/etc/systemd/system/pulse-agent.service", AGENT_UNIT)
            sh("systemctl daemon-reload")
            sh("systemctl enable --now pulse-agent")
            val active = sh("systemctl is-active pulse-agent").trim()
            if (active != "active") throw IOException("agent service is not active: $active")
            onLog("agent service is active")

            // порт 8080 должен быть открыт наружу (облачный firewall — вне SSH)
            sh("(ufw allow 8080/tcp >/dev/null 2>&1) || true")
            onLog("note: open TCP 8080 in the cloud firewall if the port is unreachable")

            InstallResult(
                relayUrl = relayUrl,
                adminToken = adminToken,
                clientToken = clientToken,
                agentLabel = cfg.host,
            )
        } finally {
            ssh.disconnect()
        }
    }

    private fun writeFile(sh: (String) -> String, path: String, content: String) {
        sh("tee $path >/dev/null <<'PULSE_EOF'\n$content${"PULSE_EOF"}")
    }

    companion object {
        // замените на адрес вашего GitHub Release (release.yml собирает бинари по тегу)
        const val RELEASE_BASE = "https://github.com/watchdeck-app/watchdeck/releases/latest/download"

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
