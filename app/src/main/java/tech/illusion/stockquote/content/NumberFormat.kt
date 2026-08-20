package tech.illusion.stockquote.content

/** Shown wherever a free source genuinely does not give us the field — never a stand-in for a guess. */
const val NoValue = "—"

/**
 * Volume is in 手 (1 手 = 100 股) — that is the unit the Eastmoney kline `volume` column uses,
 * cross-checked against the same row's `amount` column (volume × 100 × price ≈ amount).
 */
fun formatLots(lots: Double): String = when {
    lots >= 1e8 -> "%.2f亿手".format(lots / 1e8)
    lots >= 1e4 -> "%.2f万手".format(lots / 1e4)
    else -> "%.0f手".format(lots)
}

/** Yuan amounts (成交额/市值) condensed to 万/亿/万亿. */
fun formatYuan(yuan: Double): String = when {
    yuan >= 1e12 -> "%.2f万亿".format(yuan / 1e12)
    yuan >= 1e8 -> "%.2f亿".format(yuan / 1e8)
    yuan >= 1e4 -> "%.2f万".format(yuan / 1e4)
    else -> "%.0f".format(yuan)
}

/**
 * 市值类字段。**入参单位是亿元**，不是元——腾讯 `qt.gtimg.cn` 的市值/流通市值字段就是亿元
 * （茅台 16832.35 = 1.68万亿）。误用 [formatYuan] 会差 8 个数量级，显示成"1.68万"。
 */
fun formatYi(yi: Double): String = when {
    yi >= 10_000 -> "%.2f万亿".format(yi / 10_000)
    else -> "%.2f亿".format(yi)
}

/** 股本类字段，入参单位是**股**。 */
fun formatShares(shares: Double): String = when {
    shares >= 1e8 -> "%.2f亿股".format(shares / 1e8)
    shares >= 1e4 -> "%.2f万股".format(shares / 1e4)
    else -> "%.0f股".format(shares)
}

/** 市盈率/市净率/量比这类无单位比率。 */
fun formatRatio(value: Double): String = "%.2f".format(value)

fun formatPrice(value: Double): String = "%.2f".format(value)

fun formatPercent(value: Double): String = "%.2f%%".format(value)
