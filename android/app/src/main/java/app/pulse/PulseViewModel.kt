package app.pulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pulse.data.AlertUi
import app.pulse.data.ContainerUi
import app.pulse.data.LogLineUi
import app.pulse.data.MetricsUi
import app.pulse.data.PulseRepository
import app.pulse.data.combineSelected
import app.pulse.net.ConnState
import app.pulse.net.PulseError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Экраны живут на интерфейсе [PulseRepository] (живой WS или демо-мок).
 * Локализация тостов — через строковые ресурсы по локали устройства.
 */
class PulseViewModel(
    application: Application,
    private val repo: PulseRepository,
) : AndroidViewModel(application) {

    val connectionState: StateFlow<ConnState> = repo.connectionState
    val agents: StateFlow<Map<String, String>> = repo.agents
    val events: StateFlow<List<AlertUi>> = repo.events
    val logLines: StateFlow<List<LogLineUi>> = repo.logLines

    /** Выбранный сервер (agent_id). null, пока список пуст. */
    val selectedAgent = MutableStateFlow<String?>(null)

    val metrics: Flow<MetricsUi?> = combineSelected(repo.metricsByAgent, selectedAgent)
    val containers: Flow<List<ContainerUi>> = combineSelected(
        repo.containersByAgent,
        selectedAgent,
    ).map { it ?: emptyList() }

    /** Сообщения для snackbar (ошибки действий, события алертов). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    init {
        viewModelScope.launch {
            repo.agents.collect { map ->
                if (selectedAgent.value == null && map.isNotEmpty()) {
                    selectedAgent.value = map.keys.first()
                }
            }
        }
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

    fun selectAgent(agentId: String) {
        if (selectedAgent.value != agentId) {
            selectedAgent.value = agentId
            // активный стрим логов принадлежит прежнему серверу — перезапросим
            lastLogsContainer?.let { repo.requestLogs(agentId, it) }
        }
    }

    private var lastLogsContainer: String? = null

    fun startLogs(containerId: String) {
        lastLogsContainer = containerId
        val agent = selectedAgent.value ?: return
        repo.requestLogs(agent, containerId)
    }

    fun action(containerId: String, kind: String) {
        val agent = selectedAgent.value ?: return
        viewModelScope.launch {
            repo.action(agent, containerId, kind)
                .onFailure { e ->
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
        repo.close()
    }

    class Factory(
        private val app: Application,
        private val repo: PulseRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PulseViewModel(app, repo) as T
    }
}
