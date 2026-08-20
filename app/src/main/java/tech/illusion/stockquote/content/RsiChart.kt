package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Real dual-line RSI(6)/RSI(12) via Wilder's smoothing — bounded 0–100 by definition. Takes the
 * already-computed series so the panel title's values and the lines agree.
 */
@Composable
fun RsiChart(
    rsi6: List<Double?>,
    rsi12: List<Double?>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
) {
    Canvas(modifier = modifier) {
        val count = maxOf(rsi6.size, rsi12.size)
        if (count < 2) return@Canvas
        val padTop = 6f
        val padBottom = 6f
        val axis = ChartAxis.of(size.width, ChartLabelSp.toPx(), count) ?: return@Canvas
        val padLeft = axis.padLeft
        val padRight = axis.padRight
        val slot = axis.slot
        val chartHeight = size.height - padTop - padBottom

        fun y(value: Double): Float = (padTop + (100.0 - value) / 100.0 * chartHeight).toFloat()
        fun x(index: Int): Float = axis.x(index)

        listOf(20.0, 50.0, 80.0).forEach { level ->
            drawLine(Color.Gray.copy(alpha = 0.12f), Offset(padLeft, y(level)), Offset(size.width - padRight, y(level)), strokeWidth = 1f)
        }

        fun line(values: List<Double?>, color: Color) {
            val points = values.mapIndexedNotNull { index, value -> value?.let { Offset(x(index), y(it)) } }
            for (i in 0 until points.size - 1) drawLine(color, points[i], points[i + 1], strokeWidth = 2f)
        }
        line(rsi6, RsiLines[0].color)
        line(rsi12, RsiLines[1].color)

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
