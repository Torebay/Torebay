package app.pingboost.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

private const val POINTS = 60

/** График пинга за последнюю минуту. Красные чёрточки внизу означают потерянные пакеты. */
@Composable
fun PingChart(samples: List<Double?>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val lost = MaterialTheme.colorScheme.error

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.isEmpty()) return@Canvas

        val maxValue = max(100.0, (samples.filterNotNull().maxOrNull() ?: 0.0) * 1.2).toFloat()
        val step = w / (POINTS - 1)
        val startX = w - (samples.size - 1) * step // график «едет» справа налево
        val path = Path()
        var drawing = false
        samples.forEachIndexed { i, value ->
            val x = startX + i * step
            if (value == null) {
                drawLine(lost, Offset(x, h - 16f), Offset(x, h), strokeWidth = 3f)
                drawing = false
            } else {
                val y = h - (value.toFloat() / maxValue) * h
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
        }
        drawPath(path, line, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
