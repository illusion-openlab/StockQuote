package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.ColorScheme
import com.pico.spatial.ui.design.defaultColorScheme

/**
 * 同花顺深色盘面（设计契约 v5 §2.1）。固定色、不跟随头显深浅色偏好——这是盘面应用的身份，
 * 不是系统自适应主题。角色名全部对照 design-0.13.3-sources.jar 的 ColorScheme.kt:181 核实过。
 *
 * ⚠️ 三条不能忘的理由：
 *  - `fillLight` **必须显式覆盖**。默认 `FillLightAlpha` 是 15% 白，而 `ListItem` 的默认
 *    `backgroundColor` 取的就是它（ListItem.kt:72）——不覆盖的话深色面板上每一行又会变回浅色
 *    圆角卡片，选中态也会被它盖掉。
 *  - `labelPrimaryLight` **保留默认（白）**。它是 IconButton 默认 ButtonColors 的 contentColor
 *    （IconButton.kt:194），改成深色会让所有默认 IconButton 变成"深底深字"整个消失。琥珀实心钮
 *    上的深色文字在调用处单独传。
 *  - `alert`(橙) / `passable`(绿) **不覆盖也不使用**：在 A 股语境里会和涨跌语义、和琥珀强调撞车。
 *    所有图表线色改由 StockColors.kt 提供（契约 v5 §2.3）。
 */
val StockQuoteColorScheme: ColorScheme = defaultColorScheme(
    fillPrimary = Color(0xFF0F1318), // design-style: fixed-figma-color 盘面面板基底
    fillSecondary = Color(0xFF1C2530), // design-style: fixed-figma-color 盘面选中行底
    fillTertiary = Color(0xFF151B22), // design-style: fixed-figma-color 盘面分区格子底
    fillLight = Color(0xFF1A222C), // design-style: fixed-figma-color 盘面浅填充（压掉 15% 白默认值）
    labelPrimary = Color(0xFFEDF1F6), // design-style: fixed-figma-color 盘面主文字
    labelSecondary = Color(0xFFB7C2CF), // design-style: fixed-figma-color 盘面次级文字
    labelTertiary = Color(0xFF8B97A6), // design-style: fixed-figma-color 盘面三级文字（信息网格标签用这个）
    labelQuaternary = Color(0xFF6E7A8A), // design-style: fixed-figma-color 盘面弱化文字
    dividerLine = Color(0xFF1D242D), // design-style: fixed-figma-color 盘面分隔线/网格缝
    interaction = Color(0xFFE8B34A), // design-style: fixed-figma-color 盘面强调-琥珀金
    error = Color(0xFFFF6B63), // design-style: fixed-figma-color 盘面危险-亮珊瑚红
)
