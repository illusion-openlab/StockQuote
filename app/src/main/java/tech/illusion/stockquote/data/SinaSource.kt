package tech.illusion.stockquote.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Free, unofficial Sina realtime-quote endpoint — fallback when [EastmoneySource] fails. Quote-only,
 * no multi-day history (see design doc §6/§7).
 *
 * Field layout of `var hq_str_sh600519="…"`, verified by hand against sh600519 on 2026-08-11:
 *
 * | idx | field    | sample          |
 * |-----|----------|-----------------|
 * | 0   | 名称     | 贵州茅台         |
 * | 1   | 今开     | 1348.000        |
 * | 2   | 昨收     | 1348.860        |
 * | 3   | 现价     | 1346.480        |
 * | 4   | 最高     | 1352.650        |
 * | 5   | 最低     | 1338.180        |
 * | 6   | 买一     | 1345.720        |
 * | 7   | 卖一     | 1346.470        |
 * | 8   | 成交量(股) | 1513880       |
 * | 9   | 成交额(元) | 2036498613.000 |
 * | 30  | 日期     | 2026-08-11      |
 * | 31  | 时间     | 11:30:00        |
 *
 * Cross-check that pins the units: 成交额 ÷ 成交量 = 2036498613 / 1513880 ≈ 1345.5 元/股, i.e. within
 * a tick of 现价 — so idx8 really is shares (not 手) and idx9 really is yuan.
 */
object SinaSource {

    private fun marketPrefix(code: String): String = if (code.firstOrNull() == '6') "sh" else "sz"

    /**
     * Daily OHLCV history, oldest first, at most `days` bars — **the primary history source**.
     *
     * Chosen over Eastmoney's kline after measuring both by hand (2026-08-11):
     *  - **`datalen` is actually honored.** Asking for 230 returns exactly 230 rows (25KB). Eastmoney's
     *    `lmt` is silently ignored whenever `beg`/`end` are present and dumps all 5980 rows (~360KB).
     *  - **Named JSON fields** instead of a positional CSV row, so a column-order change upstream can't
     *    silently shift `close` into `high` (Eastmoney's row order is the unusual
     *    `date,open,close,high,low,…` — close *before* high/low).
     *  - **Prices are unadjusted**, which matches what [fetchQuote] reports for the latest price. An
     *    adjusted history (Eastmoney `fqt=1`, Tencent `qfq`) spliced onto an unadjusted live price puts
     *    a step in the chart; Eastmoney's `fqt=1` additionally goes *negative* on early history.
     *  - **Cross-check that pinned it**: the last row's close for 600519 (1348.86 on 2026-08-10) is
     *    exactly the 昨收 the live quote reported at the same moment.
     *
     * `scale=240` means 240 minutes, i.e. one daily bar. Verified for both 沪 (`sh600519`) and
     * 深 (`sz000858`) symbols.
     *
     * Note this endpoint returns **no 成交额 and no name** — 成交额 comes from the quote snapshot instead
     * (see [fetchQuote]), and the name is already known locally.
     */
    fun fetchHistory(code: String, days: Int): RemoteStock? = fetchBars(code, KPeriod.Daily, days)

    /**
     * 任意周期的 K 线，oldest first，最多 `count` 根。日线走 [fetchHistory]，周/月线由详情页按需调用。
     *
     * 同一个端点，只换 `scale`（分钟数，见 [KPeriod]）。实测三档都honors `datalen`：
     * `240/600` → 600 根（2024-02-22 起）、`1200/260` → 260 根周线（2021-07-16 起）、
     * `7200/120` → 120 根月线（2016-09-30 起）。三档的复权口径一致（都不复权），所以周/月线和日线、
     * 和实时报价可以混在同一个界面里而不会出现台阶。
     */
    fun fetchBars(code: String, period: KPeriod, count: Int): RemoteStock? {
        val symbol = "${marketPrefix(code)}$code"
        val url =
            "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$symbol&scale=${period.sinaScale}&ma=no&datalen=$count"
        val body = httpGet(url, referer = "https://finance.sina.com.cn", timeoutMs = 20_000) ?: return null
        return runCatching {
            val rows = JSONArray(body)
            val candles = (0 until rows.length()).mapNotNull { i -> parseHistoryRow(rows.optJSONObject(i)) }
            if (candles.size < 2) null else RemoteStock(null, candles.map { it.close }, candles)
        }.getOrNull()
    }

