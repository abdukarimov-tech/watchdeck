package app.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pulse.ui.theme.PulseAccent
import app.pulse.ui.theme.PulseError
import app.pulse.ui.theme.PulseSuccess
import app.pulse.ui.theme.PulseWarn

/** Цвет нагрузки по доле 0..1: норма → предупреждение → критично. */
fun loadColor(fraction: Float): Color = when {
    fraction < 0.60f -> PulseSuccess
    fraction < 0.85f -> PulseWarn
    else -> PulseError
}

/**
 * Кольцевой индикатор: трек + дуга с закругленными концами,
 * в центре — контент (значение).
 */
@Composable
fun RingGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = loadColor(progress),
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    strokeWidth: Dp = 9.dp,
    content: @Composable () -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 2f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Небольшая статусная точка (running/exited/alert). */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .background(color, CircleShape),
    )
}

/** Точка + подпись в одну строку. */
@Composable
fun StatusLabel(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Мягкое световое пятно за заголовком онбординга. */
@Composable
fun BrandOrb(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            brush = Brush.radialGradient(
                colors = listOf(
                    PulseAccent.copy(alpha = 0.38f),
                    PulseAccent.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
            ),
            shape = CircleShape,
        ),
    )
}
