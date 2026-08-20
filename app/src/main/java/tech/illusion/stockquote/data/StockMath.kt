package tech.illusion.stockquote.data

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One daily bar. `volume` is in 手 (lots of 100 shares) when it came from the Eastmoney kline
 * endpoint — verified against that endpoint's own `amount` column. `date` is `yyyy-MM-dd`, or blank
 * for synthesized bars (see [buildCandles]).
 */
data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val amount: Double = 0.0,
    val date: String = "",
)

/** True for bars that carry a real trading date — i.e. came from a source, not from [buildCandles]. */
fun Candle.isReal(): Boolean = date.isNotBlank()

/**
 * Synthesizes OHLCV from a close-price series — same derivation as the prototype's `buildOHLC()`.
 * Only for the quote-only fallback path where no real OHLCV exists: the `volume` it invents is a
 * shape, not a measurement, so it must never be surfaced to the user as a number.
 */
fun buildCandles(closes: List<Double>): List<Candle> =
    closes.mapIndexed { index, close ->
        val previous = if (index == 0) close else closes[index - 1]
        val open = previous
        val volume = abs(close - previous) * 40 + 30 + (index % 5) * 6
        val high = maxOf(open, close) + abs(close - open) * 0.35 + 1.2
        val low = minOf(open, close) - abs(close - open) * 0.35 - 1.2
        Candle(open, high, low, close, volume)
    }

/**
 * K 线周期。
 *
 * **周线/月线优先走新浪的原生接口**（[SinaSource.fetchBars]），[aggregateCandles] 的本地合并是它没抓到
 * 之前的即时兜底。原因是深度：本地合并的输入只有那 500 根日线，撑死给 100 根周线 / 24 根月线，而月线图
 * 至少要几十根才有意义；原生接口一次就能给 120 根月线（回溯到 2016 年）。
 *
 * `sinaScale` 是新浪 kline 的 `scale` 参数，单位**分钟**：240=日线、1200=周线（5×240）、7200=月线。
 * 实测（2026-08-11, sh600519）：周线 260 根回溯到 2021-07-16、月线 120 根回溯到 2016-09-30，两者
 * **末根都是今天**（本周/本月在形成中的那根，服务端盘中就在更新），所以不会有"末根停在上周"的滞后。
 * 日期口径也和 [aggregateCandles] 一致——周线落在周五、月线落在月末交易日——两条路可以互为兜底。
 *
 * `fetchDepth` 是该周期一次抓多少根：要覆盖最长的区间档位，再留出横向拖拽翻历史的余量。
 *
 * 枚举顺序必须和 UI 的周期 Tab 顺序（日 K / 周 K / 月 K）一致，`DetailScreen` 直接用下标取。
 */
enum class KPeriod(val label: String, val sinaScale: Int, val fetchDepth: Int) {
    Daily("日 K", 240, 500),
    Weekly("周 K", 1200, 260),
    Monthly("月 K", 7200, 120),
}

/**
 * 把日线聚合成周线/月线，通达信/同花顺口径：
 * **开**=区间第一根的开盘，**收**=最后一根的收盘，**高/低**=区间极值，**量/额**=求和，
 * **日期**=区间内最后一个交易日（所以本周/本月还没走完时，末尾那根就是一根正在形成的 bar，和行情
 * 软件的表现一致）。
 *
 * 分组只用日期里天然带的信息，不查节假日表：周线按 ISO 周（周一到周日，跨年那周用 week-based-year，
 * 否则 12 月底那几天会被归到第 1 周去和一月的数据混在一起），月线按 `yyyy-MM`。
 *
 * 入参必须按日期升序（各源的 kline 都是升序，[StockRepository] 也一路保持）。
 *
 * [buildCandles] 造的合成蜡烛没有日期，无法分组——这种情况**整段原样返回**，宁可周期切换没效果，
 * 也不按数组下标瞎切出"每 5 根算一周"的假周线。
 */
fun aggregateCandles(daily: List<Candle>, period: KPeriod): List<Candle> {
    if (period == KPeriod.Daily || daily.size < 2) return daily
    val buckets = LinkedHashMap<String, MutableList<Candle>>()
    for (bar in daily) {
        val key = periodKey(bar, period) ?: return daily
        buckets.getOrPut(key) { mutableListOf() } += bar
    }
    return buckets.values.map { group ->
        Candle(
            open = group.first().open,
            high = group.maxOf { it.high },
            low = group.minOf { it.low },
            close = group.last().close,
            volume = group.sumOf { it.volume },
            amount = group.sumOf { it.amount },
            date = group.last().date,
        )
    }
}

/** `null` 表示这根 bar 没有可用日期（合成蜡烛），调用方据此放弃聚合。 */
private fun periodKey(bar: Candle, period: KPeriod): String? {
    val date = runCatching { LocalDate.parse(bar.date) }.getOrNull() ?: return null
    return when (period) {
        KPeriod.Daily -> bar.date
        KPeriod.Weekly -> "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        KPeriod.Monthly -> "%04d-%02d".format(date.year, date.monthValue)
    }
}

/** `null` for indices before the first full window, matching the prototype's `sma()`. */
fun simpleMovingAverage(values: List<Double>, period: Int): List<Double?> =
    values.indices.map { i ->
        if (i < period - 1) null else values.subList(i - period + 1, i + 1).average()
    }

