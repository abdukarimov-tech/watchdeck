package app.pulse.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.PulseViewModel
import app.pulse.net.SshInstaller
import app.pulse.net.SshTerminal
import app.pulse.R
import app.pulse.data.MetricsUi
import kotlinx.coroutines.launch
import app.pulse.data.formatBytes
import app.pulse.data.formatUptime
import app.pulse.net.ConnState
import app.pulse.ui.components.StatusDot
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn
import java.util.Locale
import kotlinx.coroutines.flow.flowOf

private enum class DetailsTab(val titleRes: Int) {
    Metrics(R.string.tab_dashboard),
    Containers(R.string.tab_containers),
    Logs(R.string.tab_logs),
    Terminal(R.string.details_tab_terminal),
    History(R.string.history_title),
    Rules(R.string.rules_title),
}

/**
 * Подробные данные сервера: вкладки Метрики / Контейнеры / Логи,
 * выбор агента и управление (сделать активным / удалить).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailsScreen(
    vm: PulseViewModel,
    serverId: String,
    onBack: () -> Unit,
    initialAgentId: String? = null,
    initialLogContainerId: String? = null,
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
        repo?.connectionState ?: flowOf(ConnState.DISCONNECTED)
    }.collectAsState(initial = ConnState.DISCONNECTED)
    val agents by remember(repo) {
        repo?.agents ?: flowOf(emptyMap<String, String>())
    }.collectAsState(initial = emptyMap())
    val metricsByAgent by remember(repo) {
        repo?.metricsByAgent ?: flowOf(emptyMap<String, MetricsUi>())
    }.collectAsState(initial = emptyMap())
    val containersByAgent by remember(repo) {
        repo?.containersByAgent ?: flowOf(emptyMap<String, List<app.pulse.data.ContainerUi>>())
    }.collectAsState(initial = emptyMap())
    val versions by remember(repo) {
        repo?.versions ?: flowOf(emptyMap<String, String>())
    }.collectAsState(initial = emptyMap())

    // выбор агента: у активного сервера — общий selectedAgent (синхронно с данными),
    // у неактивного — локальный, только для просмотра
    val vmSelected by vm.selectedAgent.collectAsState()
    var localAgent by remember(serverId) { mutableStateOf<String?>(null) }
    val selectedAgent = if (isActive) vmSelected else localAgent ?: agents.keys.firstOrNull()
    val agentMetrics = selectedAgent?.let { metricsByAgent[it] }
    val agentContainers = selectedAgent?.let { containersByAgent[it] } ?: emptyList()

    var tab by remember(serverId, initialLogContainerId) { mutableStateOf(if (initialLogContainerId != null) DetailsTab.Logs else DetailsTab.Metrics) }
    var logContainerId by remember(serverId, selectedAgent) { mutableStateOf(initialLogContainerId?.takeIf { initialAgentId == selectedAgent }) }
    LaunchedEffect(initialAgentId) {
        if (initialAgentId != null) {
            if (isActive) vm.selectAgent(initialAgentId) else localAgent = initialAgentId
        }
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var updatingAgent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler { onBack() }

    // логи: стрим первого запущенного контейнера выбранного агента, per-repo
    if (tab == DetailsTab.Logs) {
        androidx.compose.runtime.DisposableEffect(repo) { onDispose { repo?.stopLogs() } }
        LaunchedEffect(repo, selectedAgent, logContainerId, agentContainers.map { it.id }) {
            val running = agentContainers.firstOrNull { it.id == logContainerId } ?: agentContainers.firstOrNull { it.state == "RUNNING" } ?: agentContainers.firstOrNull()
            if (repo != null && selectedAgent != null && running != null) {
                repo.requestLogs(selectedAgent, logContainerId ?: running.id)
            }
        }
    }
    val logLines by remember(repo) {
        repo?.logLines ?: flowOf(emptyList<app.pulse.data.LogLineUi>())
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.label.ifEmpty { stringResource(R.string.demo_label) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (dotColor, statusText) = when (state) {
                                ConnState.CONNECTED -> if (agents.isEmpty()) PulseWarn to stringResource(R.string.relay_online_status) else PulseSuccess to stringResource(R.string.conn_status_online)
                                ConnState.CONNECTING -> PulseWarn to stringResource(R.string.conn_status_connecting)
                                ConnState.DISCONNECTED -> PulseError to stringResource(R.string.conn_status_offline)
                            }
                            StatusDot(dotColor, size = 7.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val version = selectedAgent?.let { versions[it] }.orEmpty()
                            if (version.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.agent_version_fmt, version),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.details_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details_update_agent)) },
                            enabled = !updatingAgent,
                            onClick = {
                                menuOpen = false
                                if (server.isDemo || server.sshAuthMode.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.details_no_ssh),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    updatingAgent = true
                                    scope.launch {
                                        vm.updateAgentRemote(server.id) { err ->
                                            updatingAgent = false
                                            Toast.makeText(
                                                context,
                                                if (err == null) context.getString(R.string.details_update_ok)
                                                else context.getString(R.string.details_update_fail, err),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            },
                        )
                        if (!isActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.details_make_active)) },
                                onClick = {
                                    menuOpen = false
                                    vm.selectServer(server.id)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details_reissue_tls)) },
                            enabled = !updatingAgent,
                            onClick = {
                                menuOpen = false
                                if (server.isDemo || server.sshAuthMode.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.details_no_ssh),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    updatingAgent = true
                                    scope.launch {
                                        vm.reissueCertsRemote(server.id) { err ->
                                            updatingAgent = false
                                            Toast.makeText(
                                                context,
                                                if (err == null) context.getString(R.string.details_reissue_ok)
                                                else context.getString(R.string.details_update_fail, err),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details_remove), color = PulseError) },
                            onClick = {
                                menuOpen = false
                                confirmRemove = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (server.relayUrl.isNotEmpty()) {
                Text(
                    server.relayUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (agents.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
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

            ScrollableTabRow(
                selectedTabIndex = tab.ordinal,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                indicator = {},
                divider = {},
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                DetailsTab.entries.forEach { t ->
                    Tab(
                        modifier = Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (tab == t) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer),
                        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(stringResource(t.titleRes), maxLines = 1) },
                    )
                }
            }

            when (tab) {
                DetailsTab.Metrics -> key(serverId, selectedAgent) { MetricsContent(metrics = agentMetrics, noAgents = state == ConnState.CONNECTED && agents.isEmpty()) }
                DetailsTab.Containers -> ContainersScreen(
                    containers = agentContainers,
                    onAction = vm::action,
                    canAct = isActive,
                    onLogs = { logContainerId = it; tab = DetailsTab.Logs },
                )
                DetailsTab.Logs -> LogsScreen(logLines, agentContainers,
                    logContainerId ?: agentContainers.firstOrNull { it.state == "RUNNING" }?.id ?: agentContainers.firstOrNull()?.id,
                    onSelect = { logContainerId = it })
                DetailsTab.Terminal -> TerminalTab(server = server)
                DetailsTab.History -> HistoryScreen(server, selectedAgent)
                DetailsTab.Rules -> AlertRulesScreen(server, selectedAgent)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
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

@Composable
private fun MetricsContent(metrics: MetricsUi?, noAgents: Boolean = false) {
    if (metrics == null) {
        Text(
            stringResource(if (noAgents) R.string.servers_no_agents_caption else R.string.servers_connecting_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        )
        return
    }
    val cpuFraction = (metrics.cpuPercent / 100.0).toFloat()
    val memFraction = metrics.memUsedBytes.toFloat() / metrics.memTotalBytes.coerceAtLeast(1).toFloat()

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

private val QUICK_COMMANDS = listOf("docker ps", "docker stats --no-stream", "df -h", "free -m", "uptime", "ps -eo pid,comm,%cpu,%mem --sort=-%cpu | head -15", "systemctl --failed --no-pager")

@Composable
private fun TerminalTab(server: app.pulse.crypto.ServersStore.ServerConfig) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (server.isDemo || server.sshAuthMode.isEmpty()) {
        Text(
            stringResource(R.string.terminal_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    var output by remember(server.id) { mutableStateOf("") }
    var input by remember(server.id) { mutableStateOf("") }
    val commandPrefs = remember { context.getSharedPreferences("watchdeck_diagnostics", android.content.Context.MODE_PRIVATE) }
    var savedCommands by remember { mutableStateOf(runCatching {
        val a = org.json.JSONArray(commandPrefs.getString("commands", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())) }
    var pendingCommand by remember { mutableStateOf<String?>(null) }
    fun saveCommands(next: List<String>) {
        savedCommands = next.distinct().take(30)
        commandPrefs.edit().putString("commands", org.json.JSONArray(savedCommands).toString()).apply()
    }
    var connectError by remember(server.id) { mutableStateOf<String?>(null) }
    val terminal = remember(server.id) {
        SshTerminal(
            expectedFp = server.sshHostKeyFp,
            onLog = { msg -> output += msg + "\n" },
        ) { chunk ->
            // убираем ANSI-управляющие последовательности
            output += ansiRegex.replace(chunk, "")
            if (output.length > MAX_TERMINAL_CHARS) {
                output = output.takeLast(MAX_TERMINAL_CHARS)
            }
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(server.id) {
        val cfg = SshInstaller.SshConfig(
            host = server.label.ifEmpty { server.relayUrl },
            port = server.sshPort,
            username = server.sshUser,
            auth = when (server.sshAuthMode) {
                "key" -> SshInstaller.SshConfig.Auth.Key(server.sshSecret, server.sshKeyPass.ifBlank { null })
                else -> SshInstaller.SshConfig.Auth.Password(server.sshSecret)
            },
        )
        runCatching { terminal.connect(cfg) }
            .onFailure { connectError = it.message ?: "ssh error" }
    }
    androidx.compose.runtime.DisposableEffect(server.id) {
        onDispose { terminal.close() }
    }

    pendingCommand?.let { cmd ->
        AlertDialog(onDismissRequest = { pendingCommand = null },
            title = { Text(stringResource(R.string.diagnostic_confirm)) },
            text = { Text("${server.sshUser}@${server.label}:${server.sshPort}\n\n$cmd", fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { terminal.send(cmd); input = ""; pendingCommand = null }) { Text(stringResource(R.string.diagnostic_run)) } },
            dismissButton = { TextButton(onClick = { pendingCommand = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = Color(0xFF0D1117),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (connectError != null) {
                Text(
                    stringResource(R.string.terminal_connect_error, connectError.orEmpty()),
                    color = app.pulse.ui.theme.PulseError,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                val blocks = output.split('\n')
                LaunchedEffect(output) {
                    if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    items(blocks.size) { i ->
                        Text(
                            blocks[i],
                            color = Color(0xFFC9D4E5),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (QUICK_COMMANDS + savedCommands).distinct().forEach { cmd ->
                FilterChip(
                    selected = false,
                    onClick = { pendingCommand = cmd },
                    label = { Text(cmd, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        if (input.isNotBlank() && input.length <= 1024 && input !in QUICK_COMMANDS && input !in savedCommands) {
            TextButton(onClick = { saveCommands(savedCommands + input) }) { Text(stringResource(R.string.diagnostic_save)) }
        }
        if (savedCommands.isNotEmpty()) {
            TextButton(onClick = { saveCommands(emptyList()) }) { Text(stringResource(R.string.diagnostic_clear)) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.terminal_placeholder)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        pendingCommand = input
                    }
                },
                enabled = connectError == null,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.terminal_send),
                )
            }
        }
    }
}

private val ansiRegex = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
private const val MAX_TERMINAL_CHARS = 20_000
