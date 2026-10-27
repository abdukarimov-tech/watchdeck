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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

                var addingServer by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var detailsServerId by remember { mutableStateOf<String?>(null) }
                val context = LocalContext.current

                when {
                    // добавление сервера (из онбординга или с FAB)
                    addingServer -> OnboardingScreen { url, token, demo ->
                        addingServer = false
                        if (demo) {
                            vm.addDemo(context.getString(R.string.demo_label))
                        } else {
                            vm.addServer(url, token)
                        }
                    }

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

                    // список серверов; без серверов — онбординг
                    servers.isEmpty() -> OnboardingScreen { url, token, demo ->
                        if (demo) {
                            vm.addDemo(context.getString(R.string.demo_label))
                        } else {
                            vm.addServer(url, token)
                        }
                    }

                    else -> ServerListScreen(
                        vm = vm,
                        onOpenDetails = { detailsServerId = it },
                        onAdd = { addingServer = true },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
