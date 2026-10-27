package app.pulse.data

import app.pulse.net.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

const val DEMO_AGENT_ID = "demo-vps"

/**
 * Источник данных сервера. Релизная реализация — [app.pulse.net.WsPulseRepository]
 * (живой WS к релею); [MockPulseRepository] используется для демо-режима
 * (магазинные снепшоты, проверка UI без сервера).
 */
interface PulseRepository {
    val connectionState: StateFlow<ConnState>
    /** agent_id -> hostname. */
    val agents: StateFlow<Map<String, String>>
    /** agent_id -> версия агента (демо — пусто). */
    val versions: Flow<Map<String, String>>
    val metricsByAgent: StateFlow<Map<String, MetricsUi>>
    val containersByAgent: StateFlow<Map<String, List<ContainerUi>>>
    val logLines: StateFlow<List<LogLineUi>>
    val events: StateFlow<List<AlertUi>>

    suspend fun action(agentId: String, containerId: String, kind: String): Result<Unit>
    fun requestLogs(agentId: String, containerId: String)
    fun close()
}

class MockPulseRepository : PulseRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val connectionState = MutableStateFlow(ConnState.CONNECTED)
    override val agents = MutableStateFlow(mapOf(DEMO_AGENT_ID to "demo-server"))
    override val versions: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
    override val events = MutableStateFlow(
        listOf(
            AlertUi("CONTAINER_DIED", "batch-worker", "exit code 137", System.currentTimeMillis() / 1000 - 120),
        ),
    )

    private val cpu = MutableStateFlow(0.12)
    private val memUsed = MutableStateFlow(2 * GiB + 512 * MiB)
    private val metricsFlow = MutableStateFlow(
        mapOf(
            DEMO_AGENT_ID to MetricsUi(
                cpuPercent = 12.0,
                cpuCores = 4,
                memTotalBytes = 8 * GiB,
                memUsedBytes = 2 * GiB + 512 * MiB,
                disks = listOf(
                    DiskUi("/", 80 * GiB, 43 * GiB),
                    DiskUi("/var/lib/docker", 40 * GiB, 31 * GiB),
                ),
                rxBytesPerSec = 240_000,
                txBytesPerSec = 90_000,
                uptimeSeconds = 12 * 86_400 + 5 * 3_600,
            ),
        ),
    )

    private val containersFlow = MutableStateFlow(
        mapOf(
            DEMO_AGENT_ID to listOf(
                ContainerUi("a1b2c3d4e5f6", "web-nginx", "nginx:1.25", "RUNNING", "Up 3 days (healthy)"),
                ContainerUi("ffeeddccbbaa", "batch-worker", "worker:latest", "EXITED", "Exited (137) 2 minutes ago"),
                ContainerUi("998877665544", "postgres", "postgres:16", "RUNNING", "Up 12 days"),
                ContainerUi("112233445566", "redis", "redis:7", "RUNNING", "Up 12 days"),
            ),
        ),
    )

    override val metricsByAgent: MutableStateFlow<Map<String, MetricsUi>> = metricsFlow
    override val containersByAgent: MutableStateFlow<Map<String, List<ContainerUi>>> = containersFlow

    override val logLines = MutableStateFlow<List<LogLineUi>>(emptyList())

    init {
        scope.launch {
            var lastCpu = 0.12
            while (isActive) {
                lastCpu = (lastCpu + Random.nextDouble(-0.08, 0.08)).coerceIn(0.02, 0.97)
                cpu.value = lastCpu
                memUsed.value = (memUsed.value + Random.nextLong(-64, 64) * MiB)
                    .coerceIn(1 * GiB, 6 * GiB)
                metricsFlow.value = metricsFlow.value + (
                    DEMO_AGENT_ID to (metricsFlow.value[DEMO_AGENT_ID]!!.copy(
                        cpuPercent = lastCpu * 100.0,
                        memUsedBytes = memUsed.value,
                        rxBytesPerSec = Random.nextLong(1_000, 900_000),
                        txBytesPerSec = Random.nextLong(1_000, 300_000),
                    ))
                    )
                delay(2_000)
            }
        }
    }

    override suspend fun action(agentId: String, containerId: String, kind: String): Result<Unit> {
        delay(600) // имитация запроса к релею
        containersFlow.value = containersFlow.value.mapValues { (_, list) ->
            list.map {
                if (it.id == containerId && kind == "ACTION_RESTART") {
                    it.copy(state = "RUNNING", status = "Restarted just now")
                } else {
                    it
                }
            }
        }
        return Result.success(Unit)
    }

    override fun requestLogs(agentId: String, containerId: String) {
        logLines.value = emptyList()
        scope.launch {
            val samples = listOf(
                LogLineUi(0, false, "listening on :8080"),
                LogLineUi(0, false, "GET /health 200 2ms"),
                LogLineUi(0, true, "ERROR: connection refused upstream"),
                LogLineUi(0, false, "cache hit ratio 0.98"),
                LogLineUi(0, true, "WARN: slow query 1.4s SELECT ..."),
            )
            var i = 0
            while (isActive) {
                val s = samples[i % samples.size]
                logLines.value = (logLines.value + s.copy(tsUnix = System.currentTimeMillis() / 1000, text = "[$i] ${s.text}"))
                    .takeLast(2_000)
                i++
                delay(700)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** Полезные комбинаторы для UI. */
fun <T> combineSelected(mapFlow: Flow<Map<String, T>>, selected: Flow<String?>): Flow<T?> =
    combine(mapFlow, selected) { map, sel -> sel?.let { map[it] } }

private val KiB = 1024L
private val MiB = 1024L * KiB
private val GiB = 1024L * MiB
