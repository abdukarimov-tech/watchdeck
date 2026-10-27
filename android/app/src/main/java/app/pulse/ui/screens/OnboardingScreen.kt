package app.pulse.ui.screens

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.R
import app.pulse.ui.components.BrandOrb
import app.pulse.ui.theme.PulseSuccess

@Composable
fun OnboardingScreen(onDone: (relayUrl: String, token: String, demo: Boolean) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorUrl = stringResource(R.string.onboarding_error_url)
    val errorToken = stringResource(R.string.onboarding_error_token)

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            BrandOrb(Modifier.size(190.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(stringResource(R.string.onboarding_relay_url)) },
            placeholder = { Text(stringResource(R.string.onboarding_relay_url_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text(stringResource(R.string.onboarding_token)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(checked = demo, onCheckedChange = { demo = it })
            Text(stringResource(R.string.onboarding_demo), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
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
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.onboarding_connect), fontSize = 16.sp)
        }
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = PulseSuccess,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  " + stringResource(R.string.onboarding_hint_title),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "curl -XPOST relay.example.com/api/tokens \\\n  -H 'X-Admin-Token: ...' \\\n  -d '{\"label\":\"phone\",\"role\":\"client\"}'",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}
