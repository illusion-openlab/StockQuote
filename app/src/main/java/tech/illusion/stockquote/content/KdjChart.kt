package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import tech.illusion.stockquote.data.KdjResult

/**
 * Real KDJ(9,3,3) — K/D/J lines, auto-scaled since J can swing outside the 0–100 band that bounds
 * K/D. Takes the already-computed result so the panel title's values and the lines agree.
 */
@Composable
fun KdjChart(
    kdj: KdjResult,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
) {
    Canvas(modifier = modifier) {
        val count = kdj.k.size
        if (count < 2) return@Canvas
        val padTop = 6f
        val padBottom = 6f
        val axis = ChartAxis.of(size.width, ChartLabelSp.toPx(), count) ?: return@Canvas
        val padLeft = axis.padLeft
        val padRight = axis.padRight
        val slot = axis.slot
        val chartHeight = size.height - padTop - padBottom

        val all = kdj.k + kdj.d + kdj.j
        val max = all.maxOrNull() ?: 100.0
        val min = all.minOrNull() ?: 0.0
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0

        fun y(value: Double): Float = (padTop + (max - value) / range * chartHeight).toFloat()
        fun x(index: Int): Float = axis.x(index)

        fun line(values: List<Double>, color: Color) {
            val points = values.indices.map { Offset(x(it), y(values[it])) }
            for (i in 0 until points.size - 1) drawLine(color, points[i], points[i + 1], strokeWidth = 2f)
        }
        line(kdj.k, KdjLines[0].color)
        line(kdj.d, KdjLines[1].color)
        line(kdj.j, KdjLines[2].color)

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
