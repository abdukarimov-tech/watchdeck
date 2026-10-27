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
import app.pulse.net.WsPulseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Сессия приложения поверх списка серверов ([ServersStore]).
 *
 * Активный сервер определяет реализацию [PulseRepository]: живой WS-канал
 * для реального релея, мок для демо. Переключение сервера закрывает старый
 * репозиторий и подменяет его — экраны перерисовываются на новых потоках.
 */
class PulseViewModel(application: Application) : AndroidViewModel(application) {

    private val serversStore = ServersStore(application)

    val servers = MutableStateFlow(serversStore.load())
    val activeServerId = MutableStateFlow(serversStore.activeId())

    private val repoFlow = MutableStateFlow<PulseRepository?>(null)

    val connectionState: Flow<ConnState> = repoFlow.flatMapLatest { repo ->
        repo?.connectionState ?: flowOf(ConnState.DISCONNECTED)
    }
    val agents: Flow<Map<String, String>> = repoFlow.flatMapLatest { repo ->
        repo?.agents ?: flowOf(emptyMap())
    }
    val metricsByAgent: Flow<Map<String, MetricsUi>> = repoFlow.flatMapLatest { repo ->
        repo?.metricsByAgent ?: flowOf(emptyMap())
    }
    val containersByAgent: Flow<Map<String, List<ContainerUi>>> = repoFlow.flatMapLatest { repo ->
        repo?.containersByAgent ?: flowOf(emptyMap())
    }
    val logLines: Flow<List<LogLineUi>> = repoFlow.flatMapLatest { repo ->
        repo?.logLines ?: flowOf(emptyList())
    }
    val events: Flow<List<AlertUi>> = repoFlow.flatMapLatest { repo ->
        repo?.events ?: flowOf(emptyList<AlertUi>())
    }

    /** Выбранный агент внутри активного сервера. */
    val selectedAgent = MutableStateFlow<String?>(null)

    val metrics: Flow<MetricsUi?> = combineSelected(metricsByAgent, selectedAgent)
    val containers: Flow<List<ContainerUi>> = combineSelected(containersByAgent, selectedAgent).map { it ?: emptyList() }

    /** Сообщения для snackbar (ошибки действий, события алертов). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var lastLogsContainer: String? = null

    init {
        migrateLegacyConfig()
        activate(activeServerId.value ?: servers.value.firstOrNull()?.id)

        viewModelScope.launch {
            agents.collect { map ->
                if (selectedAgent.value == null && map.isNotEmpty()) {
                    selectedAgent.value = map.keys.first()
                }
            }
        }
        viewModelScope.launch {
            events.collect { list ->
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

    fun selectServer(id: String) {
        if (activeServerId.value == id) return
        serversStore.setActive(id)
        activeServerId.value = id
        activate(id)
    }

    /** Добавляет сервер и сразу делает его активным. */
    fun addServer(relayUrl: String, token: String) {
        val config = serversStore.add(
            relayUrl = relayUrl,
            token = token,
            label = ServersStore.labelForUrl(relayUrl),
        )
        servers.value = serversStore.load()
        selectServer(config.id)
    }

    /** Демо-режим как псевдо-сервер. */
    fun addDemo(demoLabel: String) {
        serversStore.addDemo(demoLabel)
        servers.value = serversStore.load()
        selectServer(ServersStore.DEMO_ID)
    }

    fun removeServer(id: String) {
        serversStore.remove(id)
        servers.value = serversStore.load()
        if (activeServerId.value == id) {
            activate(servers.value.firstOrNull()?.id)
        }
    }

    private fun activate(id: String?) {
        repoFlow.value?.close()
        selectedAgent.value = null
        lastLogsContainer = null
        repoFlow.value = id?.let { serverId ->
            val config = servers.value.firstOrNull { it.id == serverId }
            when {
                config == null -> null
                config.isDemo -> MockPulseRepository()
                else -> WsPulseRepository(
                    ServerSecretStore.ServerConfig(config.relayUrl, config.token),
                )
            }
        }
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
            lastLogsContainer?.let { repoFlow.value?.requestLogs(agentId, it) }
        }
    }

    fun startLogs(containerId: String) {
        lastLogsContainer = containerId
        val agent = selectedAgent.value ?: return
        repoFlow.value?.requestLogs(agent, containerId)
    }

    fun action(containerId: String, kind: String) {
        val agent = selectedAgent.value ?: return
        viewModelScope.launch {
            repoFlow.value?.action(agent, containerId, kind)
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

    /** Полный сброс: удаляет все серверы и возвращает приложение к онбордингу. */
    fun resetAll() {
        repoFlow.value?.close()
        repoFlow.value = null
        serversStore.clear()
        servers.value = emptyList()
        activeServerId.value = null
        selectedAgent.value = null
        lastLogsContainer = null
    }

    override fun onCleared() {
        repoFlow.value?.close()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PulseViewModel(app) as T
    }
}
