package app.pulse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.crypto.ServersStore
import app.pulse.data.MetricsUi
import app.pulse.net.ConnState
import app.pulse.ui.components.StatusDot
import app.pulse.ui.components.loadColor
import app.pulse.ui.theme.MetricValueStyle
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn

fun serverStateColor(state: ConnState?) = when (state) {
    ConnState.CONNECTED -> PulseSuccess
    ConnState.CONNECTING -> PulseWarn
    ConnState.DISCONNECTED -> PulseError
    null -> PulseWarn
}

/**
 * Главный экран: список карточек серверов с живыми виджетами.
 * Тап по карточке — подробные данные; FAB «+» — добавление сервера.
 */
@Composable
fun ServerListScreen(
    vm: PulseViewModel,
    onOpenDetails: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val servers by vm.servers.collectAsState()
    val states by vm.serverStates.collectAsState()
    val metrics by vm.serverMetrics.collectAsState()
    val agentsByServer by vm.serverAgents.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_server))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        pluralStringResource(R.plurals.servers_count, servers.size, servers.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerWidgetCard(
                        server = server,
                        isActive = server.id == vm.activeServerId.collectAsState().value,
                        state = states[server.id],
                        metrics = metrics[server.id],
                        agentCount = agentsByServer[server.id]?.size ?: 0,
                        onClick = { onOpenDetails(server.id) },
                    )
                }
            }
        }
    }
}

/** Карточка-виджет сервера: статус, подпись, мини-показатели CPU/ОЗУ/Диск. */
@Composable
fun ServerWidgetCard(
    server: ServersStore.ServerConfig,
    isActive: Boolean,
    state: ConnState?,
    metrics: MetricsUi?,
    agentCount: Int,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(serverStateColor(state), size = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            server.label.ifEmpty { stringResource(R.string.demo_label) },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.servers_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        when {
                            server.isDemo -> stringResource(R.string.servers_demo_caption)
                            agentCount > 0 -> pluralStringResource(R.plurals.agents_count, agentCount, agentCount)
                            else -> stringResource(R.string.servers_connecting_caption)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetStat(
                    label = stringResource(R.string.widget_cpu),
                    value = metrics?.let { String.format(java.util.Locale.US, "%.0f%%", it.cpuPercent) },
                    fraction = metrics?.let { (it.cpuPercent / 100.0).toFloat() },
                    active = isActive,
                    modifier = Modifier.weight(1f),
                )
                WidgetStat(
                    label = stringResource(R.string.widget_ram),
                    value = metrics?.let { "${(it.memUsedBytes * 100 / it.memTotalBytes.coerceAtLeast(1)).toInt()}%" },
                    fraction = metrics?.let { it.memUsedBytes.toFloat() / it.memTotalBytes.toFloat() },
                    active = isActive,
                    modifier = Modifier.weight(1f),
                )
                WidgetStat(
                    label = stringResource(R.string.widget_disk),
                    value = metrics?.disks?.firstOrNull()?.let {
                        "${(it.usedBytes * 100 / it.totalBytes.coerceAtLeast(1)).toInt()}%"
                    },
                    fraction = metrics?.disks?.firstOrNull()?.let {
                        it.usedBytes.toFloat() / it.totalBytes.toFloat()
                    },
                    active = isActive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Мини-показатель виджета: подпись, значение, тонкая цветная полоска. */
@Composable
private fun WidgetStat(
    label: String,
    value: String?,
    fraction: Float?,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value ?: "—",
                style = MetricValueStyle.copy(fontSize = 15.sp),
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { fraction ?: 0f },
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
                color = fraction?.let { loadColor(it) } ?: MaterialTheme.colorScheme.outline,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
            )
        }
    }
}
