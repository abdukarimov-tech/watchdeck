package app.pulse.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Список добавленных серверов в SharedPreferences.
 *
 * ServerConfig хранит всё, что нужно приложению: адрес релея и клиентский
 * токен (для живого канала), админ-токен релея (для выпуска агентских токенов
 * новым серверам) и SSH-доступ (для терминала и переустановки агента).
 * Демо-режим — тоже «сервер» с id [DEMO_ID].
 */
class ServersStore(context: Context) {

    data class ServerConfig(
        val id: String,
        val label: String,
        val relayUrl: String,
        val token: String,
        val adminToken: String = "",
        val relayCertPem: String = "",     // самоподписанный сертификат релея (pinning)
        val sshHostKeyFp: String = "",     // отпечаток SSH-ключа сервера (pinning)
        val sshPort: Int = 22,
        val sshUser: String = "root",
        val sshAuthMode: String = "",      // "password" | "key"
        val sshSecret: String = "",        // пароль или приватный ключ PEM
        val sshKeyPass: String = "",
    ) {
        val isDemo: Boolean get() = id == DEMO_ID
    }

    private val prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(context)

    fun load(): List<ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServerConfig(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    relayUrl = o.optString("url"),
                    token = cipher.decrypt(o.optString("token")),
                    adminToken = cipher.decrypt(o.optString("admin")),
                    relayCertPem = o.optString("cert"),
                    sshHostKeyFp = o.optString("ssh_fp"),
                    sshPort = o.optInt("ssh_port", 22),
                    sshUser = o.optString("ssh_user", "root"),
                    sshAuthMode = o.optString("ssh_auth"),
                    sshSecret = cipher.decrypt(o.optString("ssh_secret")),
                    sshKeyPass = cipher.decrypt(o.optString("ssh_key_pass")),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Добавляет сервер, возвращает его конфиг. */
    fun add(config: ServerConfig): ServerConfig {
        val withId = if (config.id.isBlank()) config.copy(id = UUID.randomUUID().toString()) else config
        save(load() + withId)
        return withId
    }

    fun addDemo(label: String): ServerConfig {
        load().firstOrNull { it.id == DEMO_ID }?.let { return it }
        val demo = ServerConfig(DEMO_ID, label, "", "")
        save(load() + demo)
        return demo
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
        if (activeId() == id) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    /** Полная очистка (сброс данных приложения). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun save(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("url", c.relayUrl)
                    .put("token", cipher.encrypt(c.token))
                    .put("admin", cipher.encrypt(c.adminToken))
                    .put("cert", c.relayCertPem)
                    .put("ssh_fp", c.sshHostKeyFp)
                    .put("ssh_port", c.sshPort)
                    .put("ssh_user", c.sshUser)
                    .put("ssh_auth", c.sshAuthMode)
                    .put("ssh_secret", cipher.encrypt(c.sshSecret))
                    .put("ssh_key_pass", cipher.encrypt(c.sshKeyPass))
            )
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    companion object {
        const val DEMO_ID = "demo"
        private const val STORE_NAME = "pulse_servers"
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE = "active_id"

        /** Хост из URL — метка сервера по умолчанию. */
        fun labelForUrl(url: String): String = runCatching {
            java.net.URI(url).host ?: url
        }.getOrDefault(url)
    }
}
