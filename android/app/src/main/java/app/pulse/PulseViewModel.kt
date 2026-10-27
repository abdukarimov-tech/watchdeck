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
import app.pulse.net.RelayApi
import app.pulse.net.SshInstaller
import app.pulse.net.WsPulseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Сессия приложения поверх списка серверов ([ServersStore]).
 *
 * Все добавленные серверы подключены одновременно (по одному WS на релей):
 * их данные собираются в per-server потоки для виджетов ([serverStates],
 * [serverMetrics], [serverAgents]), а [activeServerId] определяет, какой
 * репозиторий показан на основных вкладках.
 */
class PulseViewModel(application: Application) : AndroidViewModel(application) {

    private val serversStore = ServersStore(application)

    val servers = MutableStateFlow(serversStore.load())
    val activeServerId = MutableStateFlow(serversStore.activeId())

    private val repos = linkedMapOf<String, PulseRepository>()

    // ---- данные по каждому серверу (для виджетов в окне «Серверы») ----
    val serverStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val serverMetrics = MutableStateFlow<Map<String, MetricsUi>>(emptyMap())
    val serverAgents = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val serverContainers = MutableStateFlow<Map<String, List<ContainerUi>>>(emptyMap())

    // ---- репозиторий активного сервера (основные вкладки) ----
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

    /** Выбранный агент внутри активного сервера. */
    val selectedAgent = MutableStateFlow<String?>(null)

    val metrics: Flow<MetricsUi?> = combineSelected(metricsByAgent, selectedAgent)
    val containers: Flow<List<ContainerUi>> = combineSelected(containersByAgent, selectedAgent).map { it ?: emptyList() }

    /** Сообщения для snackbar (ошибки действий, события алертов). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Прогресс SSH-установки агента: строки лога + статус. */
    data class InstallState(val lines: List<String>, val done: Boolean, val error: String?)
    val installState = MutableStateFlow<InstallState?>(null)

    /**
     * Полная установка: выпуск токенов через админ-API релея, SSH-подключение
     * и установка агента, добавление сервера в приложение.
     */
    fun installAndConnect(
        host: String,
        port: Int,
        username: String,
        auth: SshInstaller.SshConfig.Auth,
        relayUrl: String,
        adminToken: String,
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
                val api = RelayApi(relayUrl)
                log("[relay] creating client token")
                val clientToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.createToken(adminToken, "phone", "client")
                }
                log("[relay] creating agent token")
                val agentToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.createToken(adminToken, host, "agent")
                }
                SshInstaller { log("[ssh] $it") }.install(
                    SshInstaller.SshConfig(host, port, username, auth),
                    relayUrl,
                    agentToken,
                )
                installState.value = InstallState(lines.toList(), done = true, error = null)
                addServer(relayUrl, clientToken)
            } catch (e: Exception) {
                val message = when (e) {
                    is net.schmizz.sshj.userauth.UserAuthException -> "SSH authentication failed (wrong login/password or key)"
                    is java.io.IOException -> e.message ?: "io error"
                    else -> e.toString()
                }
                lines.add("[error] $message")
                installState.value = InstallState(lines.toList(), done = true, error = message)
            }
        }
    }

    private var lastLogsContainer: String? = null

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
    }

    fun repoFor(id: String?): PulseRepository? = id?.let { repos[it] }

    fun selectServer(id: String) {
        if (activeServerId.value == id) return
        serversStore.setActive(id)
        activeServerId.value = id
        displayRepo.value = repos[id]
        selectedAgent.value = null
        lastLogsContainer = null
    }

    /** Добавляет сервер, подключает его и сразу делает активным. */
    fun addServer(relayUrl: String, token: String) {
        val config = serversStore.add(
            relayUrl = relayUrl,
            token = token,
            label = ServersStore.labelForUrl(relayUrl),
        )
        servers.value = serversStore.load()
        syncRepos()
        selectServer(config.id)
    }

    /** Демо-режим как псевдо-сервер. */
    fun addDemo(demoLabel: String) {
        serversStore.addDemo(demoLabel)
        servers.value = serversStore.load()
        syncRepos()
        selectServer(ServersStore.DEMO_ID)
    }

    fun removeServer(id: String) {
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

    /** Полный сброс: удаляет все серверы и возвращает приложение к онбордингу. */
    fun resetAll() {
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
        // отключаем удаленные
        repos.keys.filterNot { it in wanted }.forEach { id ->
            repos.remove(id)?.close()
            dropServerData(id)
        }
        // подключаем новые
        servers.value.forEach { cfg ->
            if (!repos.containsKey(cfg.id)) {
                attach(
                    cfg.id,
                    when {
                        cfg.isDemo -> MockPulseRepository()
                        else -> WsPulseRepository(ServerSecretStore.ServerConfig(cfg.relayUrl, cfg.token))
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
                when (alert.kind) {
                    "CONTAINER_DIED", "OOM" ->
                        toasts.emit(
                            getApplication<Application>().getString(
                                R.string.alert_toast,
                                alert.containerName,
                                alert.message.ifEmpty { alert.kind },
                            ),
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
            legacy != null -> serversStore.add(legacy.relayUrl, legacy.token, ServersStore.labelForUrl(legacy.relayUrl)).id
            else -> return
        }
        serversStore.setActive(migratedId)
        servers.value = serversStore.load()
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
}
