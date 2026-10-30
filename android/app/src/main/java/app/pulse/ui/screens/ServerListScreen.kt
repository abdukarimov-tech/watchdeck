package app.pulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onOpenIncidents: () -> Unit = {},
) {
    val servers by vm.servers.collectAsState()
    val states by vm.serverStates.collectAsState()
    val metrics by vm.serverMetrics.collectAsState()
    val agentsByServer by vm.serverAgents.collectAsState()
    val activeId by vm.activeServerId.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.fleet_title),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
                }
                IconButton(onClick = onOpenIncidents) {
                    Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.incidents_title),
                        tint = MaterialTheme.colorScheme.primary)
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fleet_servers),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Text(servers.size.toString(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = onAdd) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_server),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(servers, key = { it.id }) { server ->
                    ServerWidgetCard(
                        server = server,
                        isActive = server.id == activeId,
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
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Dns, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            server.label.ifEmpty { stringResource(R.string.demo_label) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
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
                            state == ConnState.DISCONNECTED -> stringResource(R.string.conn_status_offline)
                            state == ConnState.CONNECTED -> stringResource(R.string.servers_no_agents_caption)
                            else -> stringResource(R.string.servers_connecting_caption)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(serverStateColor(state), size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(when (state) {
                    ConnState.CONNECTED -> if (agentCount == 0) R.string.relay_online_status else R.string.conn_status_online
                    ConnState.DISCONNECTED -> R.string.conn_status_offline
                    else -> R.string.conn_status_connecting
                }), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetStat(
                    label = stringResource(R.string.widget_cpu),
                    value = metrics?.let { String.format(java.util.Locale.US, "%.0f%%", it.cpuPercent) },
                    fraction = metrics?.let { (it.cpuPercent / 100.0).toFloat() },
                    modifier = Modifier.weight(1f),
                )
                WidgetStat(
                    label = stringResource(R.string.widget_ram),
                    value = metrics?.let { "${(it.memUsedBytes * 100 / it.memTotalBytes.coerceAtLeast(1)).toInt()}%" },
                    fraction = metrics?.let { it.memUsedBytes.toFloat() / it.memTotalBytes.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.weight(1f),
                )
                WidgetStat(
                    label = stringResource(R.string.widget_disk),
                    value = metrics?.disks?.firstOrNull()?.let {
                        "${(it.usedBytes * 100 / it.totalBytes.coerceAtLeast(1)).toInt()}%"
                    },
                    fraction = metrics?.disks?.firstOrNull()?.let {
                        it.usedBytes.toFloat() / it.totalBytes.coerceAtLeast(1).toFloat()
                    },
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
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "—",
            style = MetricValueStyle.copy(fontSize = 22.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { (fraction ?: 0f).coerceIn(0f, 1f) },
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
