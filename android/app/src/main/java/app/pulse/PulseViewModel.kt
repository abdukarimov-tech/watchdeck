package app.pulse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.data.ContainerUi
import app.pulse.data.LogLineUi
import app.pulse.data.MetricsUi
import app.pulse.data.MockPulseRepository
import app.pulse.data.PulseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Экраны живут на интерфейсе [PulseRepository]. Сейчас это мок; подключение
 * живого WS-канала к релею делается заменой реализации, UI не меняется.
 */
class PulseViewModel : ViewModel() {

    private val repo: PulseRepository = MockPulseRepository(viewModelScope)

    val metrics: Flow<MetricsUi?> = repo.metrics()
    val containers: Flow<List<ContainerUi>> = repo.containers()
    val logs = MutableStateFlow<List<LogLineUi>>(emptyList())

    fun startLogs(containerId: String) {
        logs.value = emptyList()
        viewModelScope.launch {
            repo.logs(containerId, follow = true).collect { logs.value = logs.value + it }
        }
    }

    fun action(containerId: String, kind: String) {
        viewModelScope.launch {
            repo.action(containerId, kind)
                .onFailure { // TODO: snackbar с ошибкой в UI
                }
        }
    }
}
