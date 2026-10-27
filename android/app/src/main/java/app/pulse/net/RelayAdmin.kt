package app.pulse.net

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON = "application/json".toMediaType()

private fun String.toJsonBody() = toRequestBody(JSON)

/** OkHttpClient с пиннингом самоподписанного сертификата релея. */
object PinnedHttp {

    fun client(certPem: String, callTimeoutSec: Long = 30): OkHttpClient {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (certPem.isBlank()) return base.build()
        val cert = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(certPem.byteInputStream()) as X509Certificate
        }.getOrNull() ?: return base.build()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.none { it == cert }) {
                    throw java.security.cert.CertificateException("unpinned relay certificate")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(cert)
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return base
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // личность релея определяется пиннингом сертификата
            .build()
    }
}

/** Админ-API релея с телефона: список и отзыв токенов. */
class RelayAdmin(private val baseUrl: String, private val adminToken: String, certPem: String = "") {

    private val http = PinnedHttp.client(certPem)
    private val restBase = baseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

    data class TokenRow(val id: Long, val label: String, val role: String, val revoked: Boolean)

    suspend fun listTokens(): List<TokenRow> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("$restBase/api/tokens")
            .header("X-Admin-Token", adminToken)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}: $text")
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TokenRow(o.getLong("id"), o.getString("label"), o.getString("role"), o.optBoolean("revoked"))
            }
        }
    }

    suspend fun revoke(id: Long) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = """{"id":$id}""".toJsonBody()
        val request = okhttp3.Request.Builder()
            .url("$restBase/api/tokens/revoke")
            .header("X-Admin-Token", adminToken)
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
        }
    }

    /** Регистрация FCM-токена устройства (требуется client-токен). */
    suspend fun registerDevice(clientToken: String, fcmToken: String, agentId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = okhttp3.Request.Builder()
                .url("$restBase/api/devices")
                .header("Authorization", "Bearer $clientToken")
                .post("""{"fcm_token":"$fcmToken","agent_id":"$agentId"}""".toJsonBody())
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    throw java.io.IOException("HTTP ${resp.code}")
                }
            }
        }
}