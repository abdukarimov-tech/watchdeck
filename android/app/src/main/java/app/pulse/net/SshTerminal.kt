package app.pulse.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * SSH-терминал: one-shot команды поверх одного SSH-соединения (session.exec).
 * Подключается напрямую к серверу по сохраненному SSH-доступу, независимо
 * от релея. Каждая команда — свой session-канал, как в [SshInstaller].
 */
class SshTerminal(
    private val expectedFp: String = "",
    private val onLog: (String) -> Unit = {},
    private val onOutput: (String) -> Unit,
) {

    private var client: SSHClient? = null
    private var prompt = ""
    private val execInFlight = AtomicBoolean(false)

    val isConnected: Boolean get() = client != null && client!!.isConnected

    suspend fun connect(cfg: SshInstaller.SshConfig) = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        val ssh = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 0
            // пиннинг: сверяем отпечаток, сохраненный при установке агента
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                    if (expectedFp.isBlank()) return true
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
                    val fp = "SHA256:" + android.util.Base64.encodeToString(
                        digest,
                        android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    )
                    if (fp != expectedFp) {
                        onLog("host key MISMATCH: $fp")
                        return false
                    }
                    return true
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
        }
        ssh.connect(cfg.host, cfg.port)
        when (val a = cfg.auth) {
            is SshInstaller.SshConfig.Auth.Password -> ssh.authPassword(cfg.username, a.password)
            is SshInstaller.SshConfig.Auth.Key -> {
                val key = ssh.loadKeys(a.privateKey, a.passphrase)
                ssh.authPublickey(cfg.username, key)
            }
        }
        client = ssh
        prompt = "${cfg.username}@${cfg.host}"
        onOutput("connected: $prompt\n")
    }

    fun send(line: String) {
        val ssh = client ?: return
        if (!execInFlight.compareAndSet(false, true)) {
            onLog("предыдущая команда ещё выполняется…")
            return
        }
        Thread {
            try {
                onOutput("\n$prompt:~# $line\n")
                ssh.startSession().use { session ->
                    val cmd = session.exec(line)
                    val out = cmd.inputStream.bufferedReader().readText()
                    val err = cmd.errorStream.bufferedReader().readText()
                    cmd.join()
                    onOutput(out)
                    if (err.isNotBlank()) onOutput(err.trimEnd() + "\n")
                    onOutput("exit=${cmd.exitStatus ?: -1}\n")
                }
            } catch (e: Exception) {
                onLog("error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                execInFlight.set(false)
            }
        }.start()
    }

    fun close() {
        runCatching { client?.disconnect() }
        client = null
    }
}
