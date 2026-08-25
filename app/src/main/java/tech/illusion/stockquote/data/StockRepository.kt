package tech.illusion.stockquote.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Market data (`universe`/`watchlist` prices) comes from [StockDataRepository] (Eastmoney/Sina, see
 * design doc §6/§7); the literals below are only the seed shown before the first successful fetch,
 * or the permanent fallback for any stock neither free source ever returns. Watchlist
 * membership/order is separately real: persisted through [WatchlistLocalStore].
 *
 * Kept as a process-wide singleton backed by Compose snapshot state (not a Flow — no extra
 * coroutines dependency needed) so the detail WindowContainer (separate Activity, same process)
 * observes the same state instead of sharing object references.
 */
/** 首次全量历史抓取的并发批量与批间停顿，见 [StockRepository.refreshAll] 的说明。 */
private const val HISTORY_BATCH_SIZE = 3
private const val HISTORY_BATCH_DELAY_MS = 400L

object StockRepository {

    private val universeSeed = listOf(
        Stock("600519", "贵州茅台", listOf(1608.0, 1652.0, 1633.0, 1668.0, 1690.0, 1698.0, 1705.0, 1735.0, 1745.0, 1712.0)),
        Stock("600036", "招商银行", listOf(36.1, 35.8, 35.2, 35.6, 35.0, 34.6, 35.1, 34.9, 35.2, 34.82)),
        Stock("300750", "宁德时代", listOf(152.0, 155.0, 158.0, 160.0, 157.0, 162.0, 165.0, 163.0, 166.0, 168.9)),
        Stock("601318", "中国平安", listOf(47.2, 47.0, 46.8, 46.5, 46.9, 46.6, 46.3, 46.5, 46.2, 46.10)),
        Stock("000858", "五粮液", listOf(124.0, 125.0, 126.0, 124.5, 126.8, 127.2, 126.5, 128.0, 127.6, 128.35)),
        Stock("002594", "比亚迪", listOf(220.0, 224.0, 226.0, 222.0, 228.0, 231.0, 229.0, 234.0, 236.0, 238.6)),
        Stock("000333", "美的集团", listOf(66.5, 67.0, 66.8, 67.4, 67.9, 67.6, 68.1, 67.8, 68.4, 68.20)),
        Stock("000725", "京东方A", listOf(4.02, 4.05, 4.00, 4.08, 4.10, 4.06, 4.09, 4.13, 4.11, 4.12)),
        Stock("601888", "中国中免", listOf(82.0, 80.5, 79.8, 81.2, 79.5, 78.9, 79.3, 78.1, 79.0, 78.60)),
        Stock("601899", "紫金矿业", listOf(16.9, 17.1, 17.0, 17.3, 17.5, 17.4, 17.6, 17.7, 17.65, 17.85)),
        Stock("002415", "海康威视", listOf(30.2, 30.5, 30.3, 30.8, 31.0, 30.9, 31.2, 31.1, 31.3, 31.40)),
        Stock("600276", "恒瑞医药", listOf(50.8, 51.0, 50.6, 51.3, 51.6, 51.4, 51.8, 52.0, 52.1, 52.30)),
    )

    private val starterCodes = setOf("600519", "600036", "300750", "601318", "000858", "002594")

    private var _universe by mutableStateOf(universeSeed)

    /** Full market universe available in the query screen (includes stocks already in the watchlist). */
    val universe: List<Stock>
        get() = _universe

    private var orderCounter = 0

    private fun defaultWatchlist(): List<Stock> =
        _universe.filter { it.code in starterCodes }
            .map { it.copy(orderIndex = orderCounter++) }

    private var _watchlist by mutableStateOf(defaultWatchlist())

    /** Read inside a `@Composable` to recompose automatically on any repository mutation. */
    val watchlist: List<Stock>
        get() = _watchlist

    /** 一条还没能重新抓到数据的持久化自选股：冷启动时在本地库里查不到，需要异步补一次真实抓取。 */
    private data class PendingRestoreEntry(val code: String, val name: String, val orderIndex: Int)

