package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.R
import app.pulse.data.MetricsUi
import app.pulse.data.formatBytes
import app.pulse.data.formatUptime

@Composable
fun DashboardScreen(metrics: MetricsUi?) {
    if (metrics == null) {
        Text(stringResource(R.string.dashboard_loading), modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dashboard_cpu), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            String.format(java.util.Locale.US, "%.0f%%", metrics.cpuPercent),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· " + pluralStringResource(R.plurals.cpu_cores, metrics.cpuCores, metrics.cpuCores),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (metrics.cpuPercent / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dashboard_memory), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatBytes(metrics.memUsedBytes)} / ${formatBytes(metrics.memTotalBytes)}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    LinearProgressIndicator(
                        progress = {
                            metrics.memUsedBytes.toFloat() / metrics.memTotalBytes.toFloat()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.dashboard_network_uptime, formatUptime(metrics.uptimeSeconds)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↓ ${formatBytes(metrics.rxBytesPerSec)}/s   ↑ ${formatBytes(metrics.txBytesPerSec)}/s",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        items(metrics.disks) { disk ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(disk.mount, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${formatBytes(disk.usedBytes)} / ${formatBytes(disk.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { disk.usedBytes.toFloat() / disk.totalBytes.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
