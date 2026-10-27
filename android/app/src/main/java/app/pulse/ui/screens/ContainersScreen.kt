package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
) {
    var confirmFor by remember { mutableStateOf<ContainerUi?>(null) }

    LazyColumn(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(containers, key = { it.id }) { c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(stateColor(c.state), size = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            c.image,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            c.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = stateColor(c.state),
                        )
                    }
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
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}
