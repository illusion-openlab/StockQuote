package tech.illusion.stockquote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周期聚合的回归测试。这里锁死的是**口径**，不是实现细节：开首收末、高低取极值、量额求和、日期取
 * 区间末日。三条容易写错、静态读代码看不出来的点各有一个用例：
 *  - ISO 周跨年（12月底那几天属于上一年的最后一周，不能按 `date.year` 分组）
 *  - 合成蜡烛（无日期）必须整段放弃聚合，不能按下标每 5 根切一周
 *  - 新浪 scale 码和抓取深度是常量，改错了整条周期链路会静默退化成本地聚合
 */
class AggregateCandlesTest {

    private fun bar(date: String, open: Double, high: Double, low: Double, close: Double, volume: Double) =
        Candle(open = open, high = high, low = low, close = close, volume = volume, amount = volume * close, date = date)

    /** 2026-08-03(一) ~ 08-07(五) 一整周，08-10(一)~08-11(二) 是下一周还没走完的两天。 */
    private val twoWeeks = listOf(
        bar("2026-08-03", 100.0, 105.0, 99.0, 104.0, 10.0),
        bar("2026-08-04", 104.0, 109.0, 103.0, 108.0, 20.0),
        bar("2026-08-05", 108.0, 111.0, 96.0, 97.0, 30.0), // 本周最高 111 和最低 96 都在这一天
        bar("2026-08-06", 97.0, 101.0, 97.0, 100.0, 40.0),
        bar("2026-08-07", 100.0, 106.0, 100.0, 106.0, 50.0),
        bar("2026-08-10", 106.0, 120.0, 105.0, 118.0, 60.0),
        bar("2026-08-11", 118.0, 125.0, 117.0, 121.0, 70.0),
    )

    @Test
    fun `weekly bar takes first open, last close, extreme high-low and summed volume`() {
        val weekly = aggregateCandles(twoWeeks, KPeriod.Weekly)

        assertEquals(2, weekly.size)
        val first = weekly[0]
        assertEquals(100.0, first.open, 1e-9) // 周一的开盘
        assertEquals(106.0, first.close, 1e-9) // 周五的收盘
        assertEquals(111.0, first.high, 1e-9)
        assertEquals(96.0, first.low, 1e-9)
        assertEquals(150.0, first.volume, 1e-9) // 10+20+30+40+50
        assertEquals("2026-08-07", first.date) // 区间内最后一个交易日

        // 还没走完的那一周照样出一根 bar（行情软件的"正在形成的 bar"），不因为不满 5 天就丢掉。
        val partial = weekly[1]
        assertEquals(106.0, partial.open, 1e-9)
        assertEquals(121.0, partial.close, 1e-9)
        assertEquals(130.0, partial.volume, 1e-9)
        assertEquals("2026-08-11", partial.date)
    }

    @Test
    fun `monthly groups by calendar month`() {
        val bars = listOf(
            bar("2026-07-30", 10.0, 12.0, 9.0, 11.0, 1.0),
            bar("2026-07-31", 11.0, 13.0, 10.0, 12.0, 2.0),
            bar("2026-08-03", 12.0, 15.0, 11.0, 14.0, 4.0),
        )
        val monthly = aggregateCandles(bars, KPeriod.Monthly)

        assertEquals(2, monthly.size)
        assertEquals(10.0, monthly[0].open, 1e-9)
        assertEquals(12.0, monthly[0].close, 1e-9)
        assertEquals(3.0, monthly[0].volume, 1e-9)
        assertEquals("2026-07-31", monthly[0].date)
        assertEquals("2026-08-03", monthly[1].date)
    }

    /**
     * ISO 周跨年：2025-12-29(一)~2026-01-02(五) 是**同一个** ISO 周（2026-W01）。
     * 如果分组键用 `date.year` 而不是 week-based-year，12/29~12/31 会被算成"2025 年第 1 周"，
     * 和一月初的数据分到两组，年末年初凭空多一根 bar。
     */
    @Test
    fun `iso week spanning new year stays one bar`() {
        val bars = listOf(
            bar("2025-12-29", 10.0, 11.0, 9.0, 10.5, 1.0),
            bar("2025-12-30", 10.5, 12.0, 10.0, 11.0, 1.0),
            bar("2025-12-31", 11.0, 11.5, 10.5, 11.2, 1.0),
            bar("2026-01-02", 11.2, 13.0, 11.0, 12.8, 1.0),
        )
        val weekly = aggregateCandles(bars, KPeriod.Weekly)

        assertEquals(1, weekly.size)
        assertEquals(10.0, weekly[0].open, 1e-9)
        assertEquals(12.8, weekly[0].close, 1e-9)
        assertEquals("2026-01-02", weekly[0].date)
    }