    /**
     * 冷启动时持久化列表里"本地库查不到"的那些自选股（也就是全市场搜索加进来的）——留到
     * [resolvePendingWatchlist] 异步补抓。**[persist] 必须把它们也写回存储**，否则应用生命周期内
     * 只要触发一次跟它们无关的持久化（移除别的股票、置顶、加别的自选……），这些还没解析出来的
     * code 就会从这次写入里凭空消失——它们明明还没来得及重试就被判了死刑。
     */
    private var _pendingRestore: List<PendingRestoreEntry> = emptyList()

    /** Call once from `Application.onCreate` — loads any previously saved watchlist from disk. */
    fun init(context: Context) {
        WatchlistLocalStore.init(context)
        val saved = WatchlistLocalStore.loadWatchlistEntries() ?: return // first run: keep the default seed
        val resolvedNow = mutableListOf<Stock>()
        val pending = mutableListOf<PendingRestoreEntry>()
        saved.forEachIndexed { index, (code, name) ->
            val seedMatch = _universe.find { it.code == code }
            if (seedMatch != null) {
                resolvedNow += seedMatch.copy(orderIndex = index)
            } else {
                pending += PendingRestoreEntry(code, name, index)
            }
        }
        // 未来新加的自选股（addFromUniverse/addRemote）的 orderIndex 必须从"比持久化列表里最大的
        // 位置还大"开始，不管那个位置对应的条目这次是同步解析出来了还是还在 pending 里——否则新加的
        // 一条可能撞上一个还没解析出来的 pending orderIndex，排序就乱了。
        orderCounter = saved.size
        _watchlist = resolvedNow
        _pendingRestore = pending
    }

    /**
     * 冷启动后，把上次持久化的自选股里"本地库查不到"的那些（也就是全市场搜索加进来的，不在
     * `universeSeed` 那 12 只里）真正抓一次数据补回 `_watchlist`——修复"搜索加的股票重启后消失"。
     *
     * 只在这次启动尝试一次：某个 code 这次抓失败（网络还没起来、这个 app 用的免费接口出了名地会限流，
     * 见 AGENTS.md），不会在这次会话里反复重试，但也**不会**从持久化里被丢掉——留到下次启动再试。
     * 真正加过自选、只是这次刚好抓失败的股票，不应该因为一次瞬时失败就永久从自选列表消失。
     *
     * 调用方（`WatchlistHost` 启动时的 `LaunchedEffect`）不需要等这个函数跑完才展示界面——本地库
     * 能同步解析的那些已经在 `init()` 里直接进了 `_watchlist`，这个函数只是把剩下那批异步补上。
     */
    suspend fun resolvePendingWatchlist() {
        if (_pendingRestore.isEmpty()) return
        val stillPending = mutableListOf<PendingRestoreEntry>()
        for (entry in _pendingRestore) {
            // 已经被别的路径解决了——比如用户没等这个函数跑完，就自己在搜索里把同一只股票重新加了一次
            // 回来（addFromUniverse/addRemote 都会把它从 _pendingRestore 里摘掉，但这个 for 循环是在
            // 循环开始那一刻就拿到了 _pendingRestore 的旧快照，摘除动作影响不到这次正在跑的迭代，
            // 所以还是会走到这一条）。不跳过的话会把同一个 code 第二次 append 进 _watchlist，
            // 造出重复 key，撞上 LazyColumn 直接崩——这是 2026-08-25 复核抓到的真实 bug。
            if (isInWatchlist(entry.code)) continue
            if (ensurePreviewData(entry.code, entry.name)) {
                _universe.find { it.code == entry.code }?.let { stock ->
                    _watchlist = (_watchlist + stock.copy(orderIndex = entry.orderIndex)).sortedBy { it.orderIndex }
                }
            } else {
                stillPending += entry
            }
        }
        _pendingRestore = stillPending
        persist()
    }

    /**
     * `code|period` → 该周期的**原生** K 线（[SinaSource.fetchBars]）。
     *
     * 按需抓、只抓一次：用户真的把详情页切到那个周期时才发一个请求，之后进这个缓存。抓不到就什么都不放，
     * 详情页自己退回 [aggregateCandles] 的本地合并——所以失败不需要任何加载态或错误提示。
     *
     * 为什么不在 [refreshAll] 里一次性把三个周期都抓了：那是 12 只 × 3 个周期 = 36 个请求，而这些是免费
     * 无 SLA 按 IP 限速的接口（同一天已经被限过一次，见 refreshAll 的注释），绝大多数还根本不会被看到。
     */
    private val _periodBars = mutableStateMapOf<String, List<Candle>>()

