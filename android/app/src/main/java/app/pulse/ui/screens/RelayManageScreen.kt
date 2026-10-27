package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.net.RelayAdmin
import app.pulse.ui.theme.PulseError
import kotlinx.coroutines.launch

/**
 * Управление релеем активного сервера: список токенов и отзыв.
 * Требуется сохраненный админ-токен релея.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManageScreen(vm: PulseViewModel, onBack: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val activeId by vm.activeServerId.collectAsState()
    val server = servers.firstOrNull { it.id == activeId } ?: run {
        onBack()
        return
    }
    val rows = remember { mutableStateListOf<RelayAdmin.TokenRow>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        val cfg = server ?: return
        if (cfg.isDemo || cfg.adminToken.isBlank()) {
            loading = false
            error = ""
            return
        }
        loading = true
        scope.launch {
            runCatching { RelayAdmin(cfg.relayUrl, cfg.adminToken, cfg.relayCertPem).listTokens() }
                .onSuccess {
                    rows.clear()
                    rows.addAll(it)
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(server?.id) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relay_tokens_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(32.dp).align(Alignment.CenterHorizontally),
                )
                error != null -> Text(
                    stringResource(R.string.terminal_connect_error, error.orEmpty()),
                    color = PulseError,
                    modifier = Modifier.padding(24.dp),
                )
                rows.isEmpty() -> Text(
                    stringResource(R.string.relay_empty),
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows) { t ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.label.ifEmpty { "token" }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        t.role + if (t.revoked) " · revoked" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (t.revoked) PulseError else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!t.revoked) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { RelayAdmin(server.relayUrl, server.adminToken, server.relayCertPem).revoke(t.id) }
                                            reload()
                                        }
                                    }) {
                                        Text(stringResource(R.string.relay_revoke), color = PulseError)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
