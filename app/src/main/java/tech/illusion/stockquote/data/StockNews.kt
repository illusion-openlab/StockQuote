package tech.illusion.stockquote.data

/** 个股新闻。`url` 字段本版不使用（不做外部跳转，见设计文档 §7），保留是为了以后加"跳转原文"不用改数据层。 */
data class StockNews(
    val title: String,
    val content: String,
    val publicTime: String,
    val url: String,
)
