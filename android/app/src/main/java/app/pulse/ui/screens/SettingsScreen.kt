package app.pulse.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pulse.BuildConfig
import app.pulse.R
import app.pulse.ui.theme.PulseError

private const val SITE_URL = "https://watchdeck.app"
private const val SOURCE_URL = "https://github.com/watchdeck-app/watchdeck"
private const val SUPPORT_EMAIL = "support@watchdeck.app"

@Composable
private fun transparentListItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Корневой экран настроек: о приложении, документы, лицензии, данные. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onResetData: () -> Unit,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    relayManageAvailable: Boolean,
    onOpenRelayManage: () -> Unit,
) {
    var document by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(enabled = document != null || showLicenses) {
        document = null
        showLicenses = false
    }

    document?.let { name ->
        DocumentScreen(
            title = if (name == "privacy_policy") stringResource(R.string.document_title_privacy)
            else stringResource(R.string.document_title_terms),
            rawName = name,
            onBack = { document = null },
        )
        return
    }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionCard(title = stringResource(R.string.settings_about)) {
                ListItem(
                    colors = transparentListItem(),
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.onboarding_tagline)) },
                )
                ListItem(
                    colors = transparentListItem(),
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    trailingContent = {
                        Text(
                            BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                LinkItem(Icons.Filled.Code, stringResource(R.string.settings_source)) {
                    openUrl(context, SOURCE_URL)
                }
                LinkItem(Icons.Filled.Language, stringResource(R.string.settings_site)) {
                    openUrl(context, SITE_URL)
                }
                LinkItem(Icons.Filled.Email, stringResource(R.string.settings_contact)) {
                    openUrl(context, "mailto:$SUPPORT_EMAIL")
                }
            }

            SectionCard(title = stringResource(R.string.settings_docs)) {
                LinkItem(Icons.Filled.Description, stringResource(R.string.settings_privacy)) {
                    document = "privacy_policy"
                }
                LinkItem(Icons.Filled.Gavel, stringResource(R.string.settings_terms)) {
                    document = "terms_of_service"
                }
                LinkItem(Icons.Filled.Description, stringResource(R.string.settings_licenses)) {
                    showLicenses = true
                }
            }

            SectionCard(title = stringResource(R.string.settings_security)) {
                ListItem(
                    colors = transparentListItem(),
                    headlineContent = { Text(stringResource(R.string.settings_app_lock)) },
                    trailingContent = {
                        androidx.compose.material3.Switch(
                            checked = appLockEnabled,
                            onCheckedChange = onAppLockChange,
                        )
                    },
                    modifier = Modifier.clickable { onAppLockChange(!appLockEnabled) },
                )
                if (relayManageAvailable) {
                    LinkItem(Icons.Filled.Description, stringResource(R.string.settings_relay_manage)) {
                        onOpenRelayManage()
                    }
                }
            }

            SectionCard(title = stringResource(R.string.settings_data)) {
                ListItem(
                    colors = transparentListItem(),
                    headlineContent = { Text(stringResource(R.string.settings_reset), color = PulseError) },
                    trailingContent = {
                        IconButton(onClick = { confirmReset = true }) {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = null,
                                tint = PulseError,
                            )
                        }
                    },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetData()
                }) { Text(stringResource(R.string.settings_reset_confirm), color = PulseError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column { content() }
    }
}

@Composable
private fun LinkItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        colors = transparentListItem(),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** Просмотрщик юридического документа из res/raw (локаль подставляется системой). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(title: String, rawName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(rawName) {
        runCatching {
            val id = context.resources.getIdentifier(rawName, "raw", context.packageName)
            context.resources.openRawResource(id).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    val licenses = listOf(
        "Kotlin & Coroutines" to "Apache License 2.0",
        "Jetpack Compose / AndroidX" to "Apache License 2.0",
        "OkHttp" to "Apache License 2.0",
        "protobuf (kotlin-lite)" to "BSD 3-Clause",
        "Firebase Messaging" to "Apache License 2.0",
        "sysinfo (Rust)" to "MIT",
        "tokio / hyper / tungstenite (Rust)" to "MIT",
        "axum (Rust)" to "MIT",
        "rusqlite / SQLite" to "MIT / Public Domain",
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(licenses.size) { i ->
                val (name, license) = licenses[i]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
