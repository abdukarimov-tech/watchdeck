package app.pulse

import android.Manifest
import android.app.Application
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.pulse.crypto.ServersStore
import app.pulse.net.ConnState
import app.pulse.ui.components.StatusDot
import app.pulse.ui.screens.ContainersScreen
import app.pulse.ui.screens.DashboardScreen
import app.pulse.ui.screens.LogsScreen
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.screens.ServerDetailsScreen
import app.pulse.ui.screens.ServersSheet
import app.pulse.ui.screens.SettingsScreen
import androidx.compose.material.icons.filled.Settings
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn
import app.pulse.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            PulseTheme {
                val app = application
                val vm: PulseViewModel = viewModel(factory = PulseViewModel.Factory(app))
                val servers by vm.servers.collectAsState()
                if (servers.isEmpty()) {
                    OnboardingScreen { url, token, demo ->
                        if (demo) {
                            vm.addDemo(getString(R.string.demo_label))
                        } else {
                            vm.addServer(url, token)
                        }
                    }
                } else {
                    MainScreen(vm)
                }
            }
        }
    }
}

private enum class Tab(val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Dashboard(R.string.tab_dashboard, Icons.Filled.Dns),
    Containers(R.string.tab_containers, Icons.Filled.SmartToy),
    Logs(R.string.tab_logs, Icons.Filled.Terminal),
}

@Composable
private fun MainScreen(vm: PulseViewModel) {
    val connectionState by vm.connectionState.collectAsState(initial = ConnState.DISCONNECTED)
    val servers by vm.servers.collectAsState()
    val activeServerId by vm.activeServerId.collectAsState()
    val agents by vm.agents.collectAsState(initial = emptyMap())
    val selectedAgent by vm.selectedAgent.collectAsState()
    val metrics by vm.metrics.collectAsState(initial = null)
    val containers by vm.containers.collectAsState(initial = emptyList())
    val logs by vm.logLines.collectAsState(initial = emptyList())
    val toasts = remember { vm.toasts }
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.Dashboard) }

    LaunchedEffect(Unit) {
        toasts.collect { snackbar.showSnackbar(it) }
    }
    var addingServer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showServersSheet by remember { mutableStateOf(false) }
    var detailsServerId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    detailsServerId?.let { id ->
        ServerDetailsScreen(vm = vm, serverId = id, onBack = { detailsServerId = null })
        return
    }

    if (showServersSheet) {
        ServersSheet(
            servers = servers,
            activeServerId = activeServerId,
            states = vm.serverStates.collectAsState().value,
            metrics = vm.serverMetrics.collectAsState().value,
            agentsCount = vm.serverAgents.collectAsState().value.mapValues { it.value.size },
            onDismiss = { showServersSheet = false },
            onSelect = vm::selectServer,
            onAdd = { addingServer = true },
            onRemove = vm::removeServer,
            onOpenDetails = { detailsServerId = it },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onResetData = {
                showSettings = false
                vm.resetAll()
            },
        )
        return
    }

    if (addingServer) {
        OnboardingScreen { url, token, demo ->
            addingServer = false
            if (demo) {
                vm.addDemo(context.getString(R.string.demo_label))
            } else {
                vm.addServer(url, token)
            }
        }
        return
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
            val serverStates by vm.serverStates.collectAsState()
            val serverMetrics by vm.serverMetrics.collectAsState()
            val serverAgents by vm.serverAgents.collectAsState()
            ServerHeader(
                connectionState = connectionState,
                serverLabel = selectedAgent?.let { agents[it] }.orEmpty()
                    .ifEmpty { servers.firstOrNull { it.id == activeServerId }?.label.orEmpty() },
                onOpenServers = { showServersSheet = true },
                onOpenSettings = { showSettings = true },
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
    serverLabel: String,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                serverLabel.ifEmpty { stringResource(R.string.app_name) },
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
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onOpenServers,
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(stringResource(R.string.header_switch_server))
            Icon(Icons.Filled.ExpandMore, contentDescription = null)
        }
    }
}

