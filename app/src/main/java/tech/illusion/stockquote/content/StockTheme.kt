package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.ColorScheme
import com.pico.spatial.ui.design.defaultColorScheme

/**
 * "素雅" palette confirmed on the HTML mockup (warm paper + muted slate-blue accent) — fixed colors,
 * not the system-adaptive vibrant scheme, so the app keeps this specific identity regardless of the
 * headset's light/dark preference. Values are lifted straight from the mockup's `:root` tokens; only
 * the roles that are actually visible in this screen (labels/interaction/error/dividers/fills) are
 * overridden — everything else falls back to PICO's own defaults.
 */
val StockQuoteColorScheme: ColorScheme = defaultColorScheme(
    labelPrimary = Color(0xFF34362F), // design-style: fixed-figma-color 素雅主文字-暖炭灰
    labelSecondary = Color(0xFF4F5245), // design-style: fixed-figma-color 素雅次级文字
    labelTertiary = Color(0xFF6C6F61), // design-style: fixed-figma-color 素雅三级文字
    labelQuaternary = Color(0xFF999C8C), // design-style: fixed-figma-color 素雅弱化文字
    fillSecondary = Color(0x665D7D8C), // design-style: fixed-figma-color 素雅强调填充-石青灰蓝40%（选中态）
    fillTertiary = Color(0x0A34362F), // design-style: fixed-figma-color 素雅弱填充-暖炭灰4%（未选中态）
    interaction = Color(0xFF5D7D8C), // design-style: fixed-figma-color 素雅强调色-石青灰蓝
    error = Color(0xFFA85A4C), // design-style: fixed-figma-color 素雅危险色-赭红（与红涨色同源）
    alert = Color(0xFFA3813F), // design-style: fixed-figma-color 素雅提醒色-暖赭黄（KDJ/RSI/BOLL占位提示用）
    dividerLine = Color(0x1A34362F), // design-style: fixed-figma-color 素雅分隔线-暖炭灰10%
)
