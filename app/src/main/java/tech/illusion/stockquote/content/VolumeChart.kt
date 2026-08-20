package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import tech.illusion.stockquote.data.Candle
import tech.illusion.stockquote.data.simpleMovingAverage

/**
 * Volume bars plus M5/M10 volume moving averages, in their own panel rather than squeezed under the
 * price chart (design contract v3 §1). Bars take the day's own up/down colour, matching the candle
 * they belong to.
 */
@Composable
fun VolumeChart(
    candles: List<Candle>,
    upColor: Color,
    downColor: Color,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
) {
    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas
        val axis = ChartAxis.of(size.width, ChartLabelSp.toPx(), candles.size) ?: return@Canvas
        val padLeft = axis.padLeft
        val padRight = axis.padRight
        val padTop = 4f
        val padBottom = 4f
        val plotWidth = size.width - padLeft - padRight
        val plotHeight = size.height - padTop - padBottom
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas
        val slot = axis.slot
        val barWidth = (slot * 0.58f).coerceAtLeast(1f)

        val volumes = candles.map { it.volume }
        val maSeries = VolumeMaLines.map { line -> line to simpleMovingAverage(volumes, line.period) }
        val max = maxOf(
            volumes.maxOrNull() ?: 0.0,
            maSeries.flatMap { (_, values) -> values.filterNotNull() }.maxOrNull() ?: 0.0,
        ).takeIf { it > 0.0 } ?: 1.0

        fun y(value: Double): Float = (padTop + (1.0 - value / max) * plotHeight).toFloat()
        fun x(index: Int): Float = axis.x(index)

        candles.forEachIndexed { index, candle ->
            val up = candle.close >= candle.open
            val top = y(candle.volume)
            drawRect(
                color = (if (up) upColor else downColor).copy(alpha = 0.55f),
                topLeft = Offset(x(index) - barWidth / 2, top),
                size = Size(barWidth, (size.height - padBottom - top).coerceAtLeast(1f)),
            )
        }

        maSeries.forEach { (line, values) ->
            val points = values.mapIndexedNotNull { index, value -> value?.let { Offset(x(index), y(it)) } }
            for (i in 0 until points.size - 1) {
                drawLine(line.color, points[i], points[i + 1], strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }

        // 十字光标竖线：和主图共用 ChartAxis，所以同一个下标在上下面板落在同一个 x 上，
        // 一条线视觉上贯穿所有面板（用户 2026-08-11 要求"对齐上下不同的图表"）。
        selectedIndex?.takeIf { it in candles.indices }?.let { index ->
            drawLine(
                CrosshairColor,
                Offset(axis.x(index), 0f),
                Offset(axis.x(index), size.height),
                strokeWidth = 1.5f,
            )
        }
    }
}
