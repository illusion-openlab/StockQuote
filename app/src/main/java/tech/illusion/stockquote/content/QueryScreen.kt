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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.illusion.stockquote.data.StockDataRepository
import tech.illusion.stockquote.data.StockRepository
import tech.illusion.stockquote.data.StockSuggestion
import tech.illusion.stockquote.data.changePercent
import tech.illusion.stockquote.data.price

/** 输入停顿多久才触发一次全市场请求，见契约增量 v4。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Query subview: mode switch inside the same WindowContainer, not a separate window. */
@Composable
fun QueryScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedCode by remember { mutableStateOf<String?>(null) }
    var isSearchingRemote by remember { mutableStateOf(false) }
    var remoteFailed by remember { mutableStateOf(false) }
    var remoteResults by remember { mutableStateOf<List<StockSuggestion>>(emptyList()) }
    var addingCode by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val localResults = remember(query) {
        if (query.isBlank()) {
            StockRepository.universe
        } else {
            StockRepository.universe.filter { it.name.contains(query) || it.code.contains(query) }
        }
    }

    // 本地 12 只的过滤（上面的 localResults）不等这条协程——防抖+全市场搜索只负责补充"12 只之外"的结果，
    // 失败也不影响本地结果，见契约增量 v4。
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

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Link(onClick = onBack) { Text("← 返回自选股") }
        Spacer(modifier = Modifier.height(12.dp))
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("查股票名称或代码，例如 600519 / 茅台") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isSearchingRemote) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator()
                Text(
                    text = "搜索全市场中…",
                    style = PicoTheme.typography.labelSmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
        } else if (remoteFailed) {
            Text(
                text = "全市场搜索暂时不可用，仅显示本地结果",
                style = PicoTheme.typography.labelSmall,
                color = PicoTheme.colorScheme.labelTertiary,
            )
        } else {
            Text(
                text = "点击列表中的股票以选中，选中后会出现\"加入自选\"按钮",
                style = PicoTheme.typography.labelSmall,
                color = PicoTheme.colorScheme.labelTertiary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        if (localResults.isEmpty() && remoteResults.isEmpty() && !isSearchingRemote && query.isNotBlank()) {
            Text(
                text = "没有匹配的股票",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelTertiary,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = localResults, key = { it.code }) { stock ->
                    val inWatchlist = StockRepository.isInWatchlist(stock.code)
                    val selected = selectedCode == stock.code
                    val change = stock.changePercent()
                    val trendColor = if (change >= 0) StockUpColor else StockDownColor

                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !inWatchlist) {
                            selectedCode = if (selected) null else stock.code
                        },
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "%.2f".format(stock.price()),
                                    style = PicoTheme.typography.bodyLarge,
                                    color = PicoTheme.colorScheme.labelPrimary,
                                )
                                Sparkline(
                                    hist = stock.hist,
                                    color = trendColor,
                                    modifier = Modifier.size(width = 56.dp, height = 24.dp),
                                )
                                when {
                                    inWatchlist -> Chip(label = { Text("已自选") }, onClick = null)
                                    selected -> Button(onClick = { StockRepository.addFromUniverse(stock.code) }) {
                                        Text("+ 加入自选")
                                    }
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }

                if (remoteResults.isNotEmpty()) {
                    item(key = "remote-section-label") {
                        Text(
                            text = "全市场结果",
                            style = PicoTheme.typography.labelSmall,
                            color = PicoTheme.colorScheme.labelTertiary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(items = remoteResults, key = { "remote-${it.code}" }) { suggestion ->
                        val inWatchlist = StockRepository.isInWatchlist(suggestion.code)
                        val selected = selectedCode == suggestion.code
                        val adding = addingCode == suggestion.code

                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !adding && !inWatchlist) {
                                selectedCode = if (selected) null else suggestion.code
                            },
                            headlineContent = {
                                Text(
                                    text = suggestion.name,
                                    style = PicoTheme.typography.bodyLarge,
                                    color = PicoTheme.colorScheme.labelPrimary,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = suggestion.code,
                                    style = PicoTheme.typography.bodySmall,
                                    color = PicoTheme.colorScheme.labelTertiary,
                                )
                            },
                            trailingContent = {
                                when {
                                    // 必须排在最前面：加完之后 adding/selected 都会被清空，不排在最前面就会
                                    // 落回下面的"未追踪"分支——这正是用户报告的那个 bug。
                                    inWatchlist -> Chip(label = { Text("已自选") }, onClick = null)
                                    adding -> CircularProgressIndicator()
                                    selected -> Button(onClick = {
                                        addingCode = suggestion.code
                                        scope.launch {
                                            val ok = StockRepository.addRemote(suggestion.code, suggestion.name)
                                            addingCode = null
                                            if (ok) selectedCode = null
                                        }
                                    }) { Text("+ 加入自选") }
                                    else -> Chip(label = { Text("未追踪") }, onClick = null)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
