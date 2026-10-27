package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.R
import app.pulse.data.LogLineUi
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseWarn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class LogFilter { ALL, ISSUES }

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun LogsScreen(lines: List<LogLineUi>) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val listState = rememberLazyListState()
    val visible = lines.filter {
        when (filter) {
            LogFilter.ALL -> true
            LogFilter.ISSUES -> it.isStderr || it.text.contains("ERROR", true) || it.text.contains("WARN", true)
        }
    }

    // автоскролл в хвост, пока пользователь не уехал вверх читать историю
    val atBottom = listState.let {
        it.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= it.layoutInfo.totalItemsCount - 1
    }
    LaunchedEffect(visible.size) {
        if (atBottom && visible.isNotEmpty()) {
            listState.scrollToItem(visible.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == LogFilter.ALL,
                onClick = { filter = LogFilter.ALL },
                label = { Text(stringResource(R.string.logs_filter_all)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            FilterChip(
                selected = filter == LogFilter.ISSUES,
                onClick = { filter = LogFilter.ISSUES },
                label = { Text(stringResource(R.string.logs_filter_issues)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
        }
        Surface(
            color = Color(0xFF0D1117),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { "${it.tsUnix}-${it.text.hashCode()}" }) { line ->
                    LogRow(line)
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLineUi) {
    val levelColor = when {
        line.text.contains("ERROR", true) || line.isStderr -> PulseError
        line.text.contains("WARN", true) -> PulseWarn
        else -> Color(0xFF8A93A6)
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            formatTime(line.tsUnix),
            color = Color(0xFF4B5563),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.width(64.dp),
        )
        Text(
            line.text,
            color = levelColor,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )
    }
}

private fun formatTime(tsUnix: Long): String = if (tsUnix > 0) {
    TIME_FORMAT.format(Instant.ofEpochSecond(tsUnix).atZone(ZoneId.systemDefault()))
} else {
    "--:--:--"
}
