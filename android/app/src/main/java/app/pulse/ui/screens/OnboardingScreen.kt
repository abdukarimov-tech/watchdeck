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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onDone: (relayUrl: String, token: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pulse", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Подключитесь к вашему релею. Токен хранится только на устройстве " +
                "в Android Keystore (StrongBox при наличии).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("Адрес релея") },
            placeholder = { Text("wss://relay.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text("Токен клиента") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Row {
            Button(
                onClick = {
                    when {
                        !url.startsWith("wss://") && !url.startsWith("ws://") ->
                            error = "Адрес должен начинаться с wss://"
                        token.isBlank() -> error = "Введите токен"
                        else -> onDone(url.trim(), token.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Подключиться")
            }
        }
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Токен выпускается на сервере релея:", style = MaterialTheme.typography.bodySmall)
                Text(
                    "curl -XPOST relay.example.com/api/tokens \\\n  -H 'X-Admin-Token: ...' \\\n  -d '{\"label\":\"phone\",\"role\":\"client\"}'",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
