package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color

// 中国大陆 A 股市场惯例：红=涨，绿=跌（与欧美相反）。这是固定的业务语义色，不是随系统深浅色变化的主题角色，
// 所以不走 PicoTheme.colorScheme。
val StockUpColor = Color(0xFFA85A4C) // design-style: fixed-figma-color A股红涨绿跌语义色-涨(赭红)
val StockDownColor = Color(0xFF5F7D61) // design-style: fixed-figma-color A股红涨绿跌语义色-跌(苔绿)
