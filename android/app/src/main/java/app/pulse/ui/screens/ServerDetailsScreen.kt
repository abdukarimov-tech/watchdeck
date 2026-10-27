package app.pulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.data.MetricsUi
import app.pulse.data.formatBytes
import app.pulse.data.formatUptime
import app.pulse.net.ConnState
import app.pulse.ui.components.StatusDot
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn
import java.util.Locale
import kotlinx.coroutines.flow.flowOf

private enum class DetailsTab(val titleRes: Int) {
    Metrics(R.string.tab_dashboard),
    Containers(R.string.tab_containers),
    Logs(R.string.tab_logs),
}

/**
 * Подробные данные сервера: вкладки Метрики / Контейнеры / Логи,
 * выбор агента и управление (сделать активным / удалить).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailsScreen(
    vm: PulseViewModel,
    serverId: String,
    onBack: () -> Unit,
) {
    val servers by vm.servers.collectAsState()
    val activeServerId by vm.activeServerId.collectAsState()
    val server = servers.firstOrNull { it.id == serverId } ?: run {
        onBack()
        return
    }
    val isActive = server.id == activeServerId
    val repo = vm.repoFor(serverId)

    val state by remember(repo) {
        repo?.connectionState ?: flowOf(ConnState.DISCONNECTED)
    }.collectAsState(initial = ConnState.DISCONNECTED)
    val agents by remember(repo) {
        repo?.agents ?: flowOf(emptyMap<String, String>())
    }.collectAsState(initial = emptyMap())
    val metricsByAgent by remember(repo) {
        repo?.metricsByAgent ?: flowOf(emptyMap<String, MetricsUi>())
    }.collectAsState(initial = emptyMap())
    val containersByAgent by remember(repo) {
        repo?.containersByAgent ?: flowOf(emptyMap<String, List<app.pulse.data.ContainerUi>>())
    }.collectAsState(initial = emptyMap())

    // выбор агента: у активного сервера — общий selectedAgent (синхронно с данными),
    // у неактивного — локальный, только для просмотра
    val vmSelected by vm.selectedAgent.collectAsState()
    var localAgent by remember(serverId) { mutableStateOf<String?>(null) }
    val selectedAgent = if (isActive) vmSelected else localAgent ?: agents.keys.firstOrNull()
    val agentMetrics = selectedAgent?.let { metricsByAgent[it] }
    val agentContainers = selectedAgent?.let { containersByAgent[it] } ?: emptyList()

    var tab by remember { mutableStateOf(DetailsTab.Metrics) }
    var confirmRemove by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // логи: стрим первого запущенного контейнера выбранного агента, per-repo
    if (tab == DetailsTab.Logs) {
        LaunchedEffect(repo, selectedAgent) {
            val running = agentContainers.firstOrNull { it.state == "RUNNING" }
            if (repo != null && selectedAgent != null && running != null) {
                repo.requestLogs(selectedAgent, running.id)
            }
        }
    }
    val logLines by remember(repo) {
        repo?.logLines ?: flowOf(emptyList<app.pulse.data.LogLineUi>())
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.label.ifEmpty { stringResource(R.string.demo_label) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (dotColor, statusText) = when (state) {
                                ConnState.CONNECTED -> PulseSuccess to stringResource(R.string.conn_status_online)
                                ConnState.CONNECTING -> PulseWarn to stringResource(R.string.conn_status_connecting)
                                ConnState.DISCONNECTED -> PulseError to stringResource(R.string.conn_status_offline)
                            }
                            StatusDot(dotColor, size = 7.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (server.relayUrl.isNotEmpty()) {
                Text(
                    server.relayUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (agents.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    agents.forEach { (id, host) ->
                        FilterChip(
                            selected = selectedAgent == id,
                            onClick = {
                                if (isActive) vm.selectAgent(id) else localAgent = id
                            },
                            label = { Text(host.ifEmpty { id }) },
                        )
                    }
                }
            }

            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DetailsTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(stringResource(t.titleRes)) },
                    )
                }
            }

            when (tab) {
                DetailsTab.Metrics -> MetricsContent(metrics = agentMetrics)
                DetailsTab.Containers -> ContainersScreen(
                    containers = agentContainers,
                    onAction = vm::action,
                    canAct = isActive,
                )
                DetailsTab.Logs -> LogsScreen(logLines)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.details_remove)) },
            text = { Text(stringResource(R.string.details_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeServer(server.id)
                    onBack()
                }) { Text(stringResource(R.string.details_remove_confirm), color = PulseError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun MetricsContent(metrics: MetricsUi?) {
    if (metrics == null) {
        Text(
            stringResource(R.string.servers_connecting_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        )
        return
    }
    val cpuFraction = (metrics.cpuPercent / 100.0).toFloat()
    val memFraction = metrics.memUsedBytes.toFloat() / metrics.memTotalBytes.toFloat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeCard(
                    title = stringResource(R.string.dashboard_cpu),
                    value = String.format(Locale.US, "%.0f%%", metrics.cpuPercent),
                    fraction = cpuFraction,
                    caption = pluralStringResource(R.plurals.cpu_cores, metrics.cpuCores, metrics.cpuCores),
                    modifier = Modifier.weight(1f),
                )
                GaugeCard(
                    title = stringResource(R.string.dashboard_memory),
                    value = "${(memFraction * 100).toInt()}%",
                    fraction = memFraction,
                    caption = "${formatBytes(metrics.memUsedBytes)} / ${formatBytes(metrics.memTotalBytes)}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Text(
                stringResource(R.string.details_uptime, formatUptime(metrics.uptimeSeconds)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { NetworkCard(metrics) }
        items(metrics.disks, key = { it.mount }) { DiskCard(it) }
    }
}
