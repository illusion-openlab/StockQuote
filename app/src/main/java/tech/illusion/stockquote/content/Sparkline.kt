package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** Minimal trend line for a watchlist row: normalizes [hist] into the drawing bounds, no axes/labels. */
@Composable
fun Sparkline(hist: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (hist.size < 2) return@Canvas
        val min = hist.min()
        val max = hist.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (hist.size - 1)
        val points = hist.mapIndexed { index, value ->
            Offset(
                x = index * stepX,
                y = size.height - ((value - min) / range).toFloat() * size.height,
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = color,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }
    }
}
