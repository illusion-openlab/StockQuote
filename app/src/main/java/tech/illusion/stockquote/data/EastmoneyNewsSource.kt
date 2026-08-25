package tech.illusion.stockquote.data

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder

private const val JsonpCallback = "jQuery_stockquote"
private const val TAG = "EastmoneyNewsSource"

/**
 * 个股新闻，走东财新闻搜索引擎的逆向接口（`search-api-web.eastmoney.com/search/jsonp`）——见设计文档
 * `docs/superpowers/specs/2026-08-25-stockquote-stock-news-panel-design.md`。无官方文档、无 SLA。
 *
 * 请求参数、JSONP 包裹格式、`<em>` 高亮标签，全部是 2026-08-25 用 curl 对 600519 实测抓包核实的，
 * 不是照 AKShare 文档转述猜的——那份文档说的 `so.eastmoney.com/news/s` 实际是个 SPA 页面，真正的数据
 * 来自这个不同域名下的接口。
 */
object EastmoneyNewsSource {

    /**
     * `pageSize` 上限 20，对应 AKShare 转述的"限量最近 20 条"；但接口本身的 `sort=default` **不是
     * 按时间倒序**——实测 600519 的 10 条结果里日期是 08-15/08-21/08-21/08-24/08-21 这种跳着来的
     * 顺序，调用方必须自己按 `publicTime` 倒序排（见 [fetchNews]）。
     */
    internal fun buildNewsUrl(code: String, pageSize: Int = 20): String {
        val param = "{\"uid\":\"\",\"keyword\":\"$code\",\"type\":[\"cmsArticleWebOld\"]," +
            "\"client\":\"web\",\"clientType\":\"web\",\"clientVersion\":\"curr\"," +
            "\"params\":{\"cmsArticleWebOld\":{\"searchScope\":\"default\",\"sort\":\"default\"," +
            "\"pageIndex\":1,\"pageSize\":$pageSize,\"preTag\":\"<em>\",\"postTag\":\"</em>\"}}}"
        val encoded = URLEncoder.encode(param, "UTF-8")
        return "https://search-api-web.eastmoney.com/search/jsonp?cb=$JsonpCallback&param=$encoded"
    }

    /** 响应是 `jQuery_stockquote({...})` 这种 JSONP 包裹，回调名是我们自己传的固定字符串，直接裁掉。 */
    internal fun stripJsonpWrapper(body: String): String? {
        val prefix = "$JsonpCallback("
        if (!body.startsWith(prefix) || !body.endsWith(")")) return null
        return body.removePrefix(prefix).removeSuffix(")")
    }

    /**
     * 接口给标题/摘要里命中关键词的每个字符单独包一层 `<em>...</em>`（哪怕是同一个数字的相邻两位），
     * 实测发现给 `preTag`/`postTag` 传空字符串并不能关掉这个行为，只能客户端清理。
     */
    internal fun stripHighlightTags(text: String): String =
        text.replace("<em>", "").replace("</em>", "")

    /** `null` = 请求或解析失败；空列表 = 请求成功但这只股票目前没有相关新闻命中。 */
    fun fetchNews(code: String): List<StockNews>? {
        val body = httpGet(buildNewsUrl(code), referer = "https://so.eastmoney.com/") ?: return null
        val json = stripJsonpWrapper(body) ?: return null
        return runCatching {
            val root = JSONObject(json)
            if (root.optInt("code") != 0) return@runCatching emptyList()
            val result = root.optJSONObject("result") ?: return@runCatching emptyList()
            val array = result.optJSONArray("cmsArticleWebOld") ?: return@runCatching emptyList()
            (0 until array.length())
                .mapNotNull { i -> array.optJSONObject(i)?.let(::parseNewsItem) }
                .sortedByDescending { it.publicTime } // 接口的 sort=default 不是时间倒序，见 buildNewsUrl 的注释
        }.onFailure { Log.w(TAG, "$code news: parse failed", it) }.getOrNull()
    }

    private fun parseNewsItem(item: JSONObject): StockNews? {
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
        return StockNews(
            title = stripHighlightTags(title),
            content = stripHighlightTags(item.optString("content")),
            publicTime = item.optString("date"),
            url = item.optString("url"),
        )
    }
}
