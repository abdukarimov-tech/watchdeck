package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.PulseViewModel
import app.pulse.R
import app.pulse.net.SshInstaller
import app.pulse.ui.components.BrandOrb
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn

private enum class OnbStep { FORM, INSTALL }
private enum class AuthMode { PASSWORD, KEY }

@Composable
fun OnboardingScreen(
    vm: PulseViewModel,
    onFinished: () -> Unit = {},
    heading: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val installState by vm.installState.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()

    var step by remember { mutableStateOf(OnbStep.FORM) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var authMode by remember { mutableStateOf(AuthMode.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val demoLabel = stringResource(R.string.demo_label)
    val errHost = stringResource(R.string.onboarding_error_url)
    val errUser = stringResource(R.string.ssh_user)
    val errToken = stringResource(R.string.onboarding_error_token)

    val running = installState != null && installState?.done == false
    LaunchedEffect(installState) {
        if (installState != null) step = OnbStep.INSTALL
    }

    fun submit() {
        val portNum = port.toIntOrNull() ?: 22
        val auth = if (authMode == AuthMode.KEY) {
            SshInstaller.SshConfig.Auth.Key(privateKey, keyPassphrase.ifBlank { null })
        } else {
            SshInstaller.SshConfig.Auth.Password(password)
        }
        when {
            demo -> {
                vm.addDemo(demoLabel)
                onFinished()
            }
            host.isBlank() -> error = errHost
            username.isBlank() -> error = errUser
            authMode == AuthMode.PASSWORD && password.isBlank() -> error = errToken
            authMode == AuthMode.KEY && privateKey.isBlank() -> error = errToken
            else -> {
                error = null
                vm.installAndConnect(host, portNum, username, auth)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_cancel))
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            if (heading != null) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                stringResource(
                    R.string.onb_step_fmt,
                    if (step == OnbStep.FORM) 1 else 2,
                    2,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (step) {
            OnbStep.FORM -> FormStep(
                host = host, onHost = { host = it.trim(); error = null },
                port = port, onPort = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                username = username, onUsername = { username = it.trim() },
                authMode = authMode, onAuthMode = { authMode = it; error = null },
                password = password, onPassword = { password = it },
                privateKey = privateKey, onPrivateKey = { privateKey = it; error = null },
                keyPassphrase = keyPassphrase, onKeyPassphrase = { keyPassphrase = it },
                error = error,
                onSubmit = { submit() },
                onDemo = {
                    demo = true
                    submit()
                },
            )

            OnbStep.INSTALL -> InstallStep(
                state = installState,
                host = host,
                onRetry = { submit() },
                onBack = {
                    vm.resetInstall()
                    step = OnbStep.FORM
                },
            )
        }
    }

    hostKeyPrompt?.let { prompt ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.confirmHostKey(false) },
            title = { Text(stringResource(R.string.ssh_host_key_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ssh_host_key_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        prompt.fingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmHostKey(true) }) {
                    Text(stringResource(R.string.ssh_host_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.confirmHostKey(false) }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FormStep(
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    authMode: AuthMode, onAuthMode: (AuthMode) -> Unit,
    password: String, onPassword: (String) -> Unit,
    privateKey: String, onPrivateKey: (String) -> Unit,
    keyPassphrase: String, onKeyPassphrase: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    onDemo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center) {
            BrandOrb(Modifier.size(150.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                SectionTitle(Icons.Filled.Dns, stringResource(R.string.ssh_section))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHost,
                        label = { Text(stringResource(R.string.ssh_host)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPort,
                        label = { Text(stringResource(R.string.ssh_port)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.width(90.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsername,
                    label = { Text(stringResource(R.string.ssh_user)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authMode == AuthMode.PASSWORD,
                        onClick = { onAuthMode(AuthMode.PASSWORD) },
                        label = { Text(stringResource(R.string.auth_password)) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = authMode == AuthMode.KEY,
                        onClick = { onAuthMode(AuthMode.KEY) },
                        label = { Text(stringResource(R.string.auth_key)) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                when (authMode) {
                    AuthMode.PASSWORD -> OutlinedTextField(
                        value = password,
                        onValueChange = onPassword,
                        label = { Text(stringResource(R.string.ssh_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AuthMode.KEY -> {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = onPrivateKey,
                            label = { Text(stringResource(R.string.ssh_key)) },
                            shape = MaterialTheme.shapes.medium,
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = keyPassphrase,
                            onValueChange = onKeyPassphrase,
                            label = { Text(stringResource(R.string.ssh_key_passphrase)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }
            }
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

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.install_button), fontSize = 16.sp)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDemo,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.or_try_demo))
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun InstallStep(
    state: PulseViewModel.InstallState?,
    host: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val done = state?.done == true
    val error = state?.error
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, label) = when {
                !done -> PulseWarn to stringResource(R.string.install_running)
                error == null -> PulseSuccess to stringResource(R.string.install_done_title)
                else -> PulseError to stringResource(R.string.install_error_title)
            }
            Box(
                Modifier
                    .size(12.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            host,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val lines = state?.lines ?: emptyList()
                items(lines.size) { i ->
                    val line = lines[i]
                    val isErr = line.startsWith("[error]")
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            isErr -> PulseError
                            line.startsWith("[done]") -> PulseSuccess
                            else -> Color(0xFFC9D4E5)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        when {
            !done -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f), shape = CircleShape) {
                    Text(stringResource(R.string.install_button))
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f), shape = CircleShape) {
                    Text(stringResource(R.string.onb_back))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
