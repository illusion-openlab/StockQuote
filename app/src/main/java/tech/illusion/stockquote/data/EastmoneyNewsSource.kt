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

    /**
     * 响应是 `jQuery_stockquote({...})` 这种 JSONP 包裹，回调名是我们自己传的固定字符串，直接裁掉。
     * 先 trim 掉首尾空白/换行，并去掉近乎通用的尾部 `;` 分号约定——两者都不是数据本身，只是包裹的一部分。
     */
    internal fun stripJsonpWrapper(body: String): String? {
        val trimmed = body.trim().removeSuffix(";").trim()
        val prefix = "$JsonpCallback("
        if (!trimmed.startsWith(prefix) || !trimmed.endsWith(")")) return null
        return trimmed.removePrefix(prefix).removeSuffix(")")
    }

    /**
     * 接口给标题/摘要里命中关键词的每个字符单独包一层 `<em>...</em>`（哪怕是同一个数字的相邻两位），
     * 实测发现给 `preTag`/`postTag` 传空字符串并不能关掉这个行为，只能客户端清理。
     */
    internal fun stripHighlightTags(text: String): String =
        text.replace("<em>", "").replace("</em>", "")

    /**
     * `null` = 请求失败、解析失败，或响应形状不符合预期（API 状态码非 0，或
     * `result`/`cmsArticleWebOld` 缺失——这是个逆向、无文档的接口，形状随时可能变）；
     * 空列表 = 响应形状正常、请求成功，但这只股票目前确实没有相关新闻命中。
     * 两者不能混为一谈：前者应该走 `NewsPanel` 的失败+重试态，混进空列表会让接口一旦改形状，
     * 所有股票都静默显示"暂无相关资讯"，且因为空列表会写进缓存导致同一次打开再也不会重试。
     */
    fun fetchNews(code: String): List<StockNews>? {
        val body = httpGet(buildNewsUrl(code), referer = "https://so.eastmoney.com/", timeoutMs = 20_000)
            ?: return null
        val json = stripJsonpWrapper(body) ?: return null
        return runCatching {
            val root = JSONObject(json)
            val apiCode = root.optInt("code", -1)
            if (apiCode != 0) {
                Log.w(TAG, "$code news: api code=$apiCode (expected 0)")
                return@runCatching null
            }
            val array = root.optJSONObject("result")?.optJSONArray("cmsArticleWebOld")
            if (array == null) {
                Log.w(TAG, "$code news: result/cmsArticleWebOld missing from response — API shape may have changed")
                return@runCatching null
            }
            (0 until array.length())
                .mapNotNull { i -> array.optJSONObject(i)?.let(::parseNewsItem) }
                .sortedByDescending { it.publicTime } // 接口的 sort=default 不是时间倒序，见 buildNewsUrl 的注释
        }.onFailure { Log.w(TAG, "$code news: parse failed", it) }.getOrNull()
    }

    /** `optString` 在字段是 JSON `null` 时会返回字面 4 个字符 `"null"`，这里统一清洗成空字符串。 */
    private fun JSONObject.optStringOrBlank(name: String): String =
        optString(name).takeIf { it.isNotBlank() && it != "null" } ?: ""

    private fun parseNewsItem(item: JSONObject): StockNews? {
        val title = item.optStringOrBlank("title").takeIf { it.isNotBlank() } ?: return null
        return StockNews(
            title = stripHighlightTags(title),
            content = stripHighlightTags(item.optStringOrBlank("content")),
            publicTime = item.optStringOrBlank("date"),
            url = item.optStringOrBlank("url"),
        )
    }
}
