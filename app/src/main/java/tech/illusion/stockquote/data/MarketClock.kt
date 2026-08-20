package tech.illusion.stockquote.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * A股交易日的时间基准。**所有"今天是哪个交易日"的判断都必须走这里，不能用 [LocalDate.now] 的设备时区。**
 *
 * 真机上实测踩到过：那台 PICO 的系统时区是 `America/New_York`，`date` 报 `Tue Aug 11 04:30 EDT`。
 * 上海和纽约横跨日界，从纽约的 00:00 到上海的同一时刻之间，`LocalDate.now()` 会比上海**慢一天**——
 * 而 A 股的这一整个交易日（上海 09:30–15:00）恰好整段落在那个窗口里。于是
 * [StockRepository.mergeLatestPrice] 认不出"历史末尾这根就是今天"，会给同一个交易日再追加一根 bar，
 * K 线尾部凭空多一天、日期还是昨天的。
 *
 * 换句话说：这个 bug 只在设备时区落后上海时才出现，模拟器是 CST 所以一直没暴露。用固定时区就没有这类
 * "换台设备行为就变"的问题——交易日属于市场，不属于用户站在哪儿。
 */
object MarketClock {
    private val Zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 上海当天的日历日。注意这是**日历日**，不判断是否休市——节假日表我们没有，也不需要。 */
    fun today(): LocalDate = LocalDate.now(Zone)

    /** `yyyy-MM-dd`，与新浪/东财 kline 的日期列同格式，可以直接和 [Candle.date] 比。 */
    fun todayString(): String = today().toString()
}
