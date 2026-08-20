package tech.illusion.stockquote.content

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 图表标签字号，全部面板统一——横轴几何依赖它（右侧刻度槽宽度按它算）。 */
val ChartLabelSp: TextUnit = 9.sp

/**
 * **全部面板共用的横轴几何。这是唯一一份，不允许任何图表自己再算一遍。**
 *
 * 为什么必须共用：主图右侧要留一条价格刻度槽（宽度按标签字号算），而副图原本各自写 `padRight = 6f`，
 * 于是同一个 bar 下标在主图和副图里落在**不同的 x** 上——十字光标的竖线就没法贯穿上下面板对齐到同一根
 * （用户 2026-08-11 参考同花顺提出的"可以对齐上下不同的图表"）。现在副图也留出同宽的槽位，
 * 哪怕它们并不在那儿画字，纯粹为了对齐。
 *
 * 顺带也是 [ChartPan]（手部位移换算根数）和十字光标（点击坐标换算下标）的几何来源：两处必须和实际
 * 绘制完全一致，否则拖拽步长和点选落点会对不上。
 */
data class ChartAxis(val padLeft: Float, val padRight: Float, val slot: Float, val count: Int) {

    /** bar 下标 → 该 bar 中心的 x。 */
    fun x(index: Int): Float = padLeft + index * slot + slot / 2

    /** 面板局部 x → bar 下标。超出绘图区就夹到两端，点空白处也能选到最近的一根。 */
    fun indexAt(x: Float): Int? {
        if (slot <= 0f || count <= 0) return null
        return ((x - padLeft) / slot).toInt().coerceIn(0, count - 1)
    }

    companion object {
        private const val PadLeft = 6f

        /** 右侧价格刻度槽。副图不写字也要留，否则和主图对不齐。 */
        private const val GutterInLabels = 3.4f

        /** `null` 表示画布还没有可用宽度（或没有数据），调用方应当直接放弃这一帧。 */
        fun of(width: Float, labelSizePx: Float, count: Int): ChartAxis? {
            if (count <= 0) return null
            val padRight = labelSizePx * GutterInLabels
            val plotWidth = width - PadLeft - padRight
            if (plotWidth <= 0f) return null
            return ChartAxis(padLeft = PadLeft, padRight = padRight, slot = plotWidth / count, count = count)
        }
    }
}

/** 十字光标线色。固定色，不是主题语义角色——它要在主图和四个副图里保持完全一致的视觉重量。 */
val CrosshairColor: androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(0x8C6E6A63) // design-style: fixed-figma-color 素雅十字线-半透暖灰