fun exponentialMovingAverage(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return values
    val k = 2.0 / (period + 1)
    var previous = values.first()
    return values.mapIndexed { index, value ->
        previous = if (index == 0) value else value * k + previous * (1 - k)
        previous
    }
}

data class MacdResult(val diff: List<Double>, val signal: List<Double>, val histogram: List<Double>)

/**
 * Real MACD(12,26,9)：`DIFF = EMA12 − EMA26`、`DEA = EMA(DIFF, 9)`、
 * **`柱 = 2 × (DIFF − DEA)`**。
 *
 * ⚠️ 那个 **×2 不能省**。通达信/同花顺一系的 A 股软件画的 MACD 柱是 `2*(DIFF-DEA)`，而西式写法常常直接用
 * `DIFF-DEA`。本项目的设计源就是同花顺截图（契约 v3 §0），用户 2026-08-11 明确确认按国内惯例，所以取 ×2。
 * 早先的实现漏了它，柱高只有应有的一半——当时算出来 0.86 而按惯例应是 1.72（用当天同一份数据在外部独立
 * 重算对过账）。改这个数会让柱子整体变高一倍，是**刻意的**，不要当成 bug 改回去。
 */
fun computeMacd(closes: List<Double>): MacdResult {
    val ema12 = exponentialMovingAverage(closes, 12)
    val ema26 = exponentialMovingAverage(closes, 26)
    val diff = ema12.indices.map { ema12[it] - ema26[it] }
    val signal = exponentialMovingAverage(diff, 9)
    val histogram = diff.indices.map { 2 * (diff[it] - signal[it]) }
    return MacdResult(diff, signal, histogram)
}

data class KdjResult(val k: List<Double>, val d: List<Double>, val j: List<Double>)

/** Real KDJ(9,3,3) from the candle high/low/close series — standard RSV + recursive K/D smoothing. */
fun computeKdj(candles: List<Candle>, periodN: Int = 9, periodK: Int = 3, periodD: Int = 3): KdjResult {
    if (candles.isEmpty()) return KdjResult(emptyList(), emptyList(), emptyList())
    val rsv = candles.indices.map { i ->
        val window = candles.subList((i - periodN + 1).coerceAtLeast(0), i + 1)
        val highest = window.maxOf { it.high }
        val lowest = window.minOf { it.low }
        val range = (highest - lowest).takeIf { it > 0.0 } ?: 1.0
        (candles[i].close - lowest) / range * 100
    }
    val k = mutableListOf<Double>()
    val d = mutableListOf<Double>()
    var prevK = 50.0
    var prevD = 50.0
    for (value in rsv) {
        val kValue = (prevK * (periodK - 1) + value) / periodK
        val dValue = (prevD * (periodD - 1) + kValue) / periodD
        k.add(kValue)
        d.add(dValue)
        prevK = kValue
        prevD = dValue
    }
    val j = k.indices.map { 3 * k[it] - 2 * d[it] }
    return KdjResult(k, d, j)
}

/**
 * Real RSI via Wilder's smoothing: expanding average for the first `period` changes, then
 * recursive smoothing — `null` before the first full window, matching [simpleMovingAverage].
 */
fun computeRsi(closes: List<Double>, period: Int): List<Double?> {
    if (closes.size < 2) return closes.map { null }
    val gains = DoubleArray(closes.size)
    val losses = DoubleArray(closes.size)
    for (i in 1 until closes.size) {
        val change = closes[i] - closes[i - 1]
        gains[i] = if (change > 0) change else 0.0
        losses[i] = if (change < 0) -change else 0.0
    }
    var avgGain = 0.0
    var avgLoss = 0.0
    return closes.indices.map { i ->
        if (i == 0) return@map null
        avgGain = if (i <= period) gains.slice(1..i).average() else (avgGain * (period - 1) + gains[i]) / period
        avgLoss = if (i <= period) losses.slice(1..i).average() else (avgLoss * (period - 1) + losses[i]) / period
        if (i < period) null else if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }
}

data class BollResult(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

/**
 * Real Bollinger Bands: SMA(period) mid line ± k·stddev(period) — `null` before the first full window.
 *
 * ⚠️ **未决：标准差口径。** 这里用的是**总体**标准差（`÷ period`），也就是 Bollinger 本人原始定义的那个。
 * 但通达信/同花顺一系的 `STD()` 是"估算标准差"即**样本**标准差（`÷ (period − 1)`），两者带宽差
 * `sqrt(20/19) ≈ 2.6%`。项目的设计源是同花顺，所以有可能应该换成样本口径——但社区对通达信 `STD` 的定义
 * 说法不一致，而免费源都不提供算好的 BOLL，**没有参考值可以实证**，所以没有凭猜改。
 * 要定这一条：拿同花顺/通达信上同一只股票同一天的 BOLL 上下轨数值来对一下即可，一行的事。
 */
fun computeBoll(closes: List<Double>, period: Int = 20, k: Double = 2.0): BollResult {
    val mid = simpleMovingAverage(closes, period)
    val upper = mutableListOf<Double?>()
    val lower = mutableListOf<Double?>()
    closes.indices.forEach { i ->
        val m = mid[i]
        if (m == null) {
            upper.add(null)
            lower.add(null)
        } else {
            val window = closes.subList(i - period + 1, i + 1)
            val variance = window.sumOf { (it - m) * (it - m) } / period
            val sd = sqrt(variance)
            upper.add(m + k * sd)
            lower.add(m - k * sd)
        }
    }
    return BollResult(mid, upper, lower)
}
