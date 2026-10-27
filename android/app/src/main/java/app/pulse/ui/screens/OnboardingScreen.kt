package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.net.SshInstaller
import app.pulse.ui.components.BrandOrb
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess

private enum class AuthMode { PASSWORD, KEY }

/**
 * Экран добавления сервера: SSH-доступ (пароль или ключ), параметры релея.
 * Приложение само подключается по SSH, выпускает токены и устанавливает агента.
 */
@Composable
fun OnboardingScreen(vm: PulseViewModel, onFinished: () -> Unit = {}) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var authMode by remember { mutableStateOf(AuthMode.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }

    var relayUrl by remember { mutableStateOf("") }
    var adminToken by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val installState by vm.installState.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()
    val servers by vm.servers.collectAsState()
    val running = installState != null && installState?.done == false
    val demoLabel = stringResource(R.string.demo_label)
    val errUrl = stringResource(R.string.onboarding_error_url)
    val errUser = stringResource(R.string.ssh_user)

    // после успешной установки сервер добавлен — сообщаем наверху об окончании
    LaunchedEffect(installState?.done) {
        if (installState?.done == true && installState?.error == null) {
            error = null
            onFinished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Box(contentAlignment = Alignment.Center) {
            BrandOrb(Modifier.size(160.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = 34.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))

        // ---- сервер (SSH) ----
        FormCard(title = stringResource(R.string.ssh_section)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); error = null },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.ssh_port)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.width(90.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text(stringResource(R.string.ssh_user)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authMode == AuthMode.PASSWORD,
                    onClick = { authMode = AuthMode.PASSWORD },
                    label = { Text(stringResource(R.string.auth_password)) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                FilterChip(
                    selected = authMode == AuthMode.KEY,
                    onClick = { authMode = AuthMode.KEY },
                    label = { Text(stringResource(R.string.auth_key)) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            when (authMode) {
                AuthMode.PASSWORD -> OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_password)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
                AuthMode.KEY -> {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it; error = null },
                        label = { Text(stringResource(R.string.ssh_key)) },
                        shape = MaterialTheme.shapes.medium,
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text(stringResource(R.string.ssh_key_passphrase)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- релей ----
        FormCard(title = stringResource(R.string.relay_section)) {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it.trim(); error = null },
                label = { Text(stringResource(R.string.onboarding_relay_url)) },
                placeholder = { Text(stringResource(R.string.onboarding_relay_url_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = adminToken,
                onValueChange = { adminToken = it.trim(); error = null },
                label = { Text(stringResource(R.string.relay_admin_token)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.relay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val portNum = port.toIntOrNull() ?: 22
                val auth = when (authMode) {
                    AuthMode.PASSWORD -> SshInstaller.SshConfig.Auth.Password(password)
                    AuthMode.KEY -> SshInstaller.SshConfig.Auth.Key(privateKey, keyPassphrase.ifBlank { null })
                }
                val rel = relayUrl.trim()
                when {
                    demo -> {
                        vm.addDemo(demoLabel)
                        return@Button
                    }
                    host.isBlank() -> error = errUrl
                    rel.startsWith("wss://") || rel.startsWith("ws://") -> {
                        if (username.isBlank()) {
                            error = errUser
                        } else {
                            error = null
                            vm.installAndConnect(host, portNum, username, auth, rel, adminToken)
                        }
                    }
                    else -> error = errUrl
                }
            },
            enabled = !running,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                stringResource(if (running) R.string.install_running else R.string.install_button),
                fontSize = 16.sp,
            )
        }

        installState?.let { st ->
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0D1117)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    st.lines.takeLast(8).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (st.done && st.error != null) {
                        Text(
                            st.error,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = PulseError,
                        )
                    }
                    if (st.done && st.error == null) {
                        Text(
                            stringResource(R.string.conn_status_online),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = PulseSuccess,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = demo, onCheckedChange = { demo = it })
            Text(stringResource(R.string.onboarding_demo), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = PulseSuccess,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "  " + stringResource(R.string.onboarding_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FormCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
