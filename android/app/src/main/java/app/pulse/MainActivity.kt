package app.pulse

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.screens.ServerDetailsScreen
import app.pulse.ui.screens.ServerListScreen
import app.pulse.ui.screens.SettingsScreen
import app.pulse.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {

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
                var showSettings by remember { mutableStateOf(false) }
                var detailsServerId by remember { mutableStateOf<String?>(null) }

                when {
                    showSettings -> SettingsScreen(
                        onBack = { showSettings = false },
                        onResetData = {
                            showSettings = false
                            vm.resetAll()
                        },
                    )

                    detailsServerId != null -> ServerDetailsScreen(
                        vm = vm,
                        serverId = detailsServerId!!,
                        onBack = { detailsServerId = null },
                    )

                    // форма добавления (с FAB): после установки закрывается сама
                    showOnboarding -> OnboardingScreen(
                        vm = vm,
                        onFinished = { showOnboarding = false },
                    )

                    // список серверов; без серверов — онбординг с SSH-установкой
                    servers.isEmpty() -> OnboardingScreen(vm = vm, onFinished = {})

                    else -> ServerListScreen(
                        vm = vm,
                        onOpenDetails = { detailsServerId = it },
                        onAdd = { showOnboarding = true },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
