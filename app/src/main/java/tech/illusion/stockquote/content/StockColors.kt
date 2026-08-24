package tech.illusion.stockquote.content

import androidx.compose.ui.graphics.Color

// 中国大陆 A 股市场惯例：红=涨，绿=跌（与欧美相反）。固定业务语义色，不随系统深浅色变化，
// 所以不走 PicoTheme.colorScheme。契约 v5 §2.2 把整套图表色都收在本文件，PicoTheme 只管 UI 控件。
val StockUpColor = Color(0xFFF0443E) // design-style: fixed-figma-color 盘面涨-高饱和红
val StockDownColor = Color(0xFF16B979) // design-style: fixed-figma-color 盘面跌-高饱和绿

/** 左侧自选列表栏底色：比 fillPrimary 亮一档做分栏区隔，不是主题语义角色。 */
val ListPaneBase = Color(0xFF12171E) // design-style: fixed-figma-color 盘面列表栏底

// ＋/◌/✓/! 四态钮的槽色（契约 v5 §5.4）。③ 已自选直接用 PicoTheme 的 interaction（琥珀），
// 不在这里重复定义——那样会出现两个"琥珀"将来各改各的。
val ToggleIdleSlot = Color(0xFF232C38) // design-style: fixed-figma-color 四态钮-未自选槽
val ToggleBusySlot = Color(0xFF33290F) // design-style: fixed-figma-color 四态钮-提交中琥珀化暗槽
val ToggleFailSlot = Color(0xFF3A1E1B) // design-style: fixed-figma-color 四态钮-失败暗红槽
