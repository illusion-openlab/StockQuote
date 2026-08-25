package tech.illusion.stockquote.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EastmoneyArticleSourceTest {

    @Test
    fun `extractArticleHtml cuts the fragment between ContentBody and the sm footer marker`() {
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
    fun `extractArticleHtml returns null when ContentBody marker is missing`() {
        assertNull(EastmoneyArticleSource.extractArticleHtml("<div>没有正文容器的页面</div>"))
    }

    @Test
    fun `extractArticleHtml returns null when the sm footer marker never appears`() {
        val page = """<div id="ContentBody"><p>正文一直到页面结束都没有 footer 边界。</p>"""
        assertNull(EastmoneyArticleSource.extractArticleHtml(page))
    }
}
