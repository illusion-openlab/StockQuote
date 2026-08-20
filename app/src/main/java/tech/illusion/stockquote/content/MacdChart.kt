package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import tech.illusion.stockquote.data.MacdResult
import kotlin.math.abs

/**
 * Real MACD(12,26,9) histogram + diff/signal lines, hand-drawn on Canvas (ported from the prototype's
 * `drawMacd()`). Takes the already-computed result so the panel's title-bar values and the drawn lines
 * always come from one and the same computation.
 */
@Composable
fun MacdChart(
    macd: MacdResult,
    upColor: Color,
    downColor: Color,
    diffColor: Color,
    signalColor: Color,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
) {
    Canvas(modifier = modifier) {
        val count = macd.histogram.size
        if (count < 2) return@Canvas
        val axis = ChartAxis.of(size.width, ChartLabelSp.toPx(), count) ?: return@Canvas
        val padLeft = axis.padLeft
        val padRight = axis.padRight
        val mid = size.height / 2
        val slot = axis.slot
        val barWidth = (slot * 0.5f).coerceAtLeast(2f)
        val maxAbs = (macd.histogram + macd.diff + macd.signal).maxOfOrNull { abs(it) }?.takeIf { it > 0.0 } ?: 1.0

        fun y(value: Double): Float = (mid - (value / maxAbs) * (size.height / 2 - 6)).toFloat()
        fun x(index: Int): Float = axis.x(index)

        drawLine(Color.Gray.copy(alpha = 0.15f), Offset(padLeft, mid), Offset(size.width - padRight, mid), strokeWidth = 1f)

        macd.histogram.forEachIndexed { index, value ->
            val up = value >= 0
            val top = if (up) y(value) else y(0.0)
            val height = abs(y(value) - y(0.0)).coerceAtLeast(1f)
            drawRect(
                color = (if (up) upColor else downColor).copy(alpha = 0.7f),
                topLeft = Offset(x(index) - barWidth / 2, top),
                size = Size(barWidth, height),
            )
        }

        fun line(values: List<Double>, color: Color) {
            val points = values.mapIndexed { index, value -> Offset(x(index), y(value)) }
            for (i in 0 until points.size - 1) drawLine(color, points[i], points[i + 1], strokeWidth = 2f)
        }
        line(macd.diff, diffColor)
        line(macd.signal, signalColor)

        // 十字光标竖线：和主图共用 ChartAxis，所以同一个下标在上下面板落在同一个 x 上，
        // 一条线视觉上贯穿所有面板（用户 2026-08-11 要求"对齐上下不同的图表"）。
        selectedIndex?.takeIf { it in 0 until count }?.let { index ->
            drawLine(
                CrosshairColor,
                Offset(axis.x(index), 0f),
                Offset(axis.x(index), size.height),
                strokeWidth = 1.5f,
            )
        }
    }
}
