package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.crypto.ServerSecretStore
import app.pulse.ui.screens.ContainersScreen
import app.pulse.ui.screens.DashboardScreen
import app.pulse.ui.screens.LogsScreen
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {

    private lateinit var store: ServerSecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerSecretStore(this)
        val saved = store.load()
        setContent {
            PulseTheme {
                var connected by remember { mutableStateOf(saved != null) }
                if (!connected) {
                    OnboardingScreen { url, token ->
                        store.save(ServerSecretStore.ServerConfig(url, token))
                        connected = true
                    }
                } else {
                    MainScreen()
                }
            }
        }
    }
}

private enum class Tab(val title: String) {
    Dashboard("Сервер"),
    Containers("Контейнеры"),
    Logs("Логи"),
}

@Composable
private fun MainScreen(vm: PulseViewModel = viewModel()) {
    val metrics by vm.metrics.collectAsState(initial = null)
    val containers by vm.containers.collectAsState(initial = emptyList())
    val logs by vm.logs.collectAsState()
    var tab by remember { mutableStateOf(Tab.Dashboard) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            when (t) {
                                Tab.Dashboard -> Icon(Icons.Filled.Dns, null)
                                Tab.Containers -> Icon(Icons.Filled.SmartToy, null)
                                Tab.Logs -> Icon(Icons.Filled.Terminal, null)
                            }
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Dashboard -> DashboardScreen(metrics)
                Tab.Containers -> ContainersScreen(containers, onAction = vm::action)
                Tab.Logs -> {
                    // MVP: стримим логи первого запущенного контейнера;
                    // выбор контейнера появится вместе с живым WS
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        containers.firstOrNull { it.state == "RUNNING" }
                            ?.let { vm.startLogs(it.id) }
                    }
                    LogsScreen(logs)
                }
            }
        }
    }
}
