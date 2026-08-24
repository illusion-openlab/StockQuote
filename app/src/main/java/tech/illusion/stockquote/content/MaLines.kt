package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color

/**
 * One moving-average line: its indicator-chip key, its label, its window, and its colour.
 *
 * Chart, legend row and chip all read this one list, so a line's colour can never disagree with the
 * swatch next to its value (design contract v3 §1 "均线图例").
 */
data class MaLine(val key: String, val label: String, val period: Int, val color: Color)

// 均线固定色（契约 v5 §2.2）。刻意全部避开红绿——红绿归蜡烛体；也避开 UI 的琥珀强调 #E8B34A。
val MaLines: List<MaLine> = listOf(
    MaLine("ma5", "M5", 5, Color(0xFFD8DEE9)), // design-style: fixed-figma-color 盘面M5-近白
    MaLine("ma10", "M10", 10, Color(0xFF5B8DEF)), // design-style: fixed-figma-color 盘面M10-靛蓝
    MaLine("ma20", "M20", 20, Color(0xFFC77DE0)), // design-style: fixed-figma-color 盘面M20-紫
    MaLine("ma30", "M30", 30, Color(0xFF4FC3E8)), // design-style: fixed-figma-color 盘面M30-青
    MaLine("ma180", "M180", 180, Color(0xFF8E96A3)), // design-style: fixed-figma-color 盘面M180-灰
)

/** 量能均线沿用价格均线的前两个色，让"同名同色"在两个面板之间保持一致。 */
val VolumeMaLines: List<MaLine> = MaLines.take(2)

/** One labelled line in a sub-panel, shared by its chart and its title-bar legend for the same
 *  reason as [MaLines]: a swatch must never disagree with the line it stands for. */
data class ChartLine(val label: String, val color: Color)

// KDJ / RSI / MACD 线固定色（契约 v5 §2.2）。原先 MACD 的 DIFF 用 colorScheme.interaction、
// DEA 用 colorScheme.alert——深色盘面下这两个角色都变成琥珀系，两条线会糊成一条，所以搬到这里。
val KdjLines: List<ChartLine> = listOf(
    ChartLine("K", Color(0xFFD8DEE9)), // design-style: fixed-figma-color 盘面K-近白
    ChartLine("D", Color(0xFF5B8DEF)), // design-style: fixed-figma-color 盘面D-靛蓝
    ChartLine("J", Color(0xFFC77DE0)), // design-style: fixed-figma-color 盘面J-紫
)

val RsiLines: List<ChartLine> = listOf(
    ChartLine("RSI6", Color(0xFF5B8DEF)), // design-style: fixed-figma-color 盘面RSI6-靛蓝
    ChartLine("RSI12", Color(0xFF4FC3E8)), // design-style: fixed-figma-color 盘面RSI12-青
)

val MacdDiffColor = Color(0xFFD8DEE9) // design-style: fixed-figma-color 盘面MACD-DIFF-近白
val MacdSignalColor = Color(0xFF5B8DEF) // design-style: fixed-figma-color 盘面MACD-DEA-靛蓝
