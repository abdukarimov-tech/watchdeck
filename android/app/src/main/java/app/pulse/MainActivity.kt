package app.pulse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.screens.RelayManageScreen
import app.pulse.ui.screens.ServerDetailsScreen
import app.pulse.ui.screens.IncidentCenter
import app.pulse.ui.screens.ServerListScreen
import app.pulse.ui.screens.SettingsScreen
import app.pulse.ui.theme.PulseTheme

class MainActivity : FragmentActivity() {

    private val unlocked = androidx.compose.runtime.mutableStateOf(false)

    private fun lockEnabled(): Boolean =
        getSharedPreferences("pulse_settings", Context.MODE_PRIVATE).getBoolean("app_lock", false)

    private fun tryUnlock() {
        if (Build.VERSION.SDK_INT < 28) {
            unlocked.value = true
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked.value = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }

    override fun onResume() {
        super.onResume()
        if (lockEnabled() && !unlocked.value) tryUnlock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            PulseTheme {
                val app = application
                val vm: PulseViewModel = viewModel(factory = PulseViewModel.Factory(app))
                val servers by vm.servers.collectAsState()

                var showOnboarding by remember { mutableStateOf(false) }
                var showIncidents by remember { mutableStateOf(false) }
                var returnToIncidents by remember { mutableStateOf(false) }
                var logAgent by remember { mutableStateOf<String?>(null) }
                var logContainer by remember { mutableStateOf<String?>(null) }
                var showSettings by remember { mutableStateOf(false) }
                var showRelayManage by remember { mutableStateOf(false) }
                var detailsServerId by remember { mutableStateOf(intent.getStringExtra("server_id")?.takeIf { requested -> servers.any { it.id == requested } }) }

                val lockEnabled = remember {
                    getSharedPreferences("pulse_settings", Context.MODE_PRIVATE).getBoolean("app_lock", false)
                }
                val locked = lockEnabled && !unlocked.value

                when {
                    locked -> LockScreen(onUnlock = { tryUnlock() })

                    showIncidents -> IncidentCenter(servers, onBack = { showIncidents = false }, onLogs = { server, agent, container ->
                        vm.selectServer(server)
                        logAgent = agent; logContainer = container; detailsServerId = server; showIncidents = false; returnToIncidents = true
                    })
                    showRelayManage -> RelayManageScreen(vm = vm, onBack = { showRelayManage = false })

                    showSettings -> SettingsScreen(
                        onBack = { showSettings = false },
                        onResetData = {
                            showSettings = false
                            vm.resetAll()
                        },
                        appLockEnabled = lockEnabled,
                        onAppLockChange = { enabled ->
                            getSharedPreferences("pulse_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("app_lock", enabled).apply()
                            if (enabled) tryUnlock()
                        },
                        relayManageAvailable = servers.any { !it.isDemo && it.adminToken.isNotEmpty() },
                        onOpenRelayManage = { showRelayManage = true },
                    )

                    detailsServerId != null -> ServerDetailsScreen(
                        vm = vm,
                        serverId = detailsServerId!!,
                        initialAgentId = logAgent, initialLogContainerId = logContainer,
                        onBack = { detailsServerId = null; logAgent = null; logContainer = null; showIncidents = returnToIncidents; returnToIncidents = false },
                    )

                    // форма добавления (с FAB): после установки закрывается сама
                    showOnboarding -> OnboardingScreen(
                        vm = vm,
                        onFinished = { showOnboarding = false },
                        onClose = { showOnboarding = false },
                    )

                    // список серверов; без серверов — онбординг с SSH-установкой
                    servers.isEmpty() -> OnboardingScreen(vm = vm, onFinished = {})

                    else -> ServerListScreen(
                        vm = vm,
                        onOpenDetails = { detailsServerId = it; returnToIncidents = false },
                        onAdd = { showOnboarding = true },
                        onOpenSettings = { showSettings = true },
                        onOpenIncidents = { showIncidents = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUnlock) {
                Text(stringResource(R.string.lock_retry))
            }
        }
    }
}
