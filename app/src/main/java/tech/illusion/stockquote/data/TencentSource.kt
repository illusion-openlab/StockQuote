package tech.illusion.stockquote.data

/**
 * 免费的腾讯行情快照接口 `qt.gtimg.cn/q=sh600519`，GBK 编码，一次返回 88 个 `~` 分隔字段。
 *
 * **它是估值与盘口字段的唯一可用来源**：市值/流通市值/市盈率/市净率/量比/换手率/涨停跌停/总股本/
 * 流通股本/内外盘——新浪的行情接口一个都不给，东财 quote 有但 2026-08-11 整个会话从主机和设备两侧
 * 都不可达（见 AGENTS.md「数据源」）。
 *
 * ⚠️ **只用它的快照字段，不要用它的历史 K 线**：腾讯的 kline 是**前复权**，而本项目的历史走新浪的
 * **不复权**日线、与实时报价同口径。两者混用会在图上留一个台阶（详见 [SinaSource.fetchHistory]）。
 * 它的**实时价**倒是不复权的（实测 1346.50 与新浪逐字相同），所以拿来当报价源是安全的。
 *
 * ## 字段位置（0-based），全部用算术交叉校验钉死，不是凭记忆认的
 *
 * | idx | 字段 | 单位 | 校验方式 |
 * |---|---|---|---|
 * | 1 | 名称 | | |
 * | 3 | 现价 | 元 | == 新浪现价 |
 * | 4 | 昨收 | 元 | == 新浪昨收 |
 * | 5 | 今开 | 元 | == 新浪今开 |
 * | 6 | 成交量 | 手 | == 新浪成交量÷100 |
 * | 7 | **外盘** | 手 | 见下方"内外盘顺序"|
 * | 8 | **内盘** | 手 | idx7+idx8 == 成交量（两只股票都精确吻合）|
 * | 33 / 34 | 最高 / 最低 | 元 | == 新浪最高/最低 |
 * | 37 | 成交额 | **万元** | ÷1e4 得亿元，与 App 显示的 36.40亿 吻合 |
 * | 38 | 换手率 | % | == 成交股数÷**流通**股本（按总股本算对不上）|
 * | 39 | 市盈率(TTM) | | |
 * | 43 | 振幅 | % | == (最高−最低)÷昨收 |
 * | 44 | **流通市值** | **亿元** | ÷现价 == idx72 |
 * | 45 | **总市值** | **亿元** | ÷现价 == idx73 |
 * | 46 | 市净率 | | |
 * | 47 / 48 | 涨停价 / 跌停价 | 元 | == 昨收×1.10 / ×0.90（两只股票都精确吻合）|
 * | 49 | 量比 | | |
 * | 50 | 委差 | 手 | （暂未在 UI 使用）|
 * | 51 | 均价 | 元 | == 成交额÷成交股数 |
 * | 72 | **流通股本** | 股 | 见下方"股本顺序"|
 * | 73 | **总股本** | 股 | 同上 |
 *
 * ### 内外盘顺序是怎么定的
 * 单看和数只能证明这两个是内外盘，证明不了谁在前。用两只涨跌相反的股票判定：招商银行当日**上涨**，
 * idx7(262209) > idx8(238560)；贵州茅台当日**下跌**，idx7(11307) < idx8(15766)。涨势下主动买入成交
 * （外盘）应更大——两次结论一致，所以 **idx7=外盘、idx8=内盘**。
 *
 * ### 股本顺序是怎么定的（**和直觉相反，别按"先总后流通"猜**）
 * 茅台全流通、两者相等，判不出来。换招商银行（有 H 股，总股本≠A股流通股本）：
 * idx72=206.29亿股 与"流通市值÷现价"吻合，idx73=252.20亿股 与"总市值÷现价"吻合。
 * 所以 **idx73 才是总股本，idx72 是流通股本**。
 *
 * ## 这个接口仍然不给的
 * 每股收益(TTM)、每股净资产、净资产收益率、市销率、股息率。响应里 idx63~66 有几个数
 * （1.37 / 3.86 / 30.53 / 26.78）但**没有校验手段**，不知道分别对应什么，所以不去猜着贴标签——
 * 那等于编数据。这几项继续显示 NoValue。
 */
object TencentSource {

    private fun marketPrefix(code: String): String = if (code.firstOrNull() == '6') "sh" else "sz"

    fun fetchQuote(code: String): RemoteStock? {
        val symbol = "${marketPrefix(code)}$code"
        val body = httpGet("https://qt.gtimg.cn/q=$symbol", charset = "GBK") ?: return null
        return runCatching {
            val fields = body.substringAfter('"', "").substringBeforeLast('"').split("~")
            if (fields.size < 74) return null
            fun at(index: Int): Double? = fields.getOrNull(index)?.trim()?.toDoubleOrNull()
            fun positive(index: Int): Double? = at(index)?.takeIf { it > 0.0 }

            val prevClose = positive(4) ?: return null
            val price = positive(3) ?: return null
            RemoteStock(
                name = fields.getOrNull(1)?.ifBlank { null },
                closes = listOf(prevClose, price),
                snapshot = QuoteSnapshot(
                    open = positive(5),
                    high = positive(33),
                    low = positive(34),
                    prevClose = prevClose,
                    volumeLots = positive(6),
                    // idx37 是**万元**，全项目的 amountYuan 统一用元
                    amountYuan = positive(37)?.let { it * 1e4 },
                    marketCapYi = positive(45),
                    floatMarketCapYi = positive(44),
                    peTtm = at(39),
                    pb = at(46),
                    volumeRatio = at(49),
                    turnoverRatePercent = at(38),
                    limitUp = positive(47),
                    limitDown = positive(48),
                    totalShares = positive(73),
                    floatShares = positive(72),
                    outerVolLots = at(7),
                    innerVolLots = at(8),
                ),
            )
        }.getOrNull()
    }
}
