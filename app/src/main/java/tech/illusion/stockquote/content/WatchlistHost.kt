package tech.illusion.stockquote.content

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.design.VerticalDivider
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.delay
import tech.illusion.stockquote.DETAIL_WINDOW_ID
import tech.illusion.stockquote.detailWindowTag
import tech.illusion.stockquote.data.RemovedSnapshot
import tech.illusion.stockquote.data.StockRepository

private const val QUOTE_POLL_INTERVAL_MS = 5000L

/** 撤销条停留时长（契约 v5 §6）。 */
private const val UNDO_TIMEOUT_MS = 5000L

/** 一条待撤销的操作。`undo` 只捕获稳定引用（快照/code），不捕获会随轮询换实例的 `Stock`。 */
private data class UndoRequest(val message: String, val undo: () -> Unit)

/**
 * 主窗口入口。设计契约 v2：默认是**左右分栏**——左边自选股列表，右边选中个股的详情。
 *
 * 详情区渲染的就是那份唯一的 [DetailScreen]（契约 v3 的完整版），不是轻量预览：v2 §1 第 3 条原本设计的
 * "预览只画 MA5/MA10、完整指标留给独立窗口"那套分级**已被用户 2026-08-11 的"详情页设计统一"裁决推翻**。
 *
 * 查询子视图改为**只接管左栏**（契约 v5 §4）：右侧详情区在搜索期间原地不动，继续显示当前选中的股票，
 * 不再是旧版那种整窗切换——加完股票退出搜索，刚加的那只就在原地列表里。
 */
