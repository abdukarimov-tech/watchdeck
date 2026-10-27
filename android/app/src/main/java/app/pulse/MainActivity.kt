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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.crypto.ServerSecretStore
import app.pulse.data.MockPulseRepository
import app.pulse.net.ConnState
import app.pulse.net.WsPulseRepository
import app.pulse.ui.components.StatusDot
import app.pulse.ui.screens.ContainersScreen
import app.pulse.ui.screens.DashboardScreen
import app.pulse.ui.screens.LogsScreen
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn
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

private enum class Tab(val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Dashboard(R.string.tab_dashboard, Icons.Filled.Dns),
    Containers(R.string.tab_containers, Icons.Filled.SmartToy),
    Logs(R.string.tab_logs, Icons.Filled.Terminal),
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
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.titleRes)) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ServerHeader(
                connectionState = connectionState,
                agents = agents,
                selectedAgent = selectedAgent,
                onSelect = vm::selectAgent,
            )
            Box {
                when (tab) {
                    Tab.Dashboard -> DashboardScreen(metrics)
                    Tab.Containers -> ContainersScreen(containers, onAction = vm::action)
                    Tab.Logs -> {
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

@Composable
private fun ServerHeader(
    connectionState: ConnState,
    agents: Map<String, String>,
    selectedAgent: String?,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hostname = selectedAgent?.let { agents[it] }.orEmpty().ifEmpty { selectedAgent.orEmpty() }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                hostname.ifEmpty { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, label) = when (connectionState) {
                    ConnState.CONNECTED -> PulseSuccess to stringResource(R.string.conn_status_online)
                    ConnState.CONNECTING -> PulseWarn to stringResource(R.string.conn_status_connecting)
                    ConnState.DISCONNECTED -> PulseError to stringResource(R.string.conn_status_offline)
                }
                StatusDot(dotColor, size = 7.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (agents.size > 1) {
            Box {
                androidx.compose.material3.FilledTonalButton(onClick = { menuOpen = true }) {
                    Text(stringResource(R.string.header_switch_server))
                    Icon(Icons.Filled.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    agents.forEach { (id, host) ->
                        DropdownMenuItem(
                            text = { Text(host.ifEmpty { id }) },
                            onClick = {
                                menuOpen = false
                                onSelect(id)
                            },
                        )
                    }
                }
            }
        }
    }
}
