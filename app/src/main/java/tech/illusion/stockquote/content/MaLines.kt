package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color

/**
 * One moving-average line: its indicator-chip key, its label, its window, and its colour.
 *
 * Chart, legend row and chip all read this one list, so a line's colour can never disagree with the
 * swatch next to its value (design contract v3 §1 "均线图例").
 */
data class MaLine(val key: String, val label: String, val period: Int, val color: Color)

// 均线固定色，沿用原型色板，不是主题语义角色
val MaLines: List<MaLine> = listOf(
    MaLine("ma5", "M5", 5, Color(0xFFB8935A)), // design-style: fixed-figma-color 素雅M5-暖赭黄
    MaLine("ma10", "M10", 10, Color(0xFF7D95A3)), // design-style: fixed-figma-color 素雅M10-灰蓝
    MaLine("ma20", "M20", 20, Color(0xFF9C8098)), // design-style: fixed-figma-color 素雅M20-灰紫
    MaLine("ma30", "M30", 30, Color(0xFF7F9370)), // design-style: fixed-figma-color 素雅M30-苔绿
    MaLine("ma180", "M180", 180, Color(0xFFA9776B)), // design-style: fixed-figma-color 素雅M180-陶土
)

/** 量能均线沿用价格均线的前两个色，让"同名同色"在两个面板之间保持一致。 */
val VolumeMaLines: List<MaLine> = MaLines.take(2)

/** One labelled line in a sub-panel, shared by its chart and its title-bar legend for the same
 *  reason as [MaLines]: a swatch must never disagree with the line it stands for. */
data class ChartLine(val label: String, val color: Color)

// KDJ / RSI 线固定色，沿用原型色板，不是主题语义角色
val KdjLines: List<ChartLine> = listOf(
    ChartLine("K", Color(0xFFB8935A)), // design-style: fixed-figma-color 素雅K-暖赭黄
    ChartLine("D", Color(0xFF7D95A3)), // design-style: fixed-figma-color 素雅D-灰蓝
    ChartLine("J", Color(0xFF9C8098)), // design-style: fixed-figma-color 素雅J-灰紫
)

val RsiLines: List<ChartLine> = listOf(
    ChartLine("RSI6", Color(0xFFB8935A)), // design-style: fixed-figma-color 素雅RSI6-暖赭黄
    ChartLine("RSI12", Color(0xFF7D95A3)), // design-style: fixed-figma-color 素雅RSI12-灰蓝
)
