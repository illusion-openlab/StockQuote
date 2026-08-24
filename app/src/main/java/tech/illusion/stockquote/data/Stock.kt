package tech.illusion.stockquote.data

/**
 * `hist` is a daily-close series (most recent last). Price and change% are derived from it rather
 * than stored separately so the two values can never drift out of sync.
 *
 * `candles` holds the source's real OHLCV+date series when a kline fetch succeeded, and is empty
 * when the only data we ever got was a quote snapshot (or the seed below). Callers that need
 * candles must fall back to [buildCandles] on `hist` for that case — but anything that reports a
 * *number* to the user (e.g. the volume panel's latest volume) may only use real candles, never the
 * synthesized ones.
 */
data class Stock(
    val code: String,
    val name: String,
    val hist: List<Double>,
    val candles: List<Candle> = emptyList(),
    val snapshot: QuoteSnapshot? = null,
    val orderIndex: Int = 0,
)

fun Stock.price(): Double = hist.last()

fun Stock.changePercent(): Double {
    val previous = hist[hist.size - 2]
    val last = hist.last()
    return (last - previous) / previous * 100
}
