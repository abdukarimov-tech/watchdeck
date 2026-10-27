package app.pulse.net

import app.pulse.data.AlertUi
import app.pulse.data.ContainerUi
import app.pulse.data.DiskUi
import app.pulse.data.LogLineUi
import app.pulse.data.MetricsUi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import pulse.v1.ContainerAction
import pulse.v1.ContainerActionKind
import pulse.v1.ContainerState
import pulse.v1.EventKind
import pulse.v1.LogsRequest
import pulse.v1.Msg

/** Состояние соединения с релеем. */
enum class ConnState { CONNECTING, CONNECTED, DISCONNECTED }

// ---------- маппинг protobuf -> UI-модели (чистые функции, покрыты тестами) ----------

fun pulse.v1.MetricsReport.toUi(): MetricsUi = MetricsUi(
    cpuPercent = cpuUsagePercent,
    cpuCores = cpuCores,
    memTotalBytes = memTotal,
    memUsedBytes = memUsed,
    disks = disksList.map { DiskUi(it.mount, it.total, it.used) },
    rxBytesPerSec = net?.rxBytesPerSec ?: 0,
    txBytesPerSec = net?.txBytesPerSec ?: 0,
    uptimeSeconds = uptimeSeconds,
)

fun pulse.v1.Container.toUi(): ContainerUi = ContainerUi(
    id = id,
    name = name,
    image = image,
    state = stateLabel(state),
    status = status,
)

fun pulse.v1.LogLine.toUi(): LogLineUi = LogLineUi(tsUnix, stderr, text)

fun pulse.v1.EventAlert.toUi(agentId: String = ""): AlertUi = AlertUi(
    kind = kindLabel(kind),
    containerName = containerName,
    message = message,
    atUnix = atUnix,
    agentId = agentId,
)

fun stateLabel(state: ContainerState): String = when (state) {
    ContainerState.RUNNING -> "RUNNING"
    ContainerState.CREATED -> "CREATED"
    ContainerState.PAUSED -> "PAUSED"
    ContainerState.RESTARTING -> "RESTARTING"
    ContainerState.EXITED -> "EXITED"
    ContainerState.DEAD -> "DEAD"
    else -> "UNKNOWN"
}

fun kindLabel(kind: EventKind): String = when (kind) {
    EventKind.CONTAINER_DIED -> "CONTAINER_DIED"
    EventKind.CONTAINER_STARTED -> "CONTAINER_STARTED"
    EventKind.OOM -> "OOM"
    else -> "EVENT_UNKNOWN"
}

/**
 * Живое соединение с релеем: WebSocket (подписка на всех агентов), автоперепод-
 * ключение с backoff, REST для списка агентов. Классы протокола генерируются
 * из proto/pulse.proto (protobuf-kotlin-lite).
 */
