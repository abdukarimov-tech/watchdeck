package app.pulse.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Источник данных сервера. В MVP — [MockPulseRepository]; переключение на
 * живой WS-канал к релею произойдет в net/WsPulseRepository без изменения UI.
 */
interface PulseRepository {
    fun metrics(): Flow<MetricsUi>
    fun containers(): Flow<List<ContainerUi>>
    suspend fun action(containerId: String, kind: String): Result<Unit>
    fun logs(containerId: String, follow: Boolean): Flow<LogLineUi>
}

class MockPulseRepository(private val scope: CoroutineScope) : PulseRepository {

    private val cpu = MutableStateFlow(0.12)
    private val memUsed = MutableStateFlow(2 * GiB + 512 * MiB)
    private val containersFlow = MutableStateFlow(
        listOf(
            ContainerUi("a1b2c3d4e5f6", "web-nginx", "nginx:1.25", "RUNNING", "Up 3 days (healthy)"),
            ContainerUi("ffeeddccbbaa", "batch-worker", "worker:latest", "EXITED", "Exited (137) 2 minutes ago"),
            ContainerUi("998877665544", "postgres", "postgres:16", "RUNNING", "Up 12 days"),
            ContainerUi("112233445566", "redis", "redis:7", "RUNNING", "Up 12 days"),
        ),
    )

    init {
        scope.launch {
            var lastCpu = 0.12
            while (isActive) {
                lastCpu = (lastCpu + Random.nextDouble(-0.08, 0.08)).coerceIn(0.02, 0.97)
                cpu.value = lastCpu
                memUsed.value = (memUsed.value + Random.nextLong(-64, 64) * MiB)
                    .coerceIn(1 * GiB, 6 * GiB)
                delay(2_000)
            }
        }
    }

    override fun metrics(): Flow<MetricsUi> = combineLatest(cpu, memUsed).map { (c, mem) ->
        MetricsUi(
            cpuPercent = c * 100.0,
            cpuCores = 4,
            memTotalBytes = 8 * GiB,
            memUsedBytes = mem,
            disks = listOf(
                DiskUi("/", 80 * GiB, 43 * GiB),
                DiskUi("/var/lib/docker", 40 * GiB, 31 * GiB),
            ),
            rxBytesPerSec = Random.nextLong(1_000, 900_000),
            txBytesPerSec = Random.nextLong(1_000, 300_000),
            uptimeSeconds = 12 * 86_400 + 5 * 3_600,
        )
    }

    override fun containers(): Flow<List<ContainerUi>> = containersFlow

    override suspend fun action(containerId: String, kind: String): Result<Unit> {
        delay(600) // имитация запроса к релею
        containersFlow.value = containersFlow.value.map {
            if (it.id == containerId && kind == "ACTION_RESTART") {
                it.copy(state = "RUNNING", status = "Restarted just now")
            } else {
                it
            }
        }
        return Result.success(Unit)
    }

    override fun logs(containerId: String, follow: Boolean): Flow<LogLineUi> =
        kotlinx.coroutines.flow.flow {
            val samples = listOf(
                LogLineUi(0, false, "listening on :8080"),
                LogLineUi(0, false, "GET /health 200 2ms"),
                LogLineUi(0, true, "ERROR: connection refused upstream"),
                LogLineUi(0, false, "cache hit ratio 0.98"),
                LogLineUi(0, true, "WARN: slow query 1.4s SELECT ..."),
            )
            var i = 0
            while (true) {
                val s = samples[i % samples.size]
                emit(s.copy(tsUnix = System.currentTimeMillis() / 1000, text = "[$i] ${s.text}"))
                i++
                if (!follow) break
                delay(700)
            }
        }

    private fun <A, B> combineLatest(a: Flow<A>, b: Flow<B>): Flow<Pair<A, B>> =
        kotlinx.coroutines.flow.combine(a, b) { x, y -> x to y }

    private companion object {
        const val KiB = 1024L
        const val MiB = 1024L * KiB
        const val GiB = 1024L * MiB
    }
}
