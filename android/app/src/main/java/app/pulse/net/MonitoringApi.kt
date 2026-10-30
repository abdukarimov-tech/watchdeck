package app.pulse.net

import app.pulse.crypto.ServersStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Incident(val id: Long, val agentId: String, val kind: String, val containerId: String,
    val containerName: String, val message: String, val openedAt: Long, val lastAt: Long,
    val resolvedAt: Long?, val occurrences: Int)
data class MetricPoint(val at: Long, val cpu: Double, val memory: Double, val disk: Double,
    val rx: Double, val tx: Double)
data class AlertPolicy(val enabled: Boolean = true, val cpu: Double = 90.0, val memory: Double = 85.0,
    val disk: Double = 90.0, val sustain: Int = 300, val restarts: Int = 3, val maintenanceUntil: Long = 0)

class MonitoringApi(private val server: ServersStore.ServerConfig) {
    private val client = PinnedHttp.client(server.relayCertPem)
    private val base = server.relayUrl.trimEnd('/').removeSuffix("/ws/client")
        .replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    private suspend fun request(path: String, agent: String = "", hours: Int? = null, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val url = (base + path).toHttpUrl().newBuilder().addQueryParameter("agent_id", agent)
            .apply { hours?.let { addQueryParameter("hours", it.toString()) } }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${server.token}")
        if (body != null) builder.put(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException(if (it.code == 404) "HTTP 404 — update pulse-relay" else "HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
    }
    suspend fun agentIds(): List<String> {
        val a = JSONArray(request("/api/agent-ids"))
        return (0 until a.length()).map { a.getString(it) }
    }
    suspend fun incidents(agent: String = ""): List<Incident> {
        if (server.isDemo) return listOf(Incident(1,"demo-vps","CONTAINER_DIED","ffeeddccbbaa","batch-worker","exit code 137",now()-120,now()-120,null,1))
        val a = JSONArray(request("/api/incidents", agent))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Incident(o.getLong("id"),o.getString("agent_id"),o.getString("kind"),o.getString("container_id"),o.getString("container_name"),o.getString("message"),o.getLong("opened_at"),o.getLong("last_at"),if (o.isNull("resolved_at")) null else o.getLong("resolved_at"),o.getInt("occurrences")) } }
    }
    suspend fun history(agent: String, hours: Int): List<MetricPoint> {
        if (server.isDemo) return (0..100).map { i -> MetricPoint(now()-hours*3600L+i*hours*36L,20+10*kotlin.math.sin(i/5.0),31+3*kotlin.math.sin(i/10.0),53.0,240000+100000*kotlin.math.sin(i/4.0),90000+30000*kotlin.math.cos(i/6.0)) }
        val a = JSONArray(request("/api/metrics",agent,hours))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> MetricPoint(o.getLong("at_unix"),o.getDouble("cpu_percent"),o.getDouble("memory_percent"),o.getDouble("disk_percent"),o.getDouble("rx_bytes_per_sec"),o.getDouble("tx_bytes_per_sec")) } }
    }
    suspend fun policy(agent: String): AlertPolicy {
        if (server.isDemo) return demoPolicies[server.id+agent] ?: AlertPolicy()
        val o = JSONObject(request("/api/alert-policy",agent))
        return AlertPolicy(o.getBoolean("enabled"),o.getDouble("cpu_percent"),o.getDouble("memory_percent"),o.getDouble("disk_percent"),o.getInt("sustain_seconds"),o.getInt("restart_count"),o.getLong("maintenance_until"))
    }
    suspend fun savePolicy(agent: String, p: AlertPolicy) {
        if (server.isDemo) { demoPolicies[server.id+agent]=p; return }
        request("/api/alert-policy",agent,body=JSONObject().put("enabled",p.enabled).put("cpu_percent",p.cpu).put("memory_percent",p.memory).put("disk_percent",p.disk).put("sustain_seconds",p.sustain).put("restart_count",p.restarts).put("maintenance_until",p.maintenanceUntil))
    }
    companion object { private fun now()=System.currentTimeMillis()/1000
        private val demoPolicies=java.util.concurrent.ConcurrentHashMap<String,AlertPolicy>() }
}
