package tech.illusion.stockquote.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.sp
import tech.illusion.stockquote.data.BollResult
import tech.illusion.stockquote.data.Candle
import tech.illusion.stockquote.data.simpleMovingAverage

private val BollBandColor = Color(0xFF8F8577) // design-style: fixed-figma-color 素雅BOLL-暖灰

/**
 * Which overlays the price chart should draw. Volume lives in its own panel now (contract v3 §1).
 *
 * `annotateExtremes` must be false whenever the candles came from [tech.illusion.stockquote.data.buildCandles]:
 * the high/low of a synthesized bar is a shape, not a measurement, and labelling it with an exact
 * price would state a number the market never printed. The Y-axis scale still gets labelled — it
 * describes the plotted range, which is all it claims to do.
 */
data class KLineOverlays(
    val activeMaKeys: Set<String>,
    val boll: BollResult? = null,
    val annotateExtremes: Boolean = true,
    /** 十字光标选中的那根 bar 的下标；`null` = 没有选中。越界值会被忽略。 */
    val selectedIndex: Int? = null,
)

/**
 * Candlestick chart with MA/BOLL overlays, a priced Y axis, a sparse date axis, and automatic
 * high/low annotation — all hand-drawn on Canvas, no chart library (ported from the HTML prototype's
 * `drawCandles()` and extended per design contract v3).
 *
 * The extremes are recomputed from whatever slice is passed in, so switching range or receiving new
 * data re-annotates without any user action.
 *
 * @param onAxis 每次绘制回填横轴几何，见 [ChartAxis]。
 */
