package tech.illusion.stockquote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class EastmoneyNewsSourceTest {

    @Test
    fun `buildNewsUrl encodes keyword and pageSize into the param payload`() {
        val url = EastmoneyNewsSource.buildNewsUrl("600519", pageSize = 20)

        assertTrue(url.startsWith("https://search-api-web.eastmoney.com/search/jsonp?cb=jQuery_stockquote&param="))
        val decodedParam = URLDecoder.decode(url.substringAfter("param="), "UTF-8")
        assertTrue(decodedParam.contains("\"keyword\":\"600519\""))
        assertTrue(decodedParam.contains("\"pageSize\":20"))
        assertTrue(decodedParam.contains("\"type\":[\"cmsArticleWebOld\"]"))
    }

    @Test
    fun `buildNewsUrl defaults pageSize to 20`() {
        val url = EastmoneyNewsSource.buildNewsUrl("600519")
        val decodedParam = URLDecoder.decode(url.substringAfter("param="), "UTF-8")
        assertTrue(decodedParam.contains("\"pageSize\":20"))
    }

    @Test
    fun `stripJsonpWrapper unwraps the fixed callback name`() {
        val wrapped = "jQuery_stockquote({\"code\":0})"
        assertEquals("{\"code\":0}", EastmoneyNewsSource.stripJsonpWrapper(wrapped))
    }

    @Test
    fun `stripJsonpWrapper returns null when the wrapper does not match`() {
        assertNull(EastmoneyNewsSource.stripJsonpWrapper("notTheCallback({\"code\":0})"))
    }

    @Test
    fun `stripJsonpWrapper tolerates a trailing semicolon and whitespace`() {
        val wrapped = "jQuery_stockquote({\"code\":0});\n"
        assertEquals("{\"code\":0}", EastmoneyNewsSource.stripJsonpWrapper(wrapped))
    }

    @Test
    fun `stripHighlightTags removes em wrapper without touching the rest of the text`() {
        // 这是 2026-08-25 对 600519 实测抓到的真实标题片段，接口对每个匹配到关键词的数字/字符
        // 单独包一层 <em>，不是整段包一次。
        val raw = "贵州茅台(<em>600519</em>.SH)：2<em>0</em>2<em>6</em>年中报净利润为44<em>5</em>.<em>1</em>7亿元"
        assertEquals("贵州茅台(600519.SH)：2026年中报净利润为445.17亿元", EastmoneyNewsSource.stripHighlightTags(raw))
    }
}
