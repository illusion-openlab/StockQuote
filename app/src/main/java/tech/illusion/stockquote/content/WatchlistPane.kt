package tech.illusion.stockquote.content

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleButton
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import tech.illusion.stockquote.data.Stock
import tech.illusion.stockquote.data.StockRepository
import tech.illusion.stockquote.data.changePercent
import tech.illusion.stockquote.data.price

/**
 * 左侧列表栏宽度，对齐 v2 视觉稿的 `.list-pane { width: 400px }`。主窗口 manifest 尺寸是 **1080×660dp**。
 *
 * **改这个数就要同步 `Main.kt` 的 `DetailWindowWidth`**（= 1080 − 这里的 400 = 680），否则独立详情
 * 窗口会和分栏详情区不一样宽，同一份 [DetailScreen] 在两处的排版就会分叉。
 */
internal val ListPaneWidth = 400.dp

@Composable
internal fun WatchlistPane(
    selectedCode: String?,
    onSelect: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenInNewWindow: (String) -> Unit,
    onPinToTop: (String) -> Unit,
    onRemoveRequest: (String) -> Unit,
    sortKey: SortKey?,
    sortAscending: Boolean,
    onToggleSort: (SortKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stocks = StockRepository.watchlist
    var editMode by remember { mutableStateOf(false) }
    // 存代码而不是 Stock 快照：Stock 每次报价轮询都会被替换成新实例，捕获旧对象会拿到过期价格。
    var menuForCode by remember { mutableStateOf<String?>(null) }

    val sorted = remember(stocks, sortKey, sortAscending) {
        val base = when (sortKey) {
            null -> stocks.sortedBy { it.orderIndex }
            SortKey.NAME -> stocks.sortedBy { it.name }
            SortKey.PRICE -> stocks.sortedBy { it.price() }
            SortKey.CHANGE -> stocks.sortedBy { it.changePercent() }
        }
        if (sortKey != null && sortAscending) base else if (sortKey != null) base.reversed() else base
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
        Text(
            text = "自选股列表",
            style = PicoTheme.typography.titleLarge,
            color = PicoTheme.colorScheme.labelPrimary,
        )
        Spacer(modifier = Modifier.height(14.dp))

        // 编辑 → 搜索栏（顶部编辑栏，只有这两个元素）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToggleButton(checked = editMode, onCheckedChange = { editMode = it }) {
                Text(if (editMode) "完成" else "编辑")
            }
            Button(
                onClick = onOpenSearch,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary),
            ) {
                Text("🔍  查股票…", color = PicoTheme.colorScheme.labelTertiary)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // 表头：只有进入编辑模式才能点击排序
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SortHeader("股票/代码", SortKey.NAME, sortKey, sortAscending, editMode) { onToggleSort(SortKey.NAME) }
            SortHeader("价格", SortKey.PRICE, sortKey, sortAscending, editMode) { onToggleSort(SortKey.PRICE) }
            SortHeader("涨跌幅", SortKey.CHANGE, sortKey, sortAscending, editMode) { onToggleSort(SortKey.CHANGE) }
        }
        HorizontalDivider()

        if (stocks.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = "暂无自选股，点击上方查询加入",
                    style = PicoTheme.typography.bodyMedium,
                    color = PicoTheme.colorScheme.labelTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = sorted, key = { it.code }) { stock ->
                    val code = stock.code
                    WatchlistRow(
                        stock = stock,
                        editMode = editMode,
                        selected = code == selectedCode,
                        menuOpen = code == menuForCode,
                        onSelect = { onSelect(code) },
                        onLongPress = { menuForCode = code },
                        onDismissMenu = { menuForCode = null },
                        onDeleteRequest = { onRemoveRequest(code) },
                        onOpenInNewWindow = { onOpenInNewWindow(code) },
                        onPinToTop = { onPinToTop(code) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

}

@Composable
internal fun SortHeader(
    label: String,
    key: SortKey,
    activeKey: SortKey?,
    ascending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val arrow = if (activeKey == key) (if (ascending) " ▲" else " ▼") else ""
    Link(onClick = onClick, enabled = enabled) {
        Text(
            text = label + arrow,
            style = PicoTheme.typography.labelMedium,
            color = if (activeKey == key) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.labelTertiary,
        )
    }
}

@Composable
internal fun WatchlistRow(
    stock: Stock,
    editMode: Boolean,
    selected: Boolean,
    menuOpen: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOpenInNewWindow: () -> Unit,
    onPinToTop: () -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val change = stock.changePercent()
    val trendColor = if (change >= 0) StockUpColor else StockDownColor

    // Box 是菜单的锚：Menu 是 Popup，会定位到它所在的父布局，这样菜单就锚在被长按的那一行上。
    Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .indication(interactionSource, LocalIndication.current)
                .controllerHapticFeedback(interactionSource = interactionSource)
                // 这里**不要**再链 spatialHoverEffect：ListItem 内部已经调过一次
                // （design-0.13.3 的 ListItem.kt:72），调用方再加一次会让悬停效果叠两层。
                .pointerInput(Unit) {
                    // 点击和长按必须写在**同一个** detectTapGestures 里。拆成两个 pointerInput 会互相抢
                    // 事件消费；这个 SDK 签名本身就同时收 onTap/onLongPress/onPress（已读 0.13.3 源码
                    // ComposeTapGestureDetectorExt.kt 确认）。targetedToEntity = null 表示 2D/3D 事件都收。
                    detectTapGestures(
                        context = context,
                        targetedToEntity = null,
                        onLongPress = { onLongPress() },
                        onPress = { offset ->
                            // 手动驱动 interactionSource，这样上面的 indication/haptic 才有按压态可用
                            // ——裸 pointerInput 不像 clickable 那样自带按压反馈。
                            val press = PressInteraction.Press(offset)
                            interactionSource.emit(press)
                            interactionSource.emit(
                                if (tryAwaitRelease()) {
                                    PressInteraction.Release(press)
                                } else {
                                    PressInteraction.Cancel(press)
                                },
                            )
                        },
                        onTap = { onSelect() },
                    )
                },
            // 行背景必须走 ListItem 自己的 colors，不能在调用方叠 Modifier.background——
            // ListItem 内部是 `.then(modifier).spatialHoverEffect().background(colors.backgroundColor)`
            // （ListItem.kt:69-73），调用方的 modifier 在**前**，所以自己叠的背景会被 SDK 那层直接盖住。
            //
            // 默认 backgroundColor 是 PicoTheme.colorScheme.fillLight（0x26FFFFFF，15% 白），而
            // StockQuoteColorScheme 没有覆盖 fillLight——这就是"每一行都变成浅色圆角卡片"的原因，
            // 也是选中态（4% 暖炭灰）一直几乎看不出来的原因：它被 15% 白盖掉了。
            //
            // ⚠️ 未选中必须传 Color.Transparent 而不是 Color.Unspecified：ListItemColors.copy() 内部是
            // takeOrElse{}（ListItem.kt:202），Unspecified 会被当成"没指定"回落到默认的 fillLight。
            colors = ListItemDefaults.listItemColors(
                backgroundColor = if (selected) PicoTheme.colorScheme.fillTertiary else Color.Transparent,
            ),
            // 设计稿 .row.selected 的圆角是 10dp；ListItem 默认 shape 是 RadiusLarge(16dp)，
            // 而且 ListItemDefaults.shape 是 internal 引用不到，所以显式传自己的。
            shape = RoundedCornerShape(10.dp),
            headlineContent = {
                Text(
                    text = stock.name,
                    style = PicoTheme.typography.bodyLarge,
                    color = PicoTheme.colorScheme.labelPrimary,
                )
            },
            supportingContent = {
                Text(
                    text = stock.code,
                    style = PicoTheme.typography.bodySmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            },
            trailingContent = {
                // 尺寸按 400dp 分栏收窄过（原来是整窗 1080dp 的排布，直接塞进分栏会挤爆）
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sparkline(hist = stock.hist, color = trendColor, modifier = Modifier.size(width = 40.dp, height = 18.dp))
                    Text(
                        text = "%.2f".format(stock.price()),
                        style = PicoTheme.typography.bodyMedium,
                        color = PicoTheme.colorScheme.labelPrimary,
                    )
                    Text(
                        text = "%s%.2f%%".format(if (change >= 0) "+" else "", change),
                        style = PicoTheme.typography.labelSmall,
                        color = trendColor,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                    )
                    if (editMode) {
                        Link(onClick = onDeleteRequest) {
                            Text("✕", color = PicoTheme.colorScheme.error)
                        }
                    }
                }
            },
        )

        if (menuOpen) {
            StockActionMenu(
                onDismiss = onDismissMenu,
                onOpenInNewWindow = onOpenInNewWindow,
                onPinToTop = onPinToTop,
                onRemove = onDeleteRequest,
            )
        }
    }
}
