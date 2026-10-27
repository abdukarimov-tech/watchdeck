package app.pulse.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Список добавленных серверов (релей + client-токен) в SharedPreferences.
 *
 * Демо-режим — тоже «сервер» с id [DEMO_ID]: он появляется и переключается
 * в общем списке, и его можно удалить. Токены лежат зашифрованно только
 * у WsPulseRepository-конфигов; для MVP список хранится в приватном
 * хранилище приложения (allowBackup=false).
 */
class ServersStore(context: Context) {

    data class ServerConfig(
        val id: String,
        val label: String,
        val relayUrl: String,
        val token: String,
    ) {
        val isDemo: Boolean get() = id == DEMO_ID
    }

    private val prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

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
                    token = o.optString("token"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Добавляет сервер, возвращает его конфиг. */
    fun add(relayUrl: String, token: String, label: String): ServerConfig {
        val config = ServerConfig(
            id = UUID.randomUUID().toString(),
            label = label,
            relayUrl = relayUrl,
            token = token,
        )
        save(load() + config)
        return config
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
                    .put("token", c.token),
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
