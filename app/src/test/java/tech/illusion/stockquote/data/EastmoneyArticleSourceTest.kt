package tech.illusion.stockquote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EastmoneyArticleSourceTest {

    @Test
    fun `extractArticleHtml cuts the fragment at ContentBody's own matching closing div`() {
        val page = """
            <div class="topbox">...</div>
            <div class="txtinfos" id="ContentBody" style="margin-top:0;">
                <p>第一段正文。</p><p>第二段正文。</p>
            </div>
            <div class="sm">
                <div class="sublab">原标题：...</div>
            </div>
        """.trimIndent()

        val fragment = requireNotNull(EastmoneyArticleSource.extractArticleHtml(page))

        // 只断言"两段正文都在、footer 的原标题没混进来"，不锁死精确空白——trimIndent() 的空白折叠
        // 细节不是这个函数要保证的契约，Html.fromHtml 转纯文本时也会把这些空白重新规整掉。
        assertTrue(fragment.contains("第一段正文"))
        assertTrue(fragment.contains("第二段正文"))
        assertTrue(!fragment.contains("原标题"))
    }

    @Test
    fun `extractArticleHtml does not truncate when the real article body contains a nested class=sm element`() {
        // 这是复评发现的真实 bug 场景：正文内部（不是 footer）出现一个同名 class="sm"（比如作者行内
        // 标注、小字说明）。原来的字符串搜索会在这里提前截断且不报错；深度配对法必须正确跳过它，找到
        // ContentBody 自己真正的收尾 </div>。
        val page = """
            <div id="ContentBody">
                <p>第一段正文。</p>
                <div class="sm">这是正文内部的一个小字说明，不是 footer。</div>
                <p>第二段正文，在"伪 footer"之后，必须也被完整包含进来。</p>
            </div>
            <div class="sm">
                <div class="sublab">原标题：这才是真正的 footer</div>
            </div>
        """.trimIndent()

        val fragment = requireNotNull(EastmoneyArticleSource.extractArticleHtml(page))

        assertTrue(fragment.contains("第一段正文"))
        assertTrue(fragment.contains("这是正文内部的一个小字说明"))
        assertTrue(fragment.contains("第二段正文，在\"伪 footer\"之后"))
        assertTrue(!fragment.contains("原标题"))
    }

    @Test
    fun `extractArticleHtml returns null when ContentBody marker is missing`() {
        assertNull(EastmoneyArticleSource.extractArticleHtml("<div>没有正文容器的页面</div>"))
    }

    @Test
    fun `extractArticleHtml returns null when ContentBody's div is never closed`() {
        val page = """<div id="ContentBody"><p>正文一直到页面结束都没有配对的收尾 div。</p>"""
        assertNull(EastmoneyArticleSource.extractArticleHtml(page))
    }

    @Test
    fun `findMatchingDivEnd skips nested divs and stops at the depth-zero closing tag`() {
        val html = "<div id=\"x\"><div>nested</div><div>another <div>deeper</div></div></div><div class=\"after\">tail</div>"
        val bodyStart = html.indexOf('>', html.indexOf("id=\"x\"")) + 1
        // The outer id="x" div's own closing tag is the last of the three consecutive "</div>" right
        // before "<div class=\"after\"" (the first two close "deeper" and "another"); its start position
        // is exactly six characters ("</div>".length) before that next div's start.
        val expectedEnd = html.indexOf("<div class=\"after\"") - "</div>".length

        val end = EastmoneyArticleSource.findMatchingDivEnd(html, bodyStart)

        assertEquals(expectedEnd, end)
        assertEquals("</div><div class=\"after\"", html.substring(end, end + "</div><div class=\"after\"".length))
    }

    @Test
    fun `findMatchingDivEnd returns -1 when there is no matching closing div`() {
        val html = "<div id=\"x\"><div>nested but never closed"
        val bodyStart = html.indexOf('>', html.indexOf("id=\"x\"")) + 1

        assertEquals(-1, EastmoneyArticleSource.findMatchingDivEnd(html, bodyStart))
    }

    @Test
    fun `normalizeTableMarkup inserts row breaks and cell separators so rows don't run together`() {
        // 缩微版真实"股东户数降幅榜"表格结构（<table><tbody><tr><th>...</th></tr><tr><td>...</td></tr>）。
        val table = "<table><tbody>" +
            "<tr><th>代码</th><th>简称</th></tr>" +
            "<tr><td>301421</td><td>波长光电</td></tr>" +
            "<tr><td>300067</td><td>安诺其</td></tr>" +
            "</tbody></table>"

        val normalized = EastmoneyArticleSource.normalizeTableMarkup(table)
        // Html.fromHtml turns <br> into a real line break; a bare tag-strip regex doesn't, so mimic
        // that one conversion here before stripping the rest, matching what the real caller sees.
        val plain = normalized.replace("<br>", "\n").replace(Regex("<[^>]+>"), "").trim()
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals(listOf("代码  简称", "301421  波长光电", "300067  安诺其"), lines)
    }

    @Test
    fun `normalizeTableMarkup is a no-op for plain paragraph markup`() {
        val fragment = "<p>没有表格的普通段落。</p>"

        assertEquals(fragment, EastmoneyArticleSource.normalizeTableMarkup(fragment))
    }
}
