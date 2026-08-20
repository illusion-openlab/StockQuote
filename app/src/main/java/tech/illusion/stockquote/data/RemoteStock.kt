package tech.illusion.stockquote.data

/**
 * Today's trading snapshot, as far as the source actually reports it. Every field is nullable on
 * purpose: a free source that does not give a value must surface as "—" in the UI, never as a guess
 * or a derived stand-in (design contract v3 §2.5).
 *
 * `volumeLots` is in 手 (1 手 = 100 股) to match [Candle.volume]; sources that report shares are
 * converted at the parse site.
 */
data class QuoteSnapshot(
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val prevClose: Double? = null,
    val volumeLots: Double? = null,
    val amountYuan: Double? = null,
    // ── 估值与盘口衍生字段。只有腾讯 qt.gtimg.cn 给（新浪的行情接口没有，东财有但整个 2026-08-11
    // 会话都不可达）。字段位置全部用算术交叉校验钉死过，见 TencentSource 的表。
    /** 总市值，**单位是亿元**（源就是亿元，不是元——直接套 formatYuan 会差 8 个数量级）。 */
    val marketCapYi: Double? = null,
    /** 流通市值，同样是**亿元**。 */
    val floatMarketCapYi: Double? = null,
    val peTtm: Double? = null,
    val pb: Double? = null,
    /** 量比。 */
    val volumeRatio: Double? = null,
    /** 换手率，百分比数值（源已实测是按**流通股本**算的）。 */
    val turnoverRatePercent: Double? = null,
    /** 涨停价/跌停价：**从源直接取**，不用"昨收×1.1"推算——创业板/科创板/ST 的限制不同，推算出来是
     *  规则假设而不是行情数据。 */
    val limitUp: Double? = null,
    val limitDown: Double? = null,
    /** 总股本 / 流通股本，单位是**股**。 */
    val totalShares: Double? = null,
    val floatShares: Double? = null,
    /** 外盘（主动买入成交）/ 内盘，单位是手。两者之和等于成交量。 */
    val outerVolLots: Double? = null,
    val innerVolLots: Double? = null,
)

/**
 * One successful remote fetch: `closes` is a daily-close series, oldest first, ending with the latest
 * known price.
 *
 * `candles` carries the source's own OHLCV **and trading date** when the fetch came from a kline
 * endpoint; it is empty for quote-only fallbacks, which have no history to report. Dates ride along
 * inside [Candle] rather than in a parallel `dates` array (which is how design contract v3 sketched
 * it) so that slicing a range can never misalign a candle from its date label.
 *
 * `snapshot` is today's quote detail when the source reports it — independent of `candles`, because
 * the quote-only sources have no history but do have today's open/high/low/volume.
 */
data class RemoteStock(
    val name: String?,
    val closes: List<Double>,
    val candles: List<Candle> = emptyList(),
    val snapshot: QuoteSnapshot? = null,
)

/** 一条全市场搜索命中：只有名称和代码，没有行情——命中之后还要单独抓一次 [StockDataRepository.fetchHistory]。 */
data class StockSuggestion(val code: String, val name: String)
