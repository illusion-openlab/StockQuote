package tech.illusion.stockquote.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
 * membership/star/order is separately real: persisted through [WatchlistLocalStore].
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
    private val starredCodes = setOf("600519", "300750", "002594")

    private var _universe by mutableStateOf(universeSeed)

    /** Full market universe available in the query screen (includes stocks already in the watchlist). */
    val universe: List<Stock>
        get() = _universe

    private var orderCounter = 0

    private fun defaultWatchlist(): List<Stock> =
        _universe.filter { it.code in starterCodes }
            .map { it.copy(star = it.code in starredCodes, orderIndex = orderCounter++) }

    private var _watchlist by mutableStateOf(defaultWatchlist())

    /** Read inside a `@Composable` to recompose automatically on any repository mutation. */
    val watchlist: List<Stock>
        get() = _watchlist

    /** Call once from `Application.onCreate` — loads any previously saved watchlist from disk. */
    fun init(context: Context) {
        WatchlistLocalStore.init(context)
        val savedCodes = WatchlistLocalStore.loadCodesInOrder() ?: return // first run: keep the default seed
        val savedStars = WatchlistLocalStore.loadStarredCodes()
        orderCounter = 0
        _watchlist = savedCodes.mapNotNull { code -> _universe.find { it.code == code } }
            .map { it.copy(star = it.code in savedStars, orderIndex = orderCounter++) }
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
        WatchlistLocalStore.save(
            codesInOrder = _watchlist.sortedBy { it.orderIndex }.map { it.code },
            starredCodes = _watchlist.filter { it.star }.map { it.code }.toSet(),
        )
    }

    fun isInWatchlist(code: String): Boolean = _watchlist.any { it.code == code }

    fun toggleStar(code: String) {
        _watchlist = _watchlist.map { if (it.code == code) it.copy(star = !it.star) else it }
        persist()
    }

    fun remove(code: String) {
        _watchlist = _watchlist.filterNot { it.code == code }
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
        _watchlist = _watchlist + item.copy(star = false, orderIndex = orderCounter++)
        persist()
    }

    /**
     * 把一条全市场搜索命中（[StockSuggestion]，只有名称+代码）加入自选。**必须先抓到真实历史/报价才
     * 入 `_universe`/`_watchlist`**——不接受"先加个空壳等后台补数据"：[Stock.price]/[Stock.changePercent]
     * 全项目到处假设 `hist` 至少 2 个点，`WatchlistRow` 一进 composition 就会调用它们，空壳会立刻崩溃。
     *
     * 抓取失败返回 `false`，不做任何改动；调用方（`QueryScreen`）自己决定怎么提示用户重试。
     */
    suspend fun addRemote(code: String, name: String): Boolean {
        if (isInWatchlist(code)) return true
        _universe.find { it.code == code }?.let { existing ->
            _watchlist = _watchlist + existing.copy(star = false, orderIndex = orderCounter++)
            persist()
            return true
        }
        val remote = StockDataRepository.fetchHistory(code, days = 500) ?: return false
        if (remote.closes.size < 2) return false
        val stock = Stock(code = code, name = name, hist = remote.closes, candles = remote.candles, snapshot = remote.snapshot)
        _universe = _universe + stock
        _watchlist = _watchlist + stock.copy(star = false, orderIndex = orderCounter++)
        persist()
        return true
    }
}
