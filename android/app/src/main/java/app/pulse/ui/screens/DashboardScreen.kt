package app.pulse.ui.screens

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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.R
import app.pulse.data.DiskUi
import app.pulse.data.MetricsUi
import app.pulse.data.formatBytes
import app.pulse.data.formatUptime
import app.pulse.ui.components.RingGauge
import app.pulse.ui.components.loadColor
import app.pulse.ui.theme.MetricValueStyle
import app.pulse.ui.theme.PulseSuccess
import java.util.Locale

@Composable
fun DashboardScreen(metrics: MetricsUi?) {
    if (metrics == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.dashboard_loading), style = MaterialTheme.typography.bodyLarge)
        }
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
        item { NetworkCard(metrics) }
        items(metrics.disks, key = { it.mount }) { DiskCard(it) }
    }
}

@Composable
private fun GaugeCard(
    title: String,
    value: String,
    fraction: Float,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RingGauge(progress = fraction, modifier = Modifier.size(92.dp)) {
                Text(
                    value,
                    style = MetricValueStyle.copy(fontSize = 20.sp),
                    color = loadColor(fraction),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NetworkCard(metrics: MetricsUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.dashboard_network_uptime, formatUptime(metrics.uptimeSeconds)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RateRow(Icons.Filled.ArrowDownward, PulseSuccess, formatBytes(metrics.rxBytesPerSec))
                RateRow(Icons.Filled.ArrowUpward, MaterialTheme.colorScheme.primary, formatBytes(metrics.txBytesPerSec))
            }
        }
    }
}

@Composable
private fun RateRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, rate: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(rate + "/s", style = MetricValueStyle.copy(fontSize = 15.sp))
    }
}

@Composable
private fun DiskCard(disk: DiskUi) {
    val fraction = disk.usedBytes.toFloat() / disk.totalBytes.toFloat()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(disk.mount, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${formatBytes(disk.usedBytes)} / ${formatBytes(disk.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                color = loadColor(fraction),
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        }
    }
}
