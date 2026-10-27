package app.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.R

@Composable
fun OnboardingScreen(onDone: (relayUrl: String, token: String, demo: Boolean) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorUrl = stringResource(R.string.onboarding_error_url)
    val errorToken = stringResource(R.string.onboarding_error_token)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(stringResource(R.string.onboarding_relay_url)) },
            placeholder = { Text(stringResource(R.string.onboarding_relay_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text(stringResource(R.string.onboarding_token)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = demo, onCheckedChange = { demo = it })
            Text(stringResource(R.string.onboarding_demo), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (demo) {
                    onDone("", "", true)
                    return@Button
                }
                when {
                    !url.startsWith("wss://") && !url.startsWith("ws://") -> error = errorUrl
                    token.isBlank() -> error = errorToken
                    else -> onDone(url.trim(), token.trim(), false)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_connect))
        }
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.onboarding_hint_title),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "curl -XPOST relay.example.com/api/tokens \\\n  -H 'X-Admin-Token: ...' \\\n  -d '{\"label\":\"phone\",\"role\":\"client\"}'",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_privacy),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
