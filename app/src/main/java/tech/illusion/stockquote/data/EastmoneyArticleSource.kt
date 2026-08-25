package tech.illusion.stockquote.data

import android.text.Html
import android.util.Log

private const val TAG = "EastmoneyArticleSource"
private const val ContentBodyMarker = "id=\"ContentBody\""
private const val ContentEndMarker = "<div class=\"sm\""

/**
 * 抓新闻原文全文。`EastmoneyNewsSource` 的搜索接口自带的 `content` 字段只是搜索片段——有的文章甚至是
 * 表格碎片（"股东户数降幅榜"那类文章实测抓到的是"1.20 汽车 603226 菲林格尔 7242 -1.79..."这种完全读不懂
 * 的表格行），不是可读摘要。这个源直接打 [StockNews.url]（东财自家的文章镜像页，不管"来源"栏写的是
 * 界面新闻/证券时报网/南方财经网哪一家，链接都是这同一套 `finance.eastmoney.com/a/<id>.html` 模板）抓正文。
 *
 * 边界已用两篇结构不同的真实文章核实过：正文都在 `id="ContentBody"` 里，`class="sm"`（"原标题/郑重声明"
 * 那块的开头）当结束边界很稳定——不是猜的。
 */
object EastmoneyArticleSource {

    /** 从整页 HTML 里切出正文片段（还带标签，没转义）。找不到边界就返回 `null`，不猜着截一半。 */
    internal fun extractArticleHtml(pageHtml: String): String? {
        val markerIndex = pageHtml.indexOf(ContentBodyMarker)
        if (markerIndex == -1) return null
        val tagEnd = pageHtml.indexOf('>', markerIndex)
        if (tagEnd == -1) return null
        val bodyStart = tagEnd + 1
        val bodyEnd = pageHtml.indexOf(ContentEndMarker, bodyStart)
        if (bodyEnd == -1 || bodyEnd <= bodyStart) return null
        return pageHtml.substring(bodyStart, bodyEnd)
    }

    /**
     * `null` = 抓取/解析失败（网络失败、页面模板变了、边界没找到）。调用方应该退回列表页那份 `content`
     * 摘要，而不是把这当成硬错误——摘要好歹能看，别为了"全文"把本来能看的内容也搞没了。
     *
     * `url` 字段这次实测是 `http://` 前缀，但 `HttpURLConnection` 不会自动跟跨协议（http→https）的跳转，
     * 而这条链路 302 到的正是 https；直接把协议换成 https 打，比处理跳转链路简单。
     */
    fun fetchFullContent(url: String): String? {
        if (url.isBlank()) return null
        val secureUrl = url.replaceFirst("http://", "https://")
        val pageHtml = httpGet(secureUrl, referer = "https://so.eastmoney.com/", timeoutMs = 20_000)
            ?: return null.also { Log.w(TAG, "fetch failed (network): $secureUrl") }
        val fragment = extractArticleHtml(pageHtml)
            ?: return null.also { Log.w(TAG, "fetch failed (ContentBody/sm marker not found, template may have changed): $secureUrl") }
        val text = Html.fromHtml(fragment, Html.FROM_HTML_MODE_LEGACY).toString().trim().takeIf { it.isNotBlank() }
        if (text == null) Log.w(TAG, "fetch failed (extracted fragment was blank after stripping tags): $secureUrl")
        return text
    }
}