    /** 已发出但还没回来的 `code|period`，避免同一个组合被重复请求（切来切去会反复触发 LaunchedEffect）。 */
    private val periodInFlight = mutableSetOf<String>()

    private fun periodKey(code: String, period: KPeriod) = "$code|${period.name}"

    /** 原生周期 K 线；`null` = 还没抓到（调用方退回本地合并）。 */
    fun periodBars(code: String, period: KPeriod): List<Candle>? = _periodBars[periodKey(code, period)]

    /**
     * 确保 `code` 的 `period` 周期 K 线已抓取。已有缓存或正在飞行中就直接返回，可以放心地在
     * `LaunchedEffect(code, period)` 里无脑调用。
     *
     * 日线不走这里——日线是 [refreshAll] 眼下就在维护的那份，而且 [mergeLatestPrice] 会把实时报价并进
     * 末根；再抓一份原生日线反而会得到一根盘中不更新的末根。
     */
    suspend fun ensurePeriodHistory(code: String, period: KPeriod) {
        if (period == KPeriod.Daily) return
        val key = periodKey(code, period)
        if (_periodBars.containsKey(key) || !periodInFlight.add(key)) return
        try {
            val bars = withContext(Dispatchers.IO) {
                SinaSource.fetchBars(code, period, period.fetchDepth)?.candles
            }
            if (!bars.isNullOrEmpty()) _periodBars[key] = bars
        } finally {
            periodInFlight.remove(key)
        }
    }

    private fun applyHistory(
        code: String,
        hist: List<Double>,
        candles: List<Candle>,
        snapshot: QuoteSnapshot?,
    ) {
        // A source that reports no snapshot must not erase one an earlier fetch did report.
        fun Stock.withHistory() = copy(hist = hist, candles = candles, snapshot = snapshot ?: this.snapshot)
        _universe = _universe.map { if (it.code == code) it.withHistory() else it }
        _watchlist = _watchlist.map { if (it.code == code) it.withHistory() else it }
    }

    /**
     * One-shot full-history refresh for every stock in [universe] (multi-day close series from
     * [StockDataRepository], real OHLC-derived candles downstream). Call once on app start; a
     * per-stock failure keeps that stock's previous (seed or last-successful) `hist` untouched —
     * these are free, no-SLA endpoints (design doc §6/§7), so partial failure is expected, not fatal.
     */
    // 500 而不是原来的 230：日 K 的"近1年"档位本身就要 220 根，只多 10 根就没有横向拖拽翻历史的余量了。
    // 500 根 ≈ 2 年，单只约 55KB、12 只一次 ~660KB，而且这是启动时的一次性抓取（盘中轮询走的是
    // refreshWatchlistQuotes，只有报价快照）。请求**条数**不变，所以不额外增加限速风险。
    suspend fun refreshAll(days: Int = 500) {
        // 小批量而不是一次并发全部：这些是免费无 SLA 的接口，按 IP 限速。2026-08-11 实测到过同一份代码
        // 有的轮次拿回完整多日历史、有的轮次只拿到 quote 兜底的 2 个点，而一次性并发 12 个大响应请求
        // 正是最可能的触发原因（同一天用 curl 快速连发也确实把出口 IP 打进过黑名单）。
        // 代价只有批间的几百毫秒，换来的是"图上到底有没有 K 线"这件事的确定性。
        _universe.map { it.code }.chunked(HISTORY_BATCH_SIZE).forEachIndexed { index, batch ->
            if (index > 0) delay(HISTORY_BATCH_DELAY_MS)
            coroutineScope {
                batch.map { code -> async { code to StockDataRepository.fetchHistory(code, days) } }
                    .awaitAll()
                    .forEach { (code, remote) ->
                        remote?.let { applyHistory(code, it.closes, it.candles, it.snapshot) }
                    }
            }
        }
    }

