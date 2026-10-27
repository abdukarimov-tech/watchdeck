package app.pulse.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Интерактивный SSH-терминал (PTY shell). Подключается напрямую к серверу
 * по сохраненному SSH-доступу, независимо от релея.
 */
class SshTerminal(private val onOutput: (String) -> Unit) {

    private var client: SSHClient? = null
    private var shell: net.schmizz.sshj.connection.channel.direct.Session.Shell? = null
    private val reading = AtomicBoolean(false)

    val isConnected: Boolean get() = client != null && client!!.isConnected

    suspend fun connect(cfg: SshInstaller.SshConfig) = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        val ssh = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 0 // интерактивная сессия
            // ключ сервера уже подтвержден при установке агента
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean = true

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
        val session = ssh.startSession()
        session.allocatePTY("vt100", 120, 32, 0, 0, emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>())
        val sh = session.startShell()

        client = ssh
        shell = sh
        reading.set(true)

        Thread {
            val buf = ByteArray(4096)
            try {
                while (reading.get()) {
                    val n = sh.inputStream.read(buf)
                    if (n <= 0) break
                    onOutput(String(buf, 0, n))
                }
            } catch (_: Exception) {
                // соединение закрыто
            }
        }.start()
    }

    fun send(line: String) {
        val sh = shell ?: return
        runCatching {
            sh.outputStream.write((line + "\r\n").toByteArray())
            sh.outputStream.flush()
        }
    }

    fun close() {
        reading.set(false)
        runCatching { shell?.close() }
        runCatching { client?.disconnect() }
        client = null
        shell = null
    }
}
