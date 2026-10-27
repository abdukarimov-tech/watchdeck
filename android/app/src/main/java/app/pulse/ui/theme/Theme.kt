package app.pulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Background = Color(0xFF0F1115)
private val Surface = Color(0xFF171A21)
private val Primary = Color(0xFF4FC3F7)
private val Error = Color(0xFFEF5350)
private val Warn = Color(0xFFFFB74D)
private val Ok = Color(0xFF66BB6A)

val PulseError = Error
val PulseWarn = Warn
val PulseOk = Ok

private val DarkScheme = darkColorScheme(
    primary = Primary,
    background = Background,
    surface = Surface,
    error = Error,
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    // приложение мониторинга — по умолчанию темная тема
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else DarkScheme,
        content = content,
    )
}
