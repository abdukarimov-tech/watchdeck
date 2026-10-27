package app.pulse.net

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Минимальный клиент админ-API релея: выпуск токенов. */
class RelayApi(private val baseUrl: String) {

    private val http = OkHttpClient()
    private val restBase = baseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

    /** POST /api/tokens — возвращает созданный токен. */
    fun createToken(adminToken: String, label: String, role: String): String {
        val body = """{"label":"$label","role":"$role"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$restBase/api/tokens")
            .header("X-Admin-Token", adminToken)
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("relay: HTTP ${resp.code} — check relay URL and admin token ($text)")
            }
            val token = org.json.JSONObject(text).optString("token")
            if (token.isEmpty()) throw IOException("relay: empty token in response")
            return token
        }
    }
}