    /**
     * Lightweight quote-only tick for the currently-watched codes (design doc §6/§7: quote snapshots
     * poll every 3–5s, unlike the multi-minute-scale full history refresh). Replaces only the latest
     * point in `hist` so the rest of the chart history stays intact between full refreshes.
     */
    suspend fun refreshWatchlistQuotes() {
        coroutineScope {
            _watchlist.map { it.code }
                .map { code -> async { code to StockDataRepository.fetchQuote(code) } }
                .awaitAll()
                .forEach { (code, remote) ->
                    val latest = remote?.closes?.lastOrNull() ?: return@forEach
                    val existing = _universe.find { it.code == code } ?: return@forEach
                    if (existing.hist.size < 2) return@forEach
                    mergeLatestPrice(code, existing, latest, remote.snapshot)
                }
        }
    }

    /**
     * 把一次实时报价合并进历史。**必须按日期判断，不能无条件"替换最后一点"**：
     *
     * 不同源的历史末尾落在不同的一天——东财 kline 会带上今天这根正在形成的 bar，而新浪日线（现在的主源）
     * 停在**上一个交易日**。对新浪那种形状如果还去替换最后一点，就会把昨天的真实收盘价悄悄覆盖掉、并且
     * 把它标成今天的日期：既丢了一个真实交易日，又让 X 轴日期和数据错位。
     *
     * 所以：末尾就是今天 → 更新那根；末尾是上一个交易日且快照给了今天真实的开/高/低 → **新增**一根
     * （自限的，加完之后末尾日期就是今天，下一次轮询走更新分支）；两者都不成立（只有 quote 兜底、没有
     * 真实 K 线）→ 保持旧语义只推 hist 的最后一点，不凭空造 bar。
     */
    private fun mergeLatestPrice(
        code: String,
        existing: Stock,
        latest: Double,
        snapshot: QuoteSnapshot?,
    ) {
        val bars = existing.candles
        val lastBar = bars.lastOrNull()
        // 必须走 MarketClock（上海时区），不能用 LocalDate.now()——设备时区落后上海时会整天判错，
        // 导致同一个交易日被追加成两根 bar。真机（America/New_York）实测过，原因见 MarketClock 的注释。
        val today = MarketClock.todayString()
        val snapshotOpen = snapshot?.open

        when {
            lastBar != null && lastBar.date == today -> {
                val updated = bars.dropLast(1) + lastBar.copy(
                    close = latest,
                    high = maxOf(lastBar.high, latest),
                    low = minOf(lastBar.low, latest),
                )
                applyHistory(code, updated.map { it.close }, updated, snapshot)
            }
            lastBar != null && snapshotOpen != null -> {
                val todayBar = Candle(
                    open = snapshotOpen,
                    high = maxOf(snapshot.high ?: latest, latest),
                    low = minOf(snapshot.low ?: latest, latest),
                    close = latest,
                    volume = snapshot.volumeLots ?: 0.0,
                    amount = snapshot.amountYuan ?: 0.0,
                    date = today,
                )
                val updated = bars + todayBar
                applyHistory(code, updated.map { it.close }, updated, snapshot)
            }
            else -> applyHistory(code, existing.hist.dropLast(1) + latest, bars, snapshot)
        }
    }

    private fun persist() {
        val liveEntries = _watchlist.map { it.orderIndex to (it.code to it.name) }
        val pendingEntries = _pendingRestore.map { it.orderIndex to (it.code to it.name) }
        val merged = (liveEntries + pendingEntries).sortedBy { it.first }.map { it.second }
        WatchlistLocalStore.save(merged)
    }

    fun isInWatchlist(code: String): Boolean = _watchlist.any { it.code == code }

    /**
     * 正在飞 / 已失败的加自选操作，按 code 独立（契约 v5 §5.2）。
     *
     * `Added` **不进这张表**——它由 `_watchlist` 推导（见 [resolveMembership]）。把 Added 也塞进来
     * 就会出现两个真相，正是旧代码 `QueryScreen.addingCode` 与 `isInWatchlist` 不同源的老毛病。
     */
    private val _opStates = mutableStateMapOf<String, Membership>()

    /** 搜索列表和自选列表都读这个函数，不再各算各的。 */
    fun membershipOf(code: String): Membership = resolveMembership(isInWatchlist(code), _opStates[code])

    /**
     * 退出搜索态时调用：清掉所有 Adding/Failed 残留（契约 v5 §5.3 的第 ③ 条清除时机）。
     * 不清的话下次进搜索会看到上次留下的红叹号。
     */
    fun clearOpStates() {
        _opStates.clear()
    }

