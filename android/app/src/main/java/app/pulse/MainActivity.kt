package app.pulse

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.crypto.ServerSecretStore
import app.pulse.data.MockPulseRepository
import app.pulse.net.ConnState
import app.pulse.net.WsPulseRepository
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
        val prefs = getSharedPreferences("pulse_mode", Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, null)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            PulseTheme {
                when (mode) {
                    MODE_DEMO -> MainScreen(
                        vm = viewModel(
                            key = "demo",
                            factory = PulseViewModel.Factory(application, MockPulseRepository()),
                        ),
                    )
                    MODE_LIVE -> {
                        val config = remember { store.load() }
                        if (config == null) {
                            OnboardingScreen { url, token, demo -> finishOnboarding(prefs, url, token, demo) }
                        } else {
                            MainScreen(
                                vm = viewModel(
                                    key = "live",
                                    factory = PulseViewModel.Factory(application, WsPulseRepository(config)),
                                ),
                            )
                        }
                    }
                    else -> OnboardingScreen { url, token, demo -> finishOnboarding(prefs, url, token, demo) }
                }
            }
        }
    }

    private fun finishOnboarding(prefs: android.content.SharedPreferences, url: String, token: String, demo: Boolean) {
        if (demo) {
            prefs.edit().putString(KEY_MODE, MODE_DEMO).apply()
        } else {
            store.save(ServerSecretStore.ServerConfig(url, token))
            prefs.edit().putString(KEY_MODE, MODE_LIVE).apply()
        }
        recreate()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val MODE_DEMO = "demo"
        const val MODE_LIVE = "live"
    }
}

private enum class Tab(val titleRes: Int) {
    Dashboard(R.string.tab_dashboard),
    Containers(R.string.tab_containers),
    Logs(R.string.tab_logs),
}

@Composable
private fun MainScreen(vm: PulseViewModel) {
    val connectionState by vm.connectionState.collectAsState()
    val agents by vm.agents.collectAsState()
    val selectedAgent by vm.selectedAgent.collectAsState()
    val metrics by vm.metrics.collectAsState(initial = null)
    val containers by vm.containers.collectAsState(initial = emptyList())
    val logs by vm.logLines.collectAsState()
    val toasts = remember { vm.toasts }
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.Dashboard) }

    LaunchedEffect(Unit) {
        toasts.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (connectionState != ConnState.CONNECTED) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = stringResource(
                            if (connectionState == ConnState.CONNECTING) {
                                R.string.conn_connecting
                            } else {
                                R.string.conn_offline
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        },
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
                        label = { Text(stringResource(t.titleRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (agents.size > 1) {
                // переключатель серверов
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    agents.forEach { (id, hostname) ->
                        FilterChip(
                            selected = selectedAgent == id,
                            onClick = { vm.selectAgent(id) },
                            label = { Text(hostname.ifEmpty { id }) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            Box {
                when (tab) {
                    Tab.Dashboard -> DashboardScreen(metrics)
                    Tab.Containers -> ContainersScreen(containers, onAction = vm::action)
                    Tab.Logs -> {
                        // стримим логи первого запущенного контейнера выбранного сервера
                        LaunchedEffect(tab, selectedAgent) {
                            containers.firstOrNull { it.state == "RUNNING" }
                                ?.let { vm.startLogs(it.id) }
                        }
                        LogsScreen(logs)
                    }
                }
            }
        }
    }
}
