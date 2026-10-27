package app.pulse.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException

/**
 * Установка агента на сервер по SSH (bootstrap).
 *
 * Алгоритм: подключиться (пароль или ключ), выпустить агентский токен делает
 * вызывающий ([RelayApi]); здесь — скачать бинарник с GitHub Release,
 * создать системного пользователя, конфиг и systemd-юнит, запустить сервис.
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

    suspend fun install(
        cfg: SshConfig,
        relayUrl: String,
        agentToken: String,
        // приглашение подтвердить отпечаток ключа сервера (true — доверять)
        verifyHostKey: suspend (fingerprint: String) -> Boolean,
    ): Unit =
        withContext(Dispatchers.IO) {
            val ssh = SSHClient().apply {
                connectTimeout = 15_000
                timeout = 30_000
                addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(key.encoded)
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

                val sudoPassword = (cfg.auth as? SshConfig.Auth.Password)?.password
                val session = ssh.startSession()

                fun exec(cmd: String, stdin: String? = null): String {
                    val command = session.exec(cmd)
                    stdin?.let { command.outputStream.write(it.toByteArray()); command.outputStream.flush() }
                    val out = command.inputStream.bufferedReader().readText()
                    val err = command.errorStream.bufferedReader().readText()
                    command.join()
                    val code = command.exitStatus ?: -1
                    if (code != 0) throw IOException("command failed ($code): $cmd\n$err${if (err.isEmpty()) out else ""}")
                    return out
                }

                // локальный helper: через sudo, если пользователь не root
                val isRoot = exec("id -u").trim() == "0"
                if (!isRoot && sudoPassword == null && cfg.auth is SshConfig.Auth.Key) {
                    throw IOException("SSH user must be root, or use password auth (for sudo)")
                }
                fun sh(cmd: String): String =
                    if (isRoot) exec(cmd)
                    else exec("sudo -S -p '' sh -c '${cmd.replace("'", "'\\''")}'", (sudoPassword ?: "") + "\n")

                // 1. архитектура и утилиты
                val arch = when (sh("uname -m").trim()) {
                    "x86_64" -> "x86_64"
                    "aarch64", "arm64" -> "aarch64"
                    else -> throw IOException("unsupported architecture")
                }
                sh("command -v curl >/dev/null || command -v wget >/dev/null")
                onLog("arch: $arch")

                // 2. пользователь без прав root
                sh("id pulse-agent >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin pulse-agent")
                sh("(getent group docker >/dev/null 2>&1 && usermod -aG docker pulse-agent) || true")
                onLog("service user ready")

                // 3. бинарник из GitHub Release
                val binaryUrl = "$RELEASE_BASE/pulse-agent-$arch"
                onLog("downloading agent ($arch)…")
                sh("curl -fsSL '$binaryUrl' -o /usr/local/bin/pulse-agent || wget -qO /usr/local/bin/pulse-agent '$binaryUrl'")
                sh("chmod 0755 /usr/local/bin/pulse-agent")

                // 4. конфиг
                sh("mkdir -p /etc/pulse-agent")
                writeFile(
                    sh = ::sh,
                    path = "/etc/pulse-agent/config.toml",
                    content = "relay_url = \"$relayUrl\"\ntoken = \"$agentToken\"\n",
                )
                sh("chmod 600 /etc/pulse-agent/config.toml")
                sh("chown -R pulse-agent:pulse-agent /etc/pulse-agent")
                onLog("config written")

                // 5. systemd
                writeFile(sh = ::sh, path = "/etc/systemd/system/pulse-agent.service", content = UNIT)
                sh("systemctl daemon-reload")
                sh("systemctl enable --now pulse-agent")
                val active = sh("systemctl is-active pulse-agent").trim()
                if (active != "active") throw IOException("service is not active: $active")
                onLog("agent service is active")
            } finally {
                ssh.disconnect()
            }
        }

    private fun writeFile(sh: (String) -> String, path: String, content: String) {
        // tee вместо > : перенаправление делали бы внутри sh -c, но так надежнее с кавычками
        sh("tee $path >/dev/null <<'PULSE_EOF'\n$content${"PULSE_EOF"}")
    }

    companion object {
        // замените на адрес вашего GitHub Release (release.yml собирает бинари по тегу)
        const val RELEASE_BASE = "https://github.com/watchdeck-app/watchdeck/releases/latest/download"

        private val UNIT = """[Unit]
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