@Composable
fun KLineChart(
    candles: List<Candle>,
    overlays: KLineOverlays,
    upColor: Color,
    downColor: Color,
    axisColor: Color,
    modifier: Modifier = Modifier,
    onAxis: ((ChartAxis) -> Unit)? = null,
) {
    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas
        val labelSize = ChartLabelSp.toPx()
        val gridColor = axisColor.copy(alpha = 0.18f)
        val axisPaint = chartLabelPaint(axisColor.copy(alpha = 0.75f), labelSize, LabelAnchor.End)
        val datePaintStart = chartLabelPaint(axisColor.copy(alpha = 0.75f), labelSize, LabelAnchor.Start)
        val datePaintCenter = chartLabelPaint(axisColor.copy(alpha = 0.75f), labelSize, LabelAnchor.Center)
        val datePaintEnd = axisPaint

        val padTop = labelSize * 0.8f
        val padBottom = labelSize + 8f // the date row
        // 横轴几何走共用的 ChartAxis —— 副图用同一份，十字线才能贯穿上下面板对齐同一根 bar
        val axis = ChartAxis.of(size.width, labelSize, candles.size) ?: return@Canvas
        val padLeft = axis.padLeft
        val padRight = axis.padRight
        val slot = axis.slot
        val chartHeight = size.height - padTop - padBottom
        val plotWidth = size.width - padLeft - padRight
        if (chartHeight <= 0f) return@Canvas
        val candleWidth = (slot * 0.58f).coerceAtLeast(1f)
        onAxis?.invoke(axis)

        val closes = candles.map { it.close }
        val maSeries = MaLines
            .filter { line -> line.key in overlays.activeMaKeys }
            .map { line -> line to simpleMovingAverage(closes, line.period) }

        // Overlay values join the price extent so no line gets clipped out of the plot.
        val overlayValues = maSeries.flatMap { (_, values) -> values.filterNotNull() } +
            overlays.boll?.let { it.upper + it.lower }?.filterNotNull().orEmpty()
        val max = maxOf(candles.maxOf { it.high }, overlayValues.maxOrNull() ?: Double.NEGATIVE_INFINITY)
        val min = minOf(candles.minOf { it.low }, overlayValues.minOrNull() ?: Double.POSITIVE_INFINITY)
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0

        fun y(value: Double): Float = (padTop + (max - value) / range * chartHeight).toFloat()
        fun x(index: Int): Float = axis.x(index)

        // Y axis: a price beside every gridline.
        for (g in 0..3) {
            val gy = padTop + chartHeight / 3 * g
            drawLine(gridColor, Offset(padLeft, gy), Offset(size.width - padRight, gy), strokeWidth = 1f)
            val value = max - range / 3 * g
            drawChartLabel("%.2f".format(value), size.width - 2f, gy + labelSize * 0.35f, axisPaint)
        }

        // X axis: 3–4 sparse dates rather than one per candle. Blank for synthesized bars.
        val n = candles.size
        val dateIndices = listOf(0, n / 3, n * 2 / 3, n - 1).distinct().filter { it in candles.indices }
        val dateY = size.height - 1f
        dateIndices.forEachIndexed { position, index ->
            val date = candles[index].date.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val label = date.removePrefix(date.substringBefore('-') + "-") // yyyy-MM-dd -> MM-dd
            when (position) {
                0 -> drawChartLabel(label, padLeft, dateY, datePaintStart)
                dateIndices.lastIndex -> drawChartLabel(label, size.width - padRight, dateY, datePaintEnd)
                else -> drawChartLabel(label, x(index), dateY, datePaintCenter)
            }
        }

        candles.forEachIndexed { index, candle ->
            val up = candle.close >= candle.open
            val color = if (up) upColor else downColor
            drawLine(color, Offset(x(index), y(candle.high)), Offset(x(index), y(candle.low)), strokeWidth = 2f, cap = StrokeCap.Round)
            val top = y(maxOf(candle.open, candle.close))
            val bottom = y(minOf(candle.open, candle.close))
            drawRect(color, topLeft = Offset(x(index) - candleWidth / 2, top), size = Size(candleWidth, (bottom - top).coerceAtLeast(2f)))
        }

        fun overlayLine(values: List<Double?>, color: Color) {
            val points = values.mapIndexedNotNull { index, value -> value?.let { Offset(x(index), y(it)) } }
            for (i in 0 until points.size - 1) {
                drawLine(color, points[i], points[i + 1], strokeWidth = 2.5f, cap = StrokeCap.Round)
            }
        }
        maSeries.forEach { (line, values) -> overlayLine(values, line.color) }
        overlays.boll?.let { boll ->
            overlayLine(boll.upper, BollBandColor.copy(alpha = 0.7f))
            overlayLine(boll.mid, BollBandColor)
            overlayLine(boll.lower, BollBandColor.copy(alpha = 0.7f))
        }

        // Automatic extremes: value + a short pointer at the highest high and the lowest low.
        fun annotate(index: Int, value: Double, above: Boolean) {
            val px = x(index)
            val py = y(value)
            val tickEnd = if (above) py - labelSize * 0.5f else py + labelSize * 0.5f
            drawLine(axisColor.copy(alpha = 0.5f), Offset(px, py), Offset(px, tickEnd), strokeWidth = 1f)
            // Flip the text to the inside near either edge so it can't run off the plot.
            val anchor = if (px > padLeft + plotWidth * 0.6f) LabelAnchor.End else LabelAnchor.Start
            val textX = if (anchor == LabelAnchor.End) px - 3f else px + 3f
            val textY = if (above) tickEnd - 1f else tickEnd + labelSize
            drawChartLabel(
                "%.2f".format(value),
                textX,
                textY,
                chartLabelPaint(axisColor, labelSize, anchor),
            )
        }
        if (overlays.annotateExtremes) {
            candles.indices.maxByOrNull { candles[it].high }?.let { annotate(it, candles[it].high, above = true) }
            candles.indices.minByOrNull { candles[it].low }?.let { annotate(it, candles[it].low, above = false) }
        }

        // 十字光标：选中某根 bar 后画一横一竖两条参考线 + 右侧价格刻度 + 底部日期。
        // 具体数值不画在图里，而是由调用方渲染成一行读数（图上塞不下开高低收量五个数，字号也会小到看不清）。
        overlays.selectedIndex?.takeIf { it in candles.indices }?.let { index ->
            val candle = candles[index]
            val cx = x(index)
            val cy = y(candle.close)
            val hairColor = axisColor.copy(alpha = 0.85f)

            drawLine(hairColor, Offset(cx, padTop), Offset(cx, padTop + chartHeight), strokeWidth = 1.5f)
            drawLine(hairColor, Offset(padLeft, cy), Offset(size.width - padRight, cy), strokeWidth = 1.5f)
            // 交点上一个小圆点，让"选中的是收盘价"这件事不用猜
            drawCircle(hairColor, radius = 3f, center = Offset(cx, cy))

            // 右侧价格刻度：和 Y 轴那几个刻度同一位置，但画得更实，避免和背景刻度混淆
            drawChartLabel(
                "%.2f".format(candle.close),
                size.width - 2f,
                cy + labelSize * 0.35f,
                chartLabelPaint(axisColor, labelSize, LabelAnchor.End),
            )
            // 底部日期：贴着竖线，靠边时翻到内侧，避免跑出绘图区
            candle.date.takeIf { it.isNotBlank() }?.let { date ->
                val label = date.removePrefix(date.substringBefore('-') + "-")
                val anchor = when {
                    cx < padLeft + plotWidth * 0.12f -> LabelAnchor.Start
                    cx > padLeft + plotWidth * 0.88f -> LabelAnchor.End
                    else -> LabelAnchor.Center
                }
                drawChartLabel(label, cx, dateY, chartLabelPaint(axisColor, labelSize, anchor))
            }
        }
    }
}
