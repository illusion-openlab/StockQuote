package tech.illusion.stockquote.data

import android.text.Html
import android.util.Log

private const val TAG = "EastmoneyArticleSource"
private const val ContentBodyMarker = "id=\"ContentBody\""

/**
 * 抓新闻原文全文。`EastmoneyNewsSource` 的搜索接口自带的 `content` 字段只是搜索片段——有的文章甚至是
 * 表格碎片（"股东户数降幅榜"那类文章实测抓到的是"1.20 汽车 603226 菲林格尔 7242 -1.79..."这种完全读不懂
 * 的表格行），不是可读摘要。这个源直接打 [StockNews.url]（东财自家的文章镜像页，不管"来源"栏写的是
 * 界面新闻/证券时报网/南方财经网哪一家，链接都是这同一套 `finance.eastmoney.com/a/<id>.html` 模板）抓正文。
 *
 * ⚠️ 2026-08-25 复评时发现并修了两个问题（都用真实抓到的文章重新核实过，不是猜的）：
 * 1. 边界原来靠字符串搜第一个 `class="sm"`，如果正文本身内部就出现一个同名 class（作者行内标注、
 *    小字说明），会在真正的"原标题/郑重声明"footer 之前提前截断，且截断后返回值仍然非 null——不会
 *    落到"边界没找到"那条失败路径，用户看到的是一篇看似完整、实际缺尾的文章，没有任何信号提醒。
 *    改成 [findMatchingDivEnd] 做真正的 `<div>`/`</div>` 深度配对，直接找 `id="ContentBody"` 那个
 *    div 自己对应的收尾 `</div>`——不依赖后面跟着什么兄弟标签，结构上就不可能被内部同名 class 撞车。
 *    附带验证：这个边界比原来字符串匹配法找到的更早、更准——它正确排除了"原标题/责任编辑"这类文章
 *    正文结束之后的尾部信息，原来的写法会把这段也一起吞进去。
 * 2. `Html.fromHtml` 不认识 `<table>/<tr>/<td>`，会把整张表拍扁成一行连续文本——这正是这段 KDoc
 *    开头举的"股东户数降幅榜"那种表格文章原本摘要就读不懂、抓全文本来想解决、结果换汤不换药的根因。
 *    [normalizeTableMarkup] 在丢给 `Html.fromHtml` 之前手动给每行插换行、每个单元格插分隔，转完的
 *    表格至少能按行按列看清楚。
 */
object EastmoneyArticleSource {

    /** 从整页 HTML 里切出正文片段（还带标签，没转义）。找不到边界就返回 `null`，不猜着截一半。 */
    internal fun extractArticleHtml(pageHtml: String): String? {
        val markerIndex = pageHtml.indexOf(ContentBodyMarker)
        if (markerIndex == -1) return null
        val tagEnd = pageHtml.indexOf('>', markerIndex)
        if (tagEnd == -1) return null
        val bodyStart = tagEnd + 1
        val bodyEnd = findMatchingDivEnd(pageHtml, bodyStart)
        if (bodyEnd == -1 || bodyEnd <= bodyStart) return null
        return pageHtml.substring(bodyStart, bodyEnd)
    }

    /**
     * `bodyStart` 已经在 `id="ContentBody"` 那个 `<div ...>` 标签内部（深度记为 1）。从这里往后扫
     * `<div`/`</div>`，深度归零那一刻，就是 ContentBody 自己配对的 `</div>` 起始位置——这是它自己的
     * 结构边界，不用猜后面跟着什么兄弟标签或碰巧同名的 class。找不到配对就返回 -1。
     */
    internal fun findMatchingDivEnd(html: String, bodyStart: Int): Int {
        val tagRegex = Regex("</?div\\b", RegexOption.IGNORE_CASE)
        var depth = 1
        for (match in tagRegex.findAll(html, bodyStart)) {
            if (match.value.startsWith("</")) {
                depth--
                if (depth == 0) return match.range.first
            } else {
                depth++
            }
        }
        return -1
    }

    /**
     * 表格行/单元格转成换行和分隔符，转换后再丢给 `Html.fromHtml`。对没有表格的普通文章是空操作
     * （两条正则都匹配不到东西）。已经用真实的"股东户数降幅榜"表格文章验证过转换效果：原本拍扁成
     * 一行的数字乱码，转完之后能看清楚是"代码 简称 最新股东户数 ... 行业"这样一行一条记录。
     */
    internal fun normalizeTableMarkup(fragmentHtml: String): String =
        fragmentHtml
            .replace(Regex("(?i)<tr[^>]*>"), "<br>")
            .replace(Regex("(?i)</t[hd]>"), "  ")

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
            ?: return null.also { Log.w(TAG, "fetch failed (ContentBody div boundary not found, template may have changed): $secureUrl") }
        val normalized = normalizeTableMarkup(fragment)
        val text = Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString().trim().takeIf { it.isNotBlank() }
        if (text == null) Log.w(TAG, "fetch failed (extracted fragment was blank after stripping tags): $secureUrl")
        return text
    }
}
