package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pulse.data.LogLineUi
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseWarn

@Composable
fun LogsScreen(lines: List<LogLineUi>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(lines, key = { "${it.tsUnix}-${it.text.hashCode()}" }) { line ->
            val color = when {
                line.text.contains("ERROR", ignoreCase = true) || line.isStderr -> PulseError
                line.text.contains("WARN", ignoreCase = true) -> PulseWarn
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(Modifier.fillMaxWidth()) {
                Text(
                    line.text,
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
