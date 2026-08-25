package tech.illusion.stockquote.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "StockDataRepository"

/**
 * Eastmoney primary (kline gives real multi-day history; its own quote endpoint is tried next),
 * Sina quote-only as last resort — see design doc §6/§7. All three legs can fail independently
 * (no SLA, no API key); callers get `null` and should keep whatever data they already have rather
 * than treat it as a crash.
 */
object StockDataRepository {

    /**
     * Multi-day history, in preference order. **Sina's kline is the primary source** — measured against
     * Eastmoney's by hand on 2026-08-11, it honors `datalen` (230 rows ≈ 25KB instead of the full
     * 5980-row dump), uses named JSON fields, and returns unadjusted prices that line up with the live
     * quote. See [SinaSource.fetchHistory] for the full comparison.
     *
     * The last two legs are **quote-only** and therefore return just `[昨收, 现价]` — two points, no
     * candles. That is what produced the "chart shows only 2 candles" symptom whenever the kline legs
     * failed: not a rendering bug, just the honest shape of the only data we had.
     */
    suspend fun fetchHistory(code: String, days: Int): RemoteStock? =
        withContext(Dispatchers.IO) {
            (SinaSource.fetchHistory(code, days)?.also { Log.d(TAG, "$code history <- sina-kline (${it.closes.size} pts)") }
                ?: EastmoneySource.fetchHistory(code, days)?.also { Log.d(TAG, "$code history <- eastmoney-kline (${it.closes.size} pts)") }
                ?: EastmoneySource.fetchQuote(code)?.also { Log.d(TAG, "$code history <- eastmoney-quote (只有2点, ${it.closes.size} pts)") }
                ?: SinaSource.fetchQuote(code)?.also { Log.d(TAG, "$code history <- sina-quote (只有2点, ${it.closes.size} pts)") })
                .also { if (it == null) Log.w(TAG, "$code history: all sources failed") }
        }

    /**
     * 实时报价快照。**腾讯优先**：只有它给估值与盘口字段（市值/流通市值/市盈率/市净率/量比/换手率/
     * 涨停跌停/总股本/流通股本/内外盘），另外两家只给 OHLCV，退到它们时那些字段就会是"—"。
     * 三家的**实时价都是不复权的**，和新浪日线历史同口径，可以安全互换（腾讯的**历史** K 线是前复权，
     * 那个不能用，见 [TencentSource]）。
     */
    suspend fun fetchQuote(code: String): RemoteStock? =
        withContext(Dispatchers.IO) {
            (TencentSource.fetchQuote(code)?.also { Log.d(TAG, "$code quote <- tencent (含估值字段)") }
                ?: EastmoneySource.fetchQuote(code)?.also { Log.d(TAG, "$code quote <- eastmoney (无估值字段)") }
                ?: SinaSource.fetchQuote(code)?.also { Log.d(TAG, "$code quote <- sina (无估值字段)") })
                .also { if (it == null) Log.w(TAG, "$code quote: all sources failed") }
        }

    /** 全市场名称/代码搜索，见 [SinaSource.suggest]。`null` = 请求失败，空列表 = 请求成功但无命中。 */
    suspend fun searchSuggestions(keyword: String): List<StockSuggestion>? =
        withContext(Dispatchers.IO) { SinaSource.suggest(keyword) }

    /** 个股新闻，见 [EastmoneyNewsSource.fetchNews]。`null` = 请求失败，空列表 = 请求成功但无命中。 */
    suspend fun fetchNews(code: String): List<StockNews>? =
        withContext(Dispatchers.IO) {
            EastmoneyNewsSource.fetchNews(code).also {
                if (it == null) Log.w(TAG, "$code news: fetch failed") else Log.d(TAG, "$code news <- eastmoney (${it.size} 条)")
            }
        }

    /** 新闻原文全文，见 [EastmoneyArticleSource.fetchFullContent]。`null` = 抓取/解析失败，调用方退回摘要。 */
    suspend fun fetchArticle(url: String): String? =
        withContext(Dispatchers.IO) {
            EastmoneyArticleSource.fetchFullContent(url).also {
                if (it == null) Log.w(TAG, "article fetch failed: $url") else Log.d(TAG, "article <- eastmoney (${it.length} 字)")
            }
        }
}
