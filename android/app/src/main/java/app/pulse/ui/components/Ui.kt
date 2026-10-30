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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import app.pulse.R
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
    fraction < 0.60f -> PulseAccent
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

/** Последние реальные значения за время нахождения на экране, шкала 0–100%. */
@Composable
fun MetricSparkline(value: Float, modifier: Modifier = Modifier) {
    var samples by remember { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(value) {
        if (value.isFinite()) samples = (samples + value.coerceIn(0f, 1f)).takeLast(30)
    }
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.metric_session)
    Canvas(modifier.semantics { contentDescription = description }) {
        val inset = 3.dp.toPx()
        val h = (size.height - inset * 2).coerceAtLeast(0f)
        drawLine(color.copy(alpha = 0.1f), Offset(0f, size.height - inset),
            Offset(size.width, size.height - inset), 1.dp.toPx())
        if (samples.size < 2) return@Canvas
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = index.toFloat() / (samples.size - 1) * size.width
            val y = inset + (1f - sample) * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), Offset(size.width, inset + (1f - samples.last()) * h))
    }
}