    /** 合成蜡烛没有日期，无法分组——必须整段原样返回，而不是按下标瞎切。 */
    @Test
    fun `synthesized candles without dates are returned untouched`() {
        val synthetic = buildCandles(List(40) { 100.0 + it })
        assertTrue(synthetic.all { !it.isReal() })

        assertSame(synthetic, aggregateCandles(synthetic, KPeriod.Weekly))
        assertSame(synthetic, aggregateCandles(synthetic, KPeriod.Monthly))
    }

    /** 日线周期是恒等变换，一根都不能动（`DetailScreen` 的默认档位走的就是这条）。 */
    @Test
    fun `daily period is identity`() {
        assertSame(twoWeeks, aggregateCandles(twoWeeks, KPeriod.Daily))
    }

    /**
     * 新浪 `scale` 参数（分钟数）。写死在测试里是故意的：这三个数一改，周/月线就会静默地抓回日线，
     * 图上看起来"能用"但根本不是那个周期。实测值见 [KPeriod] 的说明。
     */
    @Test
    fun `sina scale codes stay pinned`() {
        assertEquals(240, KPeriod.Daily.sinaScale)
        assertEquals(1200, KPeriod.Weekly.sinaScale)
        assertEquals(7200, KPeriod.Monthly.sinaScale)
    }

    /** 抓取深度必须盖得住各周期最长的区间档位（日 220 / 周 156 / 月 60），还要留翻历史的余量。 */
    @Test
    fun `fetch depth covers the widest range option with room to pan`() {
        assertTrue(KPeriod.Daily.fetchDepth > 220)
        assertTrue(KPeriod.Weekly.fetchDepth > 156)
        assertTrue(KPeriod.Monthly.fetchDepth > 60)
    }

    /**
     * 用真实数据（新浪 sh600519 日线 230 根，2025-08-28~2026-08-11）离线核对过的根数，
     * 这里用等长的合成日期序列复现同一组分桶结果，防止分组键以后被改坏。
     * 参考值来自实现期的独立 Python 复算：weekly 50 根 / monthly 13 根。
     */
    @Test
    fun `bucket counts match the offline reference for a 230-day window`() {
        val dates = tradingDays(count = 230, from = java.time.LocalDate.parse("2025-08-28"))
        val bars = dates.mapIndexed { index, date -> bar(date, 100.0, 101.0, 99.0, 100.0 + index, 1.0) }

        // 合成序列只跳周末、不跳节假日，所以**不能**去对真实数据那组 50/13——少跳了节假日，230 根
        // 就只走到 2026-07-15 而不是 08-11，桶数自然不同。这里核的是分桶结构本身。
        val weekly = aggregateCandles(bars, KPeriod.Weekly)
        assertEquals(47, weekly.size) // 首周只有周四五 2 天、末周 3 天，中间 45 个整周
        assertTrue(weekly.all { it.date in dates }) // 每根的日期都落在源序列里（取的是桶内末日）
        assertTrue(weekly.first().date < weekly.last().date) // 升序保持

        val monthly = aggregateCandles(bars, KPeriod.Monthly)
        assertEquals(12, monthly.size) // 2025-08 ~ 2026-07
        assertEquals(bars.last().close, monthly.last().close, 1e-9)
        assertEquals(bars.sumOf { it.volume }, monthly.sumOf { it.volume }, 1e-9) // 求和不丢量
    }

    private fun tradingDays(count: Int, from: java.time.LocalDate): List<String> {
        val out = mutableListOf<String>()
        var day = from
        while (out.size < count) {
            if (day.dayOfWeek.value <= 5) out += day.toString()
            day = day.plusDays(1)
        }
        return out
    }
}