    /** One row of `[{"day","open","high","low","close","volume"}, …]`. `volume` is in 股. */
    private fun parseHistoryRow(row: JSONObject?): Candle? {
        if (row == null) return null
        val day = row.optString("day").takeIf { it.isNotBlank() } ?: return null
        val open = row.optString("open").toDoubleOrNull() ?: return null
        val high = row.optString("high").toDoubleOrNull() ?: return null
        val low = row.optString("low").toDoubleOrNull() ?: return null
        val close = row.optString("close").toDoubleOrNull() ?: return null
        val shares = row.optString("volume").toDoubleOrNull() ?: return null
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0 || shares < 0.0) return null
        // 全项目统一用手（1 手 = 100 股）作为 Candle.volume 的单位
        return Candle(open = open, high = high, low = low, close = close, volume = shares / 100.0, date = day)
    }

    fun fetchQuote(code: String): RemoteStock? {
        val symbol = "${marketPrefix(code)}$code"
        val url = "https://hq.sinajs.cn/list=$symbol"
        val body = httpGet(url, referer = "https://finance.sina.com.cn", charset = "GBK") ?: return null
        return runCatching {
            val content = body.substringAfter("\"", "").substringBeforeLast("\"")
            val fields = content.split(",")
            if (fields.size < 10) return null
            fun at(index: Int): Double? = fields.getOrNull(index)?.toDoubleOrNull()?.takeIf { it > 0.0 }

            val prevClose = at(2) ?: return null
            val price = at(3) ?: return null
            val shares = at(8)
            RemoteStock(
                name = fields[0].ifBlank { null },
                closes = listOf(prevClose, price),
                snapshot = QuoteSnapshot(
                    open = at(1),
                    high = at(4),
                    low = at(5),
                    prevClose = prevClose,
                    volumeLots = shares?.let { it / 100.0 },
                    amountYuan = at(9),
                ),
            )
        }.getOrNull()
    }

    /**
     * 全市场名称/拼音/代码模糊搜索。跟 [fetchQuote] 同域名同风格：免费、无 token，未文档化但已手测可用
     * （2026-08-19，搜"茅台"直接返回 `贵州茅台,11,600519,sh600519,...`）。这是 [StockRepository] 里
     * 12 只硬编码种子股票之外唯一的全市场入口——那 12 只只是本地兜底，不是全市场数据源。
     *
     * 响应形如 `var suggestvalue="贵州茅台,11,600519,sh600519,贵州茅台,,贵州茅台,99,1,ESG,,";`，分号
     * 分隔多条命中，每条逗号分隔：第 0 段名称，第 1 段类型（11=沪深A股，12/13/14/15 是权证/基金/指数等），
     * 第 3 段带市场前缀的代码（sh/sz+6位）。只保留类型 11，其余（基金/指数/权证）跳过。
     *
     * 返回 `null` 表示请求失败（网络/超时），空列表表示请求成功但没有命中——调用方要能区分这两种，
     * 前者应该保留本地结果并提示"全市场搜索暂时不可用"，后者才是真的"没有这只股票"。
     */
    fun suggest(keyword: String): List<StockSuggestion>? {
        val key = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://suggest3.sinajs.cn/suggest/type=11,12,13,14,15&key=$key"
        val body = httpGet(url, referer = "https://finance.sina.com.cn", charset = "GBK") ?: return null
        return runCatching {
            val content = body.substringAfter("\"", "").substringBeforeLast("\"")
            if (content.isBlank()) return@runCatching emptyList()
            content.split(";").mapNotNull { entry ->
                val fields = entry.split(",")
                if (fields.size < 4 || fields[1] != "11") return@mapNotNull null
                val code = fields[3].drop(2) // 去掉 sh/sz 市场前缀
                val name = fields[0]
                if (code.length != 6 || name.isBlank()) return@mapNotNull null
                StockSuggestion(code = code, name = name)
            }
        }.getOrNull()
    }
}
