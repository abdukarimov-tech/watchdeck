package app.pulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.pulse.data.formatBytes
import androidx.compose.ui.unit.dp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.data.formatUptime
import app.pulse.ui.components.StatusDot
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn

/**
 * Подробные данные сервера: статус, выбор агента, полные метрики,
 * контейнеры и управление (сделать активным / удалить).
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
        repo?.connectionState ?: kotlinx.coroutines.flow.flowOf(app.pulse.net.ConnState.DISCONNECTED)
    }.collectAsState(initial = app.pulse.net.ConnState.DISCONNECTED)
    val agents by remember(repo) {
        repo?.agents ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, String>())
    }.collectAsState(initial = emptyMap())
    val metricsByAgent by remember(repo) {
        repo?.metricsByAgent ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, app.pulse.data.MetricsUi>())
    }.collectAsState(initial = emptyMap())
    val containersByAgent by remember(repo) {
        repo?.containersByAgent ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, List<app.pulse.data.ContainerUi>>())
    }.collectAsState(initial = emptyMap())

    // выбор агента: у активного сервера — общий selectedAgent (синхронно с вкладками),
    // у неактивного — локальный, только для просмотра
    val vmSelected by vm.selectedAgent.collectAsState()
    var localAgent by remember(serverId) { mutableStateOf<String?>(null) }
    val selectedAgent = if (isActive) vmSelected else localAgent ?: agents.keys.firstOrNull()
    val agentMetrics = selectedAgent?.let { metricsByAgent[it] }
    val agentContainers = selectedAgent?.let { containersByAgent[it] } ?: emptyList()

    var confirmRemove by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.label.ifEmpty { stringResource(R.string.demo_label) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (dotColor, statusText) = when (state) {
                                app.pulse.net.ConnState.CONNECTED -> PulseSuccess to stringResource(R.string.conn_status_online)
                                app.pulse.net.ConnState.CONNECTING -> PulseWarn to stringResource(R.string.conn_status_connecting)
                                app.pulse.net.ConnState.DISCONNECTED -> PulseError to stringResource(R.string.conn_status_offline)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (server.relayUrl.isNotEmpty()) {
                item {
                    Text(
                        server.relayUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            // ---- агенты ----
            if (agents.size > 1) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            }

            // ---- метрики выбранного агента ----
            if (agentMetrics != null) {
                item {
                    Text(
                        stringResource(R.string.details_uptime, formatUptime(agentMetrics.uptimeSeconds)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GaugeCard(
                            title = stringResource(R.string.dashboard_cpu),
                            value = String.format(java.util.Locale.US, "%.0f%%", agentMetrics.cpuPercent),
                            fraction = (agentMetrics.cpuPercent / 100.0).toFloat(),
                            caption = pluralStringResource(R.plurals.cpu_cores, agentMetrics.cpuCores, agentMetrics.cpuCores),
                            modifier = Modifier.weight(1f),
                        )
                        val memFraction = agentMetrics.memUsedBytes.toFloat() / agentMetrics.memTotalBytes.toFloat()
                        GaugeCard(
                            title = stringResource(R.string.dashboard_memory),
                            value = "${(memFraction * 100).toInt()}%",
                            fraction = memFraction,
                            caption = "${formatBytes(agentMetrics.memUsedBytes)} / ${formatBytes(agentMetrics.memTotalBytes)}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { NetworkCard(agentMetrics) }
                items(agentMetrics.disks, key = { it.mount }) { DiskCard(it) }
            } else {
                item {
                    Text(
                        stringResource(R.string.servers_connecting_caption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                }
            }

            // ---- контейнеры ----
            if (agentContainers.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.tab_containers),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(agentContainers, key = { it.id }) { c ->
                    ContainerCard(c = c, canAct = isActive, onAction = vm::action)
                }
            }

            // ---- управление ----
            item {
                Column(Modifier.padding(16.dp)) {
                    if (!isActive) {
                        Button(
                            onClick = {
                                vm.selectServer(server.id)
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.details_make_active))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(
                        onClick = { confirmRemove = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.details_remove), color = PulseError)
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        androidx.compose.material3.AlertDialog(
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