class PulseConnection(
    private val scope: CoroutineScope,
    baseUrl: String,
    private val token: String,
) {
    private val client = OkHttpClient.Builder()
        // протокольный ping держит NAT/прокси открытыми и детектит мертвый сокет
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val normalizedBase = baseUrl.trimEnd('/').removeSuffix("/ws/client")

    val connectionState = MutableStateFlow(ConnState.DISCONNECTED)
    val agents = MutableStateFlow<Map<String, String>>(emptyMap())
    val metricsByAgent = MutableStateFlow<Map<String, MetricsUi>>(emptyMap())
    val containersByAgent = MutableStateFlow<Map<String, List<ContainerUi>>>(emptyMap())
    val logLines = MutableStateFlow<List<LogLineUi>>(emptyList())
    val events = MutableStateFlow<List<AlertUi>>(emptyList())

    private val msgId = AtomicLong(1)
    private var webSocket: WebSocket? = null
    private val activeLogStream = MutableStateFlow("")
    private val pendingActions = ConcurrentHashMap<Long, CompletableDeferred<Result<Unit>>>()
    private val reopen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var backoffMs = 2_000L

    init {
        scope.launch { reconnectLoop() }
        scope.launch { appPingLoop() }
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (connectionState.value == ConnState.CONNECTED) refreshAgents()
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 2_000L
            connectionState.value = ConnState.CONNECTED
            scope.launch { refreshAgents() }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msg = runCatching { Msg.parseFrom(bytes.toByteArray()) }.getOrNull() ?: return
            handle(msg)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            reopen.tryEmit(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            reopen.tryEmit(Unit)
        }
    }

    private suspend fun reconnectLoop() {
        while (scope.isActive) {
            connectionState.value = ConnState.CONNECTING
            val request = Request.Builder()
                .url("$normalizedBase/ws/client")
                .header("Authorization", "Bearer $token")
                .build()
            webSocket = client.newWebSocket(request, listener)
            reopen.first() // ждем закрытия/ошибки
            connectionState.value = ConnState.DISCONNECTED
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun appPingLoop() {
        while (scope.isActive) {
            delay(45_000)
            if (connectionState.value == ConnState.CONNECTED) {
                sendMsg(Msg.newBuilder().setPing(pulse.v1.Ping.newBuilder().setSeq(msgId.get()).build()).build())
            }
        }
    }

    private fun handle(msg: Msg) {
        val agent = msg.target.ifEmpty { return }
        when (msg.payloadCase) {
            Msg.PayloadCase.METRICS ->
                metricsByAgent.value = metricsByAgent.value + (agent to msg.metrics.toUi())
            Msg.PayloadCase.CONTAINER_LIST ->
                containersByAgent.value =
                    containersByAgent.value + (agent to msg.containerList.containersList.map { it.toUi() })
            Msg.PayloadCase.CONTAINER_ACTION_RESULT -> {
                val result = if (msg.containerActionResult.ok) {
                    Result.success(Unit)
                } else {
                    Result.failure(PulseError.Relay(msg.containerActionResult.error.ifEmpty { "action failed" }))
                }
                pendingActions.remove(msg.id)?.complete(result)
            }
            Msg.PayloadCase.LOGS_CHUNK ->
                if (msg.logsChunk.streamId == activeLogStream.value) {
                    logLines.value = (logLines.value + msg.logsChunk.linesList.map { it.toUi() })
                        .takeLast(MAX_LOG_BUFFER)
                }
            Msg.PayloadCase.EVENT_ALERT ->
                events.value = (listOf(msg.eventAlert.toUi(agent)) + events.value).take(MAX_EVENT_BUFFER)
            else -> {}
        }
    }

    private fun nextId(): Long = msgId.getAndIncrement()

    private fun sendMsg(msg: Msg): Boolean =
        webSocket?.send(msg.toByteArray().toByteString()) ?: false

    /** Список подключенных агентов через REST (нужны hostname для UI). */
    suspend fun refreshAgents() {
        val restBase = normalizedBase
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        val request = Request.Builder()
            .url("$restBase/api/agents")
            .header("Authorization", "Bearer $token")
            .build()
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    val parsed = buildMap {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            put(o.getString("agent_id"), o.optString("hostname"))
                        }
                    }
                    agents.value = parsed
                }
            }
        }
    }

    suspend fun action(agentId: String, containerId: String, kind: String): Result<Unit> {
        val id = nextId()
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingActions[id] = deferred
        val protoKind = runCatching { ContainerActionKind.valueOf(kind) }
            .getOrElse { ContainerActionKind.ACTION_UNSPECIFIED }
        val sent = sendMsg(
            Msg.newBuilder()
                .setId(id)
                .setTarget(agentId)
                .setContainerAction(
                    ContainerAction.newBuilder()
                        .setContainerId(containerId)
                        .setKind(protoKind),
                )
                .build(),
        )
        if (!sent) {
            pendingActions.remove(id)
            return Result.failure(PulseError.Offline)
        }
        return withTimeoutOrNull(10_000) { deferred.await() }
            ?: Result.failure(PulseError.Timeout)
    }

    fun requestLogs(agentId: String, containerId: String) {
        val streamId = java.util.UUID.randomUUID().toString()
        activeLogStream.value = streamId
        logLines.value = emptyList()
        sendMsg(
            Msg.newBuilder()
                .setTarget(agentId)
                .setLogsRequest(
                    LogsRequest.newBuilder()
                        .setContainerId(containerId)
                        .setTailLines(200)
                        .setFollow(true)
                        .setStreamId(streamId),
                )
                .build(),
        )
    }

    fun close() {
        webSocket?.close(1000, "client closed")
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val MAX_LOG_BUFFER = 2_000
        const val MAX_EVENT_BUFFER = 200
    }
}
