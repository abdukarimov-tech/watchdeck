package app.pulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pulse.R
import app.pulse.data.ContainerUi
import app.pulse.data.formatBytes
import androidx.compose.ui.text.style.TextOverflow
import app.pulse.ui.components.StatusDot
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn

private fun stateColor(state: String) = when (state) {
    "RUNNING" -> PulseSuccess
    "RESTARTING", "PAUSED", "CREATED" -> PulseWarn
    "EXITED", "DEAD" -> PulseError
    else -> PulseWarn
}

@Composable
fun ContainersScreen(
    containers: List<ContainerUi>,
    onAction: (containerId: String, kind: String) -> Unit,
    canAct: Boolean = true,
    onLogs: (String) -> Unit = {},
) {
    var search by remember { mutableStateOf("") }
    var unhealthyOnly by remember { mutableStateOf(false) }
    val visible = containers.filter { c ->
        (!unhealthyOnly || c.health == "unhealthy") &&
            listOf(c.name, c.image, c.composeProject, c.composeService).any { it.contains(search, true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it },
            label = { Text(stringResource(R.string.containers_search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        FilterChip(selected = unhealthyOnly, onClick = { unhealthyOnly = !unhealthyOnly },
            label = { Text(stringResource(R.string.health_unhealthy)) })
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            if (visible.isEmpty()) item { Text(stringResource(R.string.containers_empty)) }
            visible.groupBy { it.composeProject }.toSortedMap().forEach { (project, group) ->
                item(key = "project:$project") {
                    Text(if (project.isEmpty()) stringResource(R.string.compose_standalone) else project,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group, key = { it.id }) { c ->
                    Column {
                        ContainerCard(c = c, canAct = canAct, onAction = onAction)
                        TextButton(onClick = { onLogs(c.id) }) { Text(stringResource(R.string.incident_logs)) }
                    }
                }
            }
        }
    }
}

/** Карточка контейнера: статусная точка, образ, кнопки действий с подтверждением. */
@Composable
internal fun ContainerCard(
    c: ContainerUi,
    canAct: Boolean,
    onAction: (containerId: String, kind: String) -> Unit,
) {
    var confirmFor by remember { mutableStateOf<ContainerUi?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 12.dp).size(38.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Inventory2, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    c.image,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                if (c.state == "RUNNING" && c.memLimitBytes > 0) {
                    Text("${formatBytes(c.memUsedBytes)} / ${formatBytes(c.memLimitBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (c.composeService.isNotEmpty()) Text(c.composeService, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(when (c.health) {
                    "healthy" -> R.string.health_healthy
                    "unhealthy" -> R.string.health_unhealthy
                    "starting" -> R.string.health_starting
                    else -> R.string.health_none
                }), style = MaterialTheme.typography.labelSmall,
                    color = if (c.health == "unhealthy") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    c.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor(c.state),
                )
            }
            if (canAct) {
                if (c.state == "RUNNING") {
                    ActionButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cd_restart),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { confirmFor = c },
                    )
                } else {
                    ActionButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.cd_start),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = { onAction(c.id, "ACTION_START") },
                    )
                }
            }
        }
    }

    confirmFor?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text(stringResource(R.string.containers_restart_title, target.name)) },
            text = { Text(stringResource(R.string.containers_restart_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onAction(target.id, "ACTION_RESTART")
                    confirmFor = null
                }) { Text(stringResource(R.string.containers_restart_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFor = null }) { Text(stringResource(R.string.containers_cancel)) }
            },
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = tint),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}
