package app.pulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ---------- брендовые семантические цвета статусов ----------
val PulseSuccess = Color(0xFF48BD96)
val PulseWarn = Color(0xFFE8B45C)
val PulseError = Color(0xFFEF7C82)
val PulseAccent = Color(0xFF6B9EFF)

// ---------- темная схема (основная для инструмента мониторинга) ----------
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6B9EFF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF253651),
    onPrimaryContainer = Color(0xFFB5E5FF),
    secondary = Color(0xFF9AA4B5),
    onSecondary = Color(0xFF202633),
    secondaryContainer = Color(0xFF253651),
    onSecondaryContainer = Color(0xFFDCE7FF),
    tertiary = Color(0xFF48BD96),
    onTertiary = Color(0xFF003917),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF15181E),
    onSurface = Color(0xFFE6EAF2),
    surfaceContainer = Color(0xFF1A1E25),
    surfaceContainerHigh = Color(0xFF222730),
    surfaceContainerHighest = Color(0xFF2B323D),
    surfaceVariant = Color(0xFF222730),
    onSurfaceVariant = Color(0xFF9AA4B5),
    outline = Color(0xFF2A3140),
    outlineVariant = Color(0xFF2B323D),
    error = Color(0xFFEF7C82),
    errorContainer = Color(0xFF3A1517),
    onError = Color(0xFF2B0808),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E5CBD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF546581),
    onSecondary = Color.White,
    tertiary = Color(0xFF1B7B44),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181C22),
    surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerHigh = Color(0xFFE9EDF3),
    surfaceContainerHighest = Color(0xFFE2E7EE),
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF424A57),
    outline = Color(0xFFC4CCD8),
    outlineVariant = Color(0xFFE2E7EE),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFFE3E0),
)

// ---------- формы: компактные панели ----------
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Моноширинные цифры для метрик — значения не «прыгают» при обновлении. */
val MetricValueStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content,
    )
}
