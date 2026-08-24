package tech.illusion.stockquote.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.Text
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.illusion.stockquote.data.Membership
import tech.illusion.stockquote.data.RemovedSnapshot
import tech.illusion.stockquote.data.StockDataRepository
import tech.illusion.stockquote.data.StockRepository
import tech.illusion.stockquote.data.StockSuggestion
import tech.illusion.stockquote.data.changePercent
import tech.illusion.stockquote.data.price

/** 输入停顿多久才触发一次全市场请求，见契约增量 v4。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * 左栏的搜索态（契约 v5 §4）。**只接管 400dp 左栏，右侧详情原地不动**——这是相对旧
 * `QueryScreen` 整窗接管的核心收益：加完股票退出搜索，刚加的那只就在原地列表里，
 * 不再出现"加完之后那只去哪了"。
 *
 * 加/删全部收敛到行尾一颗 [MembershipToggle]，不再有"先点行选中、才冒出加入按钮"那个中间态，
 * 已自选的行也不再被 `clickable(enabled = !inWatchlist)` 锁死。
 */
@Composable
internal fun SearchPane(
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onRemoved: (RemovedSnapshot) -> Unit,
    onAdded: (code: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var isSearchingRemote by remember { mutableStateOf(false) }
    var remoteFailed by remember { mutableStateOf(false) }
    var remoteResults by remember { mutableStateOf<List<StockSuggestion>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val localResults = remember(query) {
        if (query.isBlank()) {
            StockRepository.universe
        } else {
            StockRepository.universe.filter { it.name.contains(query) || it.code.contains(query) }
        }
    }

    // 本地过滤不等这条协程——防抖+全市场搜索只负责补充"本地之外"的结果，失败也不影响本地结果（契约 v4）。
    LaunchedEffect(query) {
        if (query.isBlank()) {
            isSearchingRemote = false
            remoteFailed = false
            remoteResults = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        isSearchingRemote = true
        remoteFailed = false
        val suggestions = StockDataRepository.searchSuggestions(query)
        isSearchingRemote = false
        if (suggestions == null) {
            remoteFailed = true
            remoteResults = emptyList()
        } else {
            remoteResults = suggestions.filter { s -> localResults.none { it.code == s.code } }
        }
    }

    // 契约 v5 §5.3 第 ② 条：搜索词一变，那些不再出现在结果里的 code 的 Adding/Failed 残留必须一起清掉，
    // 否则用户把搜索词改回来时会看到一个过期的红叹号 / 转圈。
    LaunchedEffect(localResults, remoteResults) {
        StockRepository.pruneOpStates((localResults.map { it.code } + remoteResults.map { it.code }).toSet())
    }

    // 契约 v5 §5.3 第 ③ 条：退出搜索态清掉全部 Adding/Failed 残留，否则下次进搜索会看到上次留下的红叹号。
    // 这条保证挂在 SearchPane 自己身上，而不是调用方"取消"按钮的回调里：离开 composition 的路径不止那一条
    // （Host 侧状态被别的分支改写、窗口销毁重建…），只挂在那一颗按钮上就会漏。
    DisposableEffect(Unit) {
        onDispose { StockRepository.clearOpStates() }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "查股票",
                style = PicoTheme.typography.titleLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Link(onClick = onClose) {
                Text(
                    text = "取消",
                    style = PicoTheme.typography.labelMedium,
                    color = PicoTheme.colorScheme.interaction,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // ⚠️ fillMaxWidth() 不能省。SearchField 内部是 `modifier.width(SearchFieldDefaults.DefaultWidth)`
        // （SearchField.kt:78，DefaultWidth = 280dp），调用方的 modifier 在**前**——只有先把宽度定死成
        // fill，那句 280dp 才会被夹住。不传就会得到一个固定 280dp 的搜索框，在 400dp 栏里明显偏窄。
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("代码 / 名称") },
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isSearchingRemote -> "搜索全市场中…"
                remoteFailed -> "全市场搜索暂时不可用，仅显示本地结果"
                query.isBlank() -> "输入代码或名称开始搜索"
                else -> "${localResults.size + remoteResults.size} 个匹配"
            },
            style = PicoTheme.typography.labelSmall,
            color = PicoTheme.colorScheme.labelQuaternary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        if (localResults.isEmpty() && remoteResults.isEmpty() && !isSearchingRemote && query.isNotBlank()) {
            Text(
                text = "没有匹配的股票",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelTertiary,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = localResults, key = { it.code }) { stock ->
                val code = stock.code
                val change = stock.changePercent()
                // 本地这条路径没有失败信号：code 本来就来自 StockRepository.universe，
                // 而 NotAdded 又保证了它还不在自选里，addFromUniverse 必然成功，直接报撤销条。
                val add: () -> Unit = {
                    StockRepository.addFromUniverse(code)
                    onAdded(code, stock.name)
                }
                ResultRow(
                    name = stock.name,
                    code = code,
                    // 本地已知的才有真行情；远端命中在加进来之前一律不显示价格（不造假数据）
                    price = "%.2f".format(stock.price()),
                    change = "%s%.2f%%".format(if (change >= 0) "+" else "", change),
                    changeColor = if (change >= 0) StockUpColor else StockDownColor,
                    membership = StockRepository.membershipOf(code),
                    onSelect = { onSelect(code) },
                    onAdd = add,
                    onRemove = { StockRepository.remove(code)?.let(onRemoved) },
                    onRetry = add,
                )
                HorizontalDivider()
            }

            if (remoteResults.isNotEmpty()) {
                item(key = "remote-section-label") {
                    Text(
                        text = "全市场",
                        style = PicoTheme.typography.labelSmall,
                        color = PicoTheme.colorScheme.labelQuaternary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(items = remoteResults, key = { "remote-${it.code}" }) { suggestion ->
                    val code = suggestion.code
                    val name = suggestion.name
                    // 只有真的加成功才报撤销条：抓取失败（→ Failed）和并发重复点击被守卫挡下时都返回 false。
                    val add: () -> Unit = {
                        scope.launch { if (StockRepository.addRemote(code, name)) onAdded(code, name) }
                    }
                    ResultRow(
                        name = name,
                        code = code,
                        price = null,
                        change = null,
                        changeColor = Color.Unspecified,
                        membership = StockRepository.membershipOf(code),
                        onSelect = { onSelect(code) },
                        onAdd = add,
                        onRemove = { StockRepository.remove(code)?.let(onRemoved) },
                        onRetry = add,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * 搜索结果行。本地命中和全市场命中共用——两者唯一的差别是远端行在加进来之前没有真实行情，
 * 这时 [price]/[change] 传 null，行里就不画价格，而不是显示 `--` 或者假数字。
 */
@Composable
private fun ResultRow(
    name: String,
    code: String,
    price: String?,
    change: String?,
    changeColor: Color,
    membership: Membership,
    onSelect: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    ListItem(
        // 已自选的行点了去右栏看详情；没自选的行点了不做事（加自选只走行尾那颗钮，
        // 不再有"点行选中→冒出按钮"的中间态）。
        modifier = Modifier.fillMaxWidth().clickable(enabled = membership == Membership.Added) { onSelect() },
        // 未选中必须传 Color.Transparent 而不是 Color.Unspecified：ListItemColors.copy() 内部是
        // takeOrElse{}（ListItem.kt:202），Unspecified 会被当成"没指定"回落到默认的 fillLight。
        colors = ListItemDefaults.listItemColors(backgroundColor = Color.Transparent),
        headlineContent = {
            Text(text = name, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary)
        },
        supportingContent = {
            Text(
                text = code + exchangeSuffix(code),
                style = PicoTheme.typography.bodySmall,
                color = PicoTheme.colorScheme.labelQuaternary,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (price != null) {
                    Text(text = price, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary)
                }
                if (change != null) {
                    Text(text = change, style = PicoTheme.typography.labelSmall, color = changeColor)
                }
                // ⚠️ key(membership) 不能省，见 MembershipToggle 的 KDoc：SDK 的可点组件会保留首次
                // 传入的 lambda，而 onClick 里 `when (membership)` 读的就是那个旧值。membership 是
                // enum（每个分支是单例，相等性稳定），换 key 就等于在状态真的变化时强制换掉按钮节点。
                key(membership) {
                    MembershipToggle(
                        membership = membership,
                        onAdd = onAdd,
                        onRemove = onRemove,
                        onRetry = onRetry,
                    )
                }
            }
        },
    )
}