@Composable
fun WatchlistHost() {
    var searchOpen by remember { mutableStateOf(false) }
    var selectedCode by remember { mutableStateOf<String?>(null) }
    // 排序状态提到 Host 层：`置顶` 现在有**两个**入口（列表行长按菜单、详情区右上角"更多"菜单），
    // 两边必须共用同一份排序状态，否则会出现"从详情区置顶不清排序"这类不一致。
    var sortKey by remember { mutableStateOf<SortKey?>(null) }
    var sortAscending by remember { mutableStateOf(false) }
    // 当前待展示的撤销条；null 表示没有。
    var undo by remember { mutableStateOf<UndoRequest?>(null) }
    // 每次新的可撤销操作 +1。它同时是撤销条的 key（强制换节点，见 UndoBar 的 KDoc）
    // 和倒计时的重启信号——新的一条顶掉旧的，旧的直接落定。
    var undoGeneration by remember { mutableIntStateOf(0) }
    val navigator = LocalSpatialNavigator.current

    // 一次性拉全量历史（K 线用）；随后按设计文档 §6/§7 的节奏轮询自选股最新报价。
    // 生命周期绑定在这个常驻的主窗口 Composable 上，离开 composition 会自动取消，不需要手动清理。
    LaunchedEffect(Unit) { StockRepository.refreshAll() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(QUOTE_POLL_INTERVAL_MS)
            StockRepository.refreshWatchlistQuotes()
        }
    }

    // 每只股票一个窗口，可以并排比较（用户 2026-08-11）。tag 用 `detail-$code`：不同股票 → 不同实例；
    // 同一只再点一次 → 同 tag → 复用已开的那个，不会开出重复窗口。
    //
    // 原来这里先调了一次不带 tag 的 closeWindowContainer(DETAIL_WINDOW_ID)——那会**关掉该 id 下所有**
    // 窗口，正是"只能开一个"的直接原因。现在删掉；bundle 只在新建实例时读，而同 tag 复用的是同一只股票、
    // 内容本来就一样，所以不需要靠"先关再开"来保证内容刷新。
    val openInNewWindow: (String) -> Unit = { code ->
        navigator.openWindowContainer(
            DETAIL_WINDOW_ID,
            tag = detailWindowTag(code),
            bundle = Bundle().apply { putString("code", code) },
        )
    }

    val pinToTop: (String) -> Unit = { code ->
        StockRepository.moveToTop(code)
        // 契约 v2 §3 第 2 条：置顶同时清掉表头排序，否则用户正按价格/涨跌幅排序时置顶不会带来任何
        // 肉眼可见的位置变化，会显得"点了没反应"。
        sortKey = null
    }

    fun toggleSort(key: SortKey) {
        if (sortKey != key) {
            sortKey = key
            sortAscending = false
        } else if (!sortAscending) {
            sortAscending = true
        } else {
            sortKey = null
        }
    }

    fun offerUndo(message: String, action: () -> Unit) {
        undo = UndoRequest(message, action)
        undoGeneration += 1
    }

    LaunchedEffect(undoGeneration) {
        if (undo == null) return@LaunchedEffect
        delay(UNDO_TIMEOUT_MS)
        undo = null
    }

    // 契约 v5 §6：即时移除 + 5 秒撤销条，取代了旧版的二次确认弹窗。三个移除入口
    // （搜索结果行尾钮、列表行"✕"、详情区"更多"菜单）都通过这一个回调汇入撤销条。
    val onRemoved: (RemovedSnapshot) -> Unit = { snapshot ->
        offerUndo("已移除「${snapshot.stock.name}」") { StockRepository.restore(snapshot) }
    }

    val watchlist = StockRepository.watchlist
    // 默认选中第一行（对齐 v2 稿的"默认选中第一项"）。写成派生值而不是 LaunchedEffect 纠正：
    // 选中的股票被移除后会自动落回第一行，不需要额外的同步逻辑，也不会出现指向已删除代码的空窗。
    val activeCode = watchlist.firstOrNull { it.code == selectedCode }?.code
        ?: watchlist.firstOrNull()?.code

    Row(modifier = Modifier.fillMaxSize()) {
        // 撤销条是**浮层**（契约 v5 §6「浮在左栏底部」），所以这里是 Box 而不是 Column：
        // 写成 Column + 兄弟节点的话，撤销条一出现就会把整份列表往上顶掉自己那一条的高度，
        // 5 秒后又落回去——交互做到一半整栏重排。现在它盖在列表上面，列表一动不动。
        Box(
            modifier = Modifier
                .width(ListPaneWidth)
                .fillMaxHeight()
                .background(ListPaneBase),
        ) {
            if (searchOpen) {
                SearchPane(
                    // 清 Adding/Failed 残留（契约 v5 §5.3 第 ③ 条）不在这里做：那个保证已经挪进
                    // SearchPane 自己的 DisposableEffect，覆盖所有离开 composition 的路径，不只这一颗按钮。
                    onClose = { searchOpen = false },
                    onSelect = { selectedCode = it },
                    onRemoved = onRemoved,
                    // 加入自选也给撤销条（契约 v5 §0 缺陷 7 / §4 示意图）：撤销一次"加入"就是 remove(code)，
                    // 复用移除那套 offerUndo/UndoBar，不另造一套反馈机制。
                    onAdded = { code, name -> offerUndo("已加入「$name」") { StockRepository.remove(code) } },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                WatchlistPane(
                    selectedCode = activeCode,
                    onSelect = { selectedCode = it },
                    onOpenSearch = { searchOpen = true },
                    onOpenInNewWindow = openInNewWindow,
                    onPinToTop = pinToTop,
                    onRemoveRequest = { code -> StockRepository.remove(code)?.let(onRemoved) },
                    sortKey = sortKey,
                    sortAscending = sortAscending,
                    onToggleSort = ::toggleSort,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            undo?.let { request ->
                key(undoGeneration) {
                    UndoBar(
                        message = request.message,
                        onUndo = {
                            request.undo()
                            undo = null
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        DetailPane(
            code = activeCode,
            onOpenInNewWindow = openInNewWindow,
            onPinToTop = pinToTop,
            onRemoveRequest = { code -> StockRepository.remove(code)?.let(onRemoved) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** 列表行长按菜单和详情区"更多"菜单共用这三项，避免两处各写一份将来改歪。 */
@Composable
internal fun StockActionMenu(
    onDismiss: () -> Unit,
    onOpenInNewWindow: () -> Unit,
    onPinToTop: () -> Unit,
    onRemove: () -> Unit,
) {
    // MenuItem 内部已经自带 clickable + LocalIndication + spatialHoverEffect +
    // controllerHapticFeedback（已读 0.13.3 源码确认），所以这里不用自己补点击三件套。
    Menu(onDismissRequest = onDismiss) {
        MenuItem(
            title = { Text("⤢  新窗口打开") },
            onClick = {
                onDismiss()
                onOpenInNewWindow()
            },
        )
        MenuItem(
            title = { Text("⇧  置顶") },
            onClick = {
                onDismiss()
                onPinToTop()
            },
        )
        MenuItem(
            title = { Text("✕  移除") },
            onClick = {
                onDismiss()
                // 破坏性操作现在走即时移除 + Host 层的撤销条（契约 v5 §6），不再有二次确认弹窗。
                onRemove()
            },
        )
    }
}

/** 详情栏头部行要为右上角那颗"更多"按钮让出的宽度，见 [DetailScreen] 的 `headerEndInset`。 */
private val MoreButtonInset = 52.dp

/**
 * 右侧详情栏：那份唯一的 [DetailScreen] + 浮在右上角的"更多"菜单按钮。
 *
 * 菜单三项（新窗口打开/置顶/移除）和列表行长按菜单是同一个 [StockActionMenu]。
 *
 * 之前这里是一颗独占一行的"⤢ 在新窗口打开"按钮，白占约 40dp 高度；改成右上角的浮层按钮后不再占行，
 * 代价是要给 `DetailScreen` 的头部行传 [MoreButtonInset] —— 详情自己的价格数字也在右上角，不让位会重叠。
 */
@Composable
private fun DetailPane(
    code: String?,
    onOpenInNewWindow: (String) -> Unit,
    onPinToTop: (String) -> Unit,
    onRemoveRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (code == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "还没有自选股，点左栏搜索框添加",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelTertiary,
            )
        }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    // 切换选中的股票时把菜单收起来。用 LaunchedEffect 而不是拿 code 当 remember 的 key：SDK 的点击/手势
    // 组件会保留**首次**传入的 lambda，remember key 一变就会往一个已被丢弃的 MutableState 里写
    // （见 pico-sdk-compose-pitfalls 陷阱 1）。
    LaunchedEffect(code) { menuOpen = false }

    Box(modifier = modifier) {
        DetailScreen(code = code, headerEndInset = MoreButtonInset)

        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 20.dp)) {
            ToggleableChip(
                label = { Text("⋯") },
                isToggleOn = menuOpen,
                onClick = { menuOpen = !menuOpen },
            )
            if (menuOpen) {
                StockActionMenu(
                    onDismiss = { menuOpen = false },
                    onOpenInNewWindow = { onOpenInNewWindow(code) },
                    onPinToTop = { onPinToTop(code) },
                    onRemove = { onRemoveRequest(code) },
                )
            }
        }
    }
}

internal enum class SortKey { NAME, PRICE, CHANGE }
