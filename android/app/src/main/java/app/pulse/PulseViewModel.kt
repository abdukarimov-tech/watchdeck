package app.pulse

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pulse.crypto.ServerSecretStore
import app.pulse.crypto.ServersStore
import app.pulse.data.AlertUi
import app.pulse.data.ContainerUi
import app.pulse.data.LogLineUi
import app.pulse.data.MetricsUi
import app.pulse.data.MockPulseRepository
import app.pulse.data.PulseRepository
import app.pulse.data.combineSelected
import app.pulse.net.ConnState
import app.pulse.net.PulseError
import app.pulse.net.RelayAdmin
import app.pulse.net.SshInstaller
import app.pulse.net.WsPulseRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Сессия приложения поверх списка серверов ([ServersStore]).
 *
 * Все добавленные серверы подключены одновременно (по одному WS на релей):
 * их данные собираются в per-server потоки для виджетов ([serverStates],
 * [serverMetrics], [serverAgents]), а [activeServerId] определяет, какой
 * репозиторий показан на основном экране.
 */
class PulseViewModel(application: Application) : AndroidViewModel(application) {

    private val serversStore = ServersStore(application)

    val servers = MutableStateFlow(serversStore.load())
    val activeServerId = MutableStateFlow(serversStore.activeId())

    private val repos = linkedMapOf<String, PulseRepository>()

    // ---- данные по каждому серверу (для виджетов) ----
    val serverStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val serverMetrics = MutableStateFlow<Map<String, MetricsUi>>(emptyMap())
    val serverAgents = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val serverContainers = MutableStateFlow<Map<String, List<ContainerUi>>>(emptyMap())

    // ---- репозиторий активного сервера ----
    private val displayRepo = MutableStateFlow<PulseRepository?>(null)

    val connectionState: Flow<ConnState> = displayRepo.flatMapLatest { repo ->
        repo?.connectionState ?: flowOf(ConnState.DISCONNECTED)
    }
    val agents: Flow<Map<String, String>> = displayRepo.flatMapLatest { repo ->
        repo?.agents ?: flowOf(emptyMap())
    }
    val metricsByAgent: Flow<Map<String, MetricsUi>> = displayRepo.flatMapLatest { repo ->
        repo?.metricsByAgent ?: flowOf(emptyMap())
    }
    val containersByAgent: Flow<Map<String, List<ContainerUi>>> = displayRepo.flatMapLatest { repo ->
        repo?.containersByAgent ?: flowOf(emptyMap())
    }
    val logLines: Flow<List<LogLineUi>> = displayRepo.flatMapLatest { repo ->
        repo?.logLines ?: flowOf(emptyList())
    }
    val events: Flow<List<AlertUi>> = displayRepo.flatMapLatest { repo ->
        repo?.events ?: flowOf(emptyList())
    }
    val versions: Flow<Map<String, String>> = displayRepo.flatMapLatest { repo ->
        repo?.versions ?: flowOf(emptyMap())
    }

    /** Выбранный агент внутри активного сервера. */
    val selectedAgent = MutableStateFlow<String?>(null)

    val metrics: Flow<MetricsUi?> = combineSelected(metricsByAgent, selectedAgent)
    val containers: Flow<List<ContainerUi>> = combineSelected(containersByAgent, selectedAgent).map { it ?: emptyList() }

    /** Сообщения для snackbar (ошибки действий, события алертов). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var lastLogsContainer: String? = null

    /** Плановое отключение (сброс/удаление/переключение) — без уведомления. */
    private val watchdogSuppressed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Прогресс SSH-установки агента: строки лога + статус. */
    data class InstallState(val lines: List<String>, val done: Boolean, val error: String?)
    val installState = MutableStateFlow<InstallState?>(null)

