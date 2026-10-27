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
val PulseSuccess = Color(0xFF4ADE80)
val PulseWarn = Color(0xFFFBBF24)
val PulseError = Color(0xFFFF6B6B)
val PulseAccent = Color(0xFF5AC8FF)

// ---------- темная схема (основная для инструмента мониторинга) ----------
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5AC8FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF0E3A52),
    onPrimaryContainer = Color(0xFFB5E5FF),
    secondary = Color(0xFFFF8A3D),
    onSecondary = Color(0xFF3A1A00),
    secondaryContainer = Color(0xFF3A2413),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF003917),
    background = Color(0xFF07090D),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF0B0E13),
    onSurface = Color(0xFFE6EAF2),
    surfaceContainer = Color(0xFF141922),
    surfaceContainerHigh = Color(0xFF1A2029),
    surfaceContainerHighest = Color(0xFF202633),
    surfaceVariant = Color(0xFF1A2029),
    onSurfaceVariant = Color(0xFF9AA4B5),
    outline = Color(0xFF2A3140),
    outlineVariant = Color(0xFF202633),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF3A1517),
    onError = Color(0xFF2B0808),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00668C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E8FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFFB02F00),
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

// ---------- формы: крупные радиусы ----------
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
