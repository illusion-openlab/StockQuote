package tech.illusion.stockquote.content

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "自选股",
                style = PicoTheme.typography.titleLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            ToggleButton(checked = editMode, onCheckedChange = { editMode = it }) {
                Text(if (editMode) "完成" else "编辑")
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // 常驻搜索入口：点了整个左栏切到 SearchPane（右栏详情不动）。
        // 这里刻意用 Button 假装成搜索框，而不是放一个真 SearchField：真输入框会把系统 IME
        // 拉起来占掉半个视野，而这一步用户只是想"进搜索"，还没开始打字。
        Button(
            onClick = onOpenSearch,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary),
        ) {
            Text("🔍  代码 / 名称", color = PicoTheme.colorScheme.labelTertiary)
        }
        Spacer(modifier = Modifier.height(14.dp))

        // 表头：排序不再受编辑模式限制，随时可点击
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SortHeader("名称", SortKey.NAME, sortKey, sortAscending) { onToggleSort(SortKey.NAME) }
            SortHeader("最新价", SortKey.PRICE, sortKey, sortAscending) { onToggleSort(SortKey.PRICE) }
            SortHeader("涨跌幅", SortKey.CHANGE, sortKey, sortAscending) { onToggleSort(SortKey.CHANGE) }
        }
        HorizontalDivider()

        if (stocks.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = "还没有自选股，点上方搜索框添加",
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
    onClick: () -> Unit,
) {
    val arrow = if (activeKey == key) (if (ascending) " ▲" else " ▼") else ""
    Link(onClick = onClick) {
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
            // SDK 默认 backgroundColor 取的是 PicoTheme.colorScheme.fillLight（原生默认 0x26FFFFFF，
            // 15% 白，深色盘面上会把每一行都变成浅色圆角卡片）。StockQuoteColorScheme **已经显式覆盖了
            // fillLight**（0xFF1A222C，见 StockTheme.kt 的第一条理由），所以那层白底不再存在。
            //
            // 选中态用 fillSecondary（0xFF1C2530，主题里的注释就写着"盘面选中行底"）。不要退回
            // fillTertiary（0xFF151B22）：它和左栏底色 ListPaneBase（0xFF12171E）每个通道只差 3~4，
            // 肉眼等于没有选中态。选中行还额外叠一条 3dp 琥珀强调条（见下面的 Box，契约 v5 §3.1）。
            //
            // ⚠️ 未选中必须传 Color.Transparent 而不是 Color.Unspecified：ListItemColors.copy() 内部是
            // takeOrElse{}（ListItem.kt:202），Unspecified 会被当成"没指定"回落到默认的 fillLight。
            colors = ListItemDefaults.listItemColors(
                backgroundColor = if (selected) PicoTheme.colorScheme.fillSecondary else Color.Transparent,
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
                    color = PicoTheme.colorScheme.labelQuaternary,
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

        // 选中行的 3dp 琥珀强调条（契约 v5 §3.1）。画在 ListItem **之后**才盖得住行底色。
        //
        // 为什么要套一层 matchParentSize 的 Box，而不是直接 align(CenterStart).fillMaxHeight()：
        // LazyColumn 给 item 的 maxHeight 是无界的，Box 又不传递 min 约束，fillMaxHeight() 在无界
        // 约束下会退化成 minHeight(=0)，条子高度直接变 0 看不见。matchParentSize 拿到的是"其他子项
        // 量完之后"的确切行高，套在外面再 fill 就有界了。
        //
        // start 内缩 4dp、上下各留 6dp：行自己的 shape 是 RoundedCornerShape(10dp)，贴着真边画会被
        // 圆角切掉两头（同 ParamGrid 处理圆角内缩的思路）。
        //
        // 这个 Box 没有任何 pointerInput，不参与命中测试，所以不会挡掉下面 ListItem 的点击/长按。
        if (selected) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(PicoTheme.colorScheme.interaction, RoundedCornerShape(2.dp)),
                )
            }
        }

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