    /**
     * 契约 v5 §5.3 第 ② 条：搜索词变化导致某个 code 不再出现在结果里时，调用方用这个方法把它的
     * Adding/Failed 残留一并清掉，否则用户改回同一个词时会看到过期状态。
     */
    fun pruneOpStates(visibleCodes: Set<String>) {
        val stale = _opStates.keys.filterNot { it in visibleCodes }
        stale.forEach { _opStates.remove(it) }
    }

    /**
     * 单飞锁：同一个 code 同一时间只允许一次真实抓取在跑，不管这次抓取是"预览"触发的还是"加自选"
     * 触发的——两条独立入口（[ensurePreviewData] / [addRemote]）如果各自发起一次网络请求，都会往
     * `_universe` 追加一条同 code 的 Stock，撞上 LazyColumn 的 `key = { it.code }` 直接崩。
     * 跟 [_opStates] 分开：`_opStates` 只管 [MembershipToggle] 要显示什么（提交中/失败），这张锁
     * 只管"要不要真的发一次网络请求"——预览触发抓取时不应该让加自选按钮跟着转圈，两件事语义不同。
     */
    private val _fetchInFlight = mutableStateSetOf<String>()

    /** 详情区据此判断：是不是正在为这只股票拉预览数据（还没抓完，不是真的"未找到"）。 */
    fun isPreviewLoading(code: String): Boolean = code in _fetchInFlight

    /**
     * 只把 code 的真实数据抓进 `_universe`，不动 `_watchlist`、不动 `_opStates`——用于"预览一只还没
     * 加自选的远端股票详情"（2026-08-25：用户反馈搜索命中的远端股票点了只显示"未找到股票数据"，
     * 要等加了自选才有数据；这条补上"预览也能看到真实详情"，不用先加自选）。
     *
     * 已经在 `_universe` 里（本地库自带的、或者之前预览/加自选抓过的）直接返回 `true`，不重复抓。
     * 并发调用同一个 code 也不会抓两次：`_fetchInFlight.add(code)` 拿不到锁就直接返回 `false`——
     * 调用方（[SearchPane]）本来就是"选中即触发"，同一个 code 短时间内被选中两次是可能的（比如用户
     * 手滑点了两下），这条保证不会因此发出两次网络请求。
     *
     * 也被 [addRemote] 复用来做真正的抓取：两个入口必须共享同一把 `_fetchInFlight` 锁，见那边的
     * 注释。
     */
    suspend fun ensurePreviewData(code: String, name: String): Boolean {
        if (_universe.any { it.code == code }) return true
        if (!_fetchInFlight.add(code)) return false
        try {
            val remote = StockDataRepository.fetchHistory(code, days = 500)
            if (remote == null || remote.closes.size < 2) return false
            val resolvedName = name.ifBlank { remote.name?.takeIf { it.isNotBlank() } ?: code }
            _universe = _universe + Stock(
                code = code,
                name = resolvedName,
                hist = remote.closes,
                candles = remote.candles,
                snapshot = remote.snapshot,
            )
            return true
        } finally {
            _fetchInFlight.remove(code)
        }
    }

    /**
     * 移除自选，返回撤销所需的快照（没这只股票则返回 null）。
     *
     * 返回快照而不是 Unit：契约 v5 §6 用"即时移除 + 5 秒撤销条"取代了确认弹窗，撤销要能按**原位**
     * 插回，所以移除的这一刻就得把 orderIndex 一起带出去——之后 `_watchlist` 里已经查不到它了。
     */
    fun remove(code: String): RemovedSnapshot? {
        val victim = _watchlist.find { it.code == code } ?: return null
        _watchlist = _watchlist.filterNot { it.code == code }
        persist()
        return RemovedSnapshot(stock = victim, orderIndex = victim.orderIndex)
    }

    /** 撤销一次 [remove]：按原 orderIndex 插回。重复调用是幂等的。 */
    fun restore(snapshot: RemovedSnapshot) {
        _watchlist = restoreRemoved(_watchlist, snapshot)
        persist()
    }