    /** Ожидающее подтверждение отпечатка SSH-ключа сервера. */
    data class HostKeyPrompt(val fingerprint: String, val decision: kotlinx.coroutines.CompletableDeferred<Boolean>)
    val hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)

    init {
        migrateLegacyConfig()
        syncRepos()
        if (activeServerId.value == null) activeServerId.value = servers.value.firstOrNull()?.id
        displayRepo.value = repos[activeServerId.value]

        viewModelScope.launch {
            agents.collect { map ->
                if (selectedAgent.value == null && map.isNotEmpty()) {
                    selectedAgent.value = map.keys.first()
                }
            }
        }
        startRelayWatchdog()
    }

    fun repoFor(id: String?): PulseRepository? = id?.let { repos[it] }

    fun selectServer(id: String) {
        if (activeServerId.value == id) return
        watchdogSuppressed.set(true)
        serversStore.setActive(id)
        activeServerId.value = id
        displayRepo.value = repos[id]
        selectedAgent.value = null
        lastLogsContainer = null
    }

    /** Демо-режим как псевдо-сервер. */
    fun addDemo(demoLabel: String) {
        serversStore.addDemo(demoLabel)
        servers.value = serversStore.load()
        syncRepos()
        selectServer(ServersStore.DEMO_ID)
    }

    fun removeServer(id: String) {
        watchdogSuppressed.set(true)
        serversStore.remove(id)
        servers.value = serversStore.load()
        repos.remove(id)?.close()
        dropServerData(id)
        if (activeServerId.value == id) {
            activeServerId.value = servers.value.firstOrNull()?.id
            displayRepo.value = repos[activeServerId.value]
            selectedAgent.value = null
            lastLogsContainer = null
        }
    }

    /** Полный сброс: сносит установку на серверах по SSH, затем чистит локально. */
    fun resetAll() {
        watchdogSuppressed.set(true)
        val toWipe = servers.value.filter {
            !it.isDemo && it.sshAuthMode.isNotEmpty() && it.sshSecret.isNotEmpty()
        }
        if (toWipe.isEmpty()) {
            hardResetLocal()
            return
        }
        viewModelScope.launch {
            val installer = SshInstaller { }
            toWipe.forEach { srv ->
                runCatching {
                    kotlinx.coroutines.withTimeout(45_000) {
                        installer.uninstallServer(
                            SshInstaller.SshConfig(
                                host = srv.label.ifEmpty { srv.relayUrl },
                                port = srv.sshPort,
                                username = srv.sshUser,
                                auth = when (srv.sshAuthMode) {
                                    "key" -> SshInstaller.SshConfig.Auth.Key(srv.sshSecret, srv.sshKeyPass.ifBlank { null })
                                    else -> SshInstaller.SshConfig.Auth.Password(srv.sshSecret)
                                },
                            ),
                            expectedFp = srv.sshHostKeyFp,
                        )
                    }
                }
            }
            hardResetLocal()
        }
    }

    private fun hardResetLocal() {
        repos.values.forEach { it.close() }
        repos.clear()
        serversStore.clear()
        servers.value = emptyList()
        activeServerId.value = null
        displayRepo.value = null
        selectedAgent.value = null
        lastLogsContainer = null
        serverStates.value = emptyMap()
        serverMetrics.value = emptyMap()
        serverAgents.value = emptyMap()
        serverContainers.value = emptyMap()
    }

    private fun syncRepos() {
        val wanted = servers.value.map { it.id }.toSet()
        repos.keys.filterNot { it in wanted }.forEach { id ->
            repos.remove(id)?.close()
            dropServerData(id)
        }
        servers.value.forEach { cfg ->
            if (!repos.containsKey(cfg.id)) {
                attach(
                    cfg.id,
                    when {
                        cfg.isDemo -> MockPulseRepository()
                        else -> WsPulseRepository(cfg)
                    },
                )
            }
        }
    }

    /** Подключает репозиторий и подписывает его потоки на per-server состояние. */
    private fun attach(id: String, repo: PulseRepository) {
        repos[id] = repo
        viewModelScope.launch {
            repo.connectionState.collect { st -> serverStates.update { it + (id to st) } }
        }
        viewModelScope.launch {
            repo.agents.collect { m -> serverAgents.update { it + (id to m) } }
        }
        viewModelScope.launch {
            repo.metricsByAgent.collect { m ->
                aggregate(m)?.let { agg -> serverMetrics.update { it + (id to agg) } }
            }
        }
        viewModelScope.launch {
            repo.containersByAgent.collect { m ->
                serverContainers.update { it + (id to m.values.flatten()) }
            }
        }
        // алерты со всех серверов — в snackbar
        viewModelScope.launch {
            repo.events.collect { list ->
                val alert = list.firstOrNull() ?: return@collect
                val app = getApplication<Application>()
                when (alert.kind) {
                    "CONTAINER_DIED", "OOM" -> toasts.emit(
                        app.getString(
                            R.string.alert_toast,
                            alert.containerName,
                            alert.message.ifEmpty { alert.kind },
                        ),
                    )
                    "SERVER_OFFLINE" -> toasts.emit(
                        app.getString(R.string.alert_server_offline, alert.agentId),
                    )
                }
            }
        }
    }

    private fun dropServerData(id: String) {
        serverStates.update { it - id }
        serverMetrics.update { it - id }
        serverAgents.update { it - id }
        serverContainers.update { it - id }
    }

    /** Сводные метрики сервера для виджета (средний CPU, суммарная память). */
    private fun aggregate(byAgent: Map<String, MetricsUi>): MetricsUi? {
        if (byAgent.isEmpty()) return null
        val list = byAgent.values.toList()
        if (list.size == 1) return list[0]
        val n = list.size
        return list.first().copy(
            cpuPercent = list.sumOf { it.cpuPercent } / n,
            memUsedBytes = list.sumOf { it.memUsedBytes },
            memTotalBytes = list.sumOf { it.memTotalBytes },
        )
    }

    /** Однократный перенос старого одиночного конфига в список серверов. */
    private fun migrateLegacyConfig() {
        if (servers.value.isNotEmpty()) return
        val legacyDemo = getApplication<Application>()
            .getSharedPreferences("pulse_mode", Context.MODE_PRIVATE)
            .getString("mode", null) == "demo"
        val legacy = ServerSecretStore(getApplication()).load()
        val migratedId = when {
            legacyDemo -> serversStore.addDemo(
                getApplication<Application>().getString(R.string.demo_label),
            ).id
            legacy != null -> serversStore.add(
                ServersStore.ServerConfig(
                    id = "",
                    label = ServersStore.labelForUrl(legacy.relayUrl),
                    relayUrl = legacy.relayUrl,
                    token = legacy.token,
                ),
            ).id
            else -> return
        }
        serversStore.setActive(migratedId)
        servers.value = serversStore.load()
    }

    /** Сброс состояния установки (кнопка «Назад» после ошибки). */
    fun resetInstall() {
        installState.value = null
    }

    fun confirmHostKey(accepted: Boolean) {
        val prompt = hostKeyPrompt.value
        prompt?.decision?.complete(accepted)
        hostKeyPrompt.value = null
        if (!accepted) {
            installState.update {
                InstallState(
                    lines = (it?.lines ?: emptyList()) + "[error] cancelled: host key rejected",
                    done = true,
                    error = "host key rejected",
                )
            }
        }
    }

    /**
     * Полная установка по SSH: при необходимости ставит релей на этот же
     * сервер, выпускает токены (локально на сервере), ставит агента
     * и добавляет сервер в приложение. Данные релея ниоткуда не вводятся.
     */
    fun installAndConnect(
        host: String,
        port: Int,
        username: String,
        auth: SshInstaller.SshConfig.Auth,
    ) {
        if (installState.value?.done == false) return
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            fun log(message: String) {
                lines.add(message)
                installState.value = InstallState(lines.toList(), done = false, error = null)
            }
            try {
                installState.value = InstallState(emptyList(), done = false, error = null)
                val result = SshInstaller { log("[ssh] $it") }.installServer(
                    SshInstaller.SshConfig(host, port, username, auth),
                    verifyHostKey = { fingerprint ->
                        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        hostKeyPrompt.value = HostKeyPrompt(fingerprint, decision)
                        decision.await().also { hostKeyPrompt.value = null }
                    },
                )
                installState.value = InstallState(lines.toList(), done = true, error = null)
                serversStore.add(
                    ServersStore.ServerConfig(
                        id = "",
                        label = host,
                        relayUrl = result.relayUrl,
                        token = result.clientToken,
                        adminToken = result.adminToken,
                        sshPort = port,
                        sshUser = username,
                        sshAuthMode = when (auth) {
                            is SshInstaller.SshConfig.Auth.Password -> "password"
                            is SshInstaller.SshConfig.Auth.Key -> "key"
                        },
                        sshSecret = when (auth) {
                            is SshInstaller.SshConfig.Auth.Password -> auth.password
                            is SshInstaller.SshConfig.Auth.Key -> auth.privateKey
                        },
                        sshKeyPass = (auth as? SshInstaller.SshConfig.Auth.Key)?.passphrase ?: "",
                        relayCertPem = result.relayCertPem,
                        sshHostKeyFp = result.hostKeyFp,
                    ),
                )
                servers.value = serversStore.load()
                syncRepos()
                selectServer(servers.value.firstOrNull { it.label == host }?.id ?: servers.value.last().id)
                registerPushDevices()
            } catch (e: Exception) {
                val message = when (e) {
                    is net.schmizz.sshj.userauth.UserAuthException ->
                        "SSH authentication failed (wrong login/password or key)"
                    is java.io.IOException -> e.message ?: "io error"
                    else -> e.toString()
                }
                lines.add("[error] $message")
                installState.value = InstallState(lines.toList(), done = true, error = message)
            }
        }
    }

    // ---- watchdog: локальное уведомление при потере релея ----
    private fun startRelayWatchdog() {
        viewModelScope.launch {
            var wasConnected = false
            connectionState.collect { st ->
                if (wasConnected && st == ConnState.DISCONNECTED) {
                    if (!watchdogSuppressed.getAndSet(false)) {
                        postRelayDownNotification(getApplication())
                    }
                }
                wasConnected = st == ConnState.CONNECTED
            }
        }
    }

    private fun postRelayDownNotification(app: Application) {
        val manager = app.getSystemService(Application.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    app.getString(R.string.notif_channel_id),
                    app.getString(R.string.notif_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(app, app.getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(app.getString(R.string.notif_relay_down_title))
            .setContentText(app.getString(R.string.notif_relay_down_body))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(RELAY_DOWN_NOTIFICATION_ID, notification) }
    }

    /**
     * Регистрация FCM-токена устройства на каждом релее (push о событиях).
     * Молча пропускается, если Firebase не настроен (нет google-services.json).
     */
    fun registerPushDevices() {
        val app = getApplication<Application>()
        if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) return
        viewModelScope.launch {
            val fcmToken = runCatching {
                FirebaseMessaging.getInstance().token.await()
            }.getOrNull() ?: return@launch
            servers.value.filter { !it.isDemo }.forEach { cfg ->
                runCatching {
                    RelayAdmin(cfg.relayUrl, cfg.adminToken, cfg.relayCertPem)
                        .registerDevice(cfg.token, fcmToken, cfg.label)
                }.onFailure { e ->
                    android.util.Log.w("PulseVM", "device registration failed: ${e.message}")
                }
            }
        }
    }

    /** Обновление бинарника агента по SSH (по сохраненному доступу). */
    fun updateAgent(serverId: String) {
        val cfg = servers.value.firstOrNull { it.id == serverId } ?: return
        val sshCfg = cfg.toSshConfig()
        if (sshCfg == null) {
            toasts.tryEmit(getApplication<Application>().getString(R.string.terminal_unavailable))
            return
        }
        viewModelScope.launch {
            toasts.emit(getApplication<Application>().getString(R.string.agent_updating))
            runCatching {
                SshInstaller { }.updateAgent(sshCfg, cfg.sshHostKeyFp) { }
            }.onSuccess {
                toasts.emit(getApplication<Application>().getString(R.string.agent_updated))
            }.onFailure {
                toasts.emit(getApplication<Application>().getString(R.string.error_action_failed, it.message ?: ""))
            }
        }
    }

    fun selectAgent(agentId: String) {
        if (selectedAgent.value != agentId) {
            selectedAgent.value = agentId
            // активный стрим логов принадлежит прежнему агенту — перезапросим
            lastLogsContainer?.let { displayRepo.value?.requestLogs(agentId, it) }
        }
    }

    fun startLogs(containerId: String) {
        lastLogsContainer = containerId
        val agent = selectedAgent.value ?: return
        displayRepo.value?.requestLogs(agent, containerId)
    }

    fun action(containerId: String, kind: String) {
        val agent = selectedAgent.value ?: return
        viewModelScope.launch {
            displayRepo.value?.action(agent, containerId, kind)
                ?.onFailure { e ->
                    val app = getApplication<Application>()
                    val message = when (e) {
                        is PulseError.Offline -> app.getString(R.string.error_offline)
                        is PulseError.Timeout -> app.getString(R.string.error_timeout)
                        is PulseError.Relay -> app.getString(R.string.error_action_failed, e.reason)
                        else -> e.message ?: "error"
                    }
                    toasts.emit(message)
                }
        }
    }

    override fun onCleared() {
        repos.values.forEach { it.close() }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PulseViewModel(app) as T
    }

    private companion object {
        const val RELAY_DOWN_NOTIFICATION_ID = 1001
    }
}

/** SSH-конфиг из сохраненных данных сервера; null, если SSH-доступа нет. */
fun ServersStore.ServerConfig.toSshConfig(): SshInstaller.SshConfig? =
    if (isDemo || sshAuthMode.isBlank() || sshSecret.isBlank()) {
        null
    } else {
        SshInstaller.SshConfig(
            host = label,
            port = sshPort,
            username = sshUser,
            auth = when (sshAuthMode) {
                "key" -> SshInstaller.SshConfig.Auth.Key(sshSecret, sshKeyPass.ifBlank { null })
                else -> SshInstaller.SshConfig.Auth.Password(sshSecret)
            },
        )
    }
