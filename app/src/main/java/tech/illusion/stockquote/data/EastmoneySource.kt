package tech.illusion.stockquote.data

import org.json.JSONObject
import java.time.format.DateTimeFormatter

/**
 * Free, unofficial Eastmoney "push" endpoints (no API key, no official SLA — see design doc §6/§7
 * and AGENTS.md "数据源"). Every call degrades to `null` on any parse/network failure instead of
 * throwing, so [StockDataRepository] can fall back to another source.
 */
object EastmoneySource {

    private fun marketPrefix(code: String): String = if (code.firstOrNull() == '6') "1" else "0"

    /**
     * Daily OHLCV history with trading dates, oldest first, at most `days` bars.
     *
     * Two quirks of this endpoint drive the request shape (both verified by hand against 600519 on
     * 2026-08-11, neither is documented anywhere):
     *  - **`lmt` is silently ignored once an explicit `beg`/`end` pair is present.** `lmt=4` still
     *    returned all 5980 rows back to 2001, so the window has to be bounded through `beg`.
     *  - **Bounding it is a correctness fix, not just a bandwidth one.** `fqt=1` (forward-adjusted)
     *    prices go *negative* far enough back — 600519's 2001 rows come back around `-312` — and a
     *    single such row would drag the chart's whole price axis with it.
     */
    fun fetchHistory(code: String, days: Int): RemoteStock? {
        // ~2 calendar days per trading day, plus margin for holiday clusters. 起点按上海时区算
        // （[MarketClock]）——这里差一天只是多/少一天余量、不影响正确性，但交易日基准全项目只该有一个。
        val begin = MarketClock.today().minusDays(days * 2L + 30).format(DateTimeFormatter.BASIC_ISO_DATE)
        // Bounded window first — it is an order of magnitude less payload. But that `beg` form could
        // not be verified against the live endpoint while this was written (this machine's egress is
        // refused by eastmoney, and an on-device probe showed `beg=0` and the bounded `beg` failing
        // identically), so fall back to `beg=0` — the one shape with an observed 200 — rather than let
        // an unverified assumption quietly kill the whole history path.
        return fetchKline(code, days, begin) ?: fetchKline(code, days, "0")
    }

    private fun fetchKline(code: String, days: Int, begin: String): RemoteStock? {
        val secid = "${marketPrefix(code)}.$code"
        val url =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                "?secid=$secid&klt=101&fqt=1&beg=$begin&end=20500101&lmt=$days" +
                "&fields1=f1,f2,f3,f4,f5&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
        // 20s, not the 6s default: `beg=0` pulls the full history (600519 is 5980 rows ≈ 360KB) and a
        // read timeout there would be indistinguishable from the endpoint refusing us.
        val body = httpGet(url, referer = "https://quote.eastmoney.com/", timeoutMs = 20_000) ?: return null
        return runCatching {
            val json = JSONObject(body)
            if (json.optInt("rc") != 0) return null
            val data = json.optJSONObject("data") ?: return null
            val klines = data.optJSONArray("klines") ?: return null
            val candles = (0 until klines.length())
                .mapNotNull { i -> parseCandle(klines.optString(i)) }
                .takeLast(days)
            if (candles.size < 2) {
                null
            } else {
                RemoteStock(data.optString("name").ifBlank { null }, candles.map { it.close }, candles)
            }
        }.getOrNull()
    }

    /**
     * One kline row for `fields2=f51..f58`: `date,open,close,high,low,volume,amount,amplitude`.
     * Note `close` precedes `high`/`low` — not the conventional OHLC order.
     */
    private fun parseCandle(row: String): Candle? {
        val f = row.split(",")
        if (f.size < 6) return null
        val open = f[1].toDoubleOrNull() ?: return null
        val close = f[2].toDoubleOrNull() ?: return null
        val high = f[3].toDoubleOrNull() ?: return null
        val low = f[4].toDoubleOrNull() ?: return null
        val volume = f[5].toDoubleOrNull() ?: return null
        val amount = f.getOrNull(6)?.toDoubleOrNull() ?: 0.0
        // Drops both malformed rows and the negative-price rows fqt=1 produces for early history.
        if (open <= 0.0 || close <= 0.0 || high <= 0.0 || low <= 0.0 || volume < 0.0) return null
        return Candle(
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            amount = amount,
            date = f[0],
        )
    }

    /** Live snapshot (name + last price + previous close) — no multi-day history. */
    fun fetchQuote(code: String): RemoteStock? {
        val secid = "${marketPrefix(code)}.$code"
        val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=$secid&fields=f43,f58,f60"
        val body = httpGet(url) ?: return null
        return runCatching {
            val json = JSONObject(body)
            if (json.optInt("rc") != 0) return null
            val data = json.optJSONObject("data") ?: return null
            val price = data.optInt("f43", -1)
            val prevClose = data.optInt("f60", -1)
            if (price <= 0 || prevClose <= 0) return null
            RemoteStock(data.optString("f58").ifBlank { null }, listOf(prevClose / 100.0, price / 100.0))
        }.getOrNull()
    }
}