    /**
     * 置顶：把 `orderIndex` 压到当前最小值 − 1（设计契约 v2 §3 第 1 条）。
     *
     * **调用方还必须清掉激活中的表头排序**（第 2 条）：如果用户正按价格/涨跌幅排序，置顶只改默认顺序
     * 不会带来任何肉眼可见的位置变化，会显得"点了没反应"。这条职责留在 UI 侧是因为排序状态是 UI 状态，
     * Repository 不该知道它。
     */
    fun moveToTop(code: String) {
        if (_watchlist.none { it.code == code }) return
        val minOrder = _watchlist.minOfOrNull { it.orderIndex } ?: 0
        _watchlist = _watchlist.map { if (it.code == code) it.copy(orderIndex = minOrder - 1) else it }
        persist()
    }

    fun addFromUniverse(code: String) {
        if (isInWatchlist(code)) return
        val item = _universe.find { it.code == code } ?: return
        _watchlist = _watchlist + item.copy(orderIndex = orderCounter++)
        // 这只股票有可能同时还挂在 _pendingRestore 里（比如冷启动异步补抓还没轮到它，用户等不及自己
        // 手动加了回来）——摘掉它，否则 resolvePendingWatchlist 稍后遍历到同一个 code 会再插一次，
        // 造出重复 key（2026-08-25 复核抓到的真实 bug）。
        _pendingRestore = _pendingRestore.filterNot { it.code == code }
        persist()
    }

    /**
     * 把一条全市场搜索命中（[StockSuggestion]，只有名称+代码）加入自选。**必须先抓到真实历史/报价才
     * 入 `_universe`/`_watchlist`**——不接受"先加个空壳等后台补数据"：[Stock.price]/[Stock.changePercent]
     * 全项目到处假设 `hist` 至少 2 个点，`WatchlistRow` 一进 composition 就会调用它们，空壳会立刻崩溃。
     *
     * 抓取失败返回 `false`，并把 `_opStates[code]` 置为 [Membership.Failed]（`_universe`/`_watchlist`
     * 不动）——行尾那颗 [tech.illusion.stockquote.content.MembershipToggle] 靠这个状态变成红色 `!`，
     * 再点一次就是重试。调用方是 `SearchPane`（旧的 `QueryScreen` 已经不存在了）。
     *
     * 并发重复点击也返回 `false`：见函数开头那条 `Adding` 守卫。
     */
    suspend fun addRemote(code: String, name: String): Boolean {
        // 双击竞态守卫：见 ensurePreviewData 的锁——那把锁挡的是"同一个 code 别发两次网络请求"，
        // 这里额外挡的是"同一个 code 的加自选按钮别被连点两次都走到追加 _watchlist 那一步"。
        // 注意只挡 Adding，不挡 Failed：Failed 状态下再点是用户主动重试，必须放行。
        if (_opStates[code] == Membership.Adding) return false
        if (isInWatchlist(code)) return true
        _universe.find { it.code == code }?.let { existing ->
            _watchlist = _watchlist + existing.copy(orderIndex = orderCounter++)
            _opStates.remove(code)
            // 同一个 code 有可能还挂在 _pendingRestore 里——见 addFromUniverse 同一处的注释，原理一样。
            _pendingRestore = _pendingRestore.filterNot { it.code == code }
            persist()
            return true
        }
        _opStates[code] = Membership.Adding
        // 抓取本体复用 ensurePreviewData（2026-08-25 新增，供"预览未自选的远端股票详情"使用）——
        // 两个入口都要往 _universe 追加同一个 code，必须走同一把锁（_fetchInFlight），否则"选中预览"
        // 和"点加自选"并发时会各发一次请求、往 _universe 塞两条同 code 的 Stock，撞上 LazyColumn 的
        // key 崩溃。如果这次调用刚好撞上一次已经在飞的抓取(无论是预览触发的还是别的加自选触发的),
        // ensurePreviewData 会直接返回 false——这里就老实报 Failed，用户能再点一次重试（这时大概率
        // 前一次抓取已经完成、universe 里已经有数据了，重试会走上面"已在 universe"的快路径直接成功）。
        if (!ensurePreviewData(code, name)) {
            _opStates[code] = Membership.Failed
            return false
        }
        val stock = _universe.find { it.code == code } ?: run {
            _opStates[code] = Membership.Failed
            return false
        }
        _watchlist = _watchlist + stock.copy(orderIndex = orderCounter++)
        _opStates.remove(code)
        _pendingRestore = _pendingRestore.filterNot { it.code == code }
        persist()
        return true
    }
}
