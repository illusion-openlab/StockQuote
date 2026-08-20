package tech.illusion.stockquote.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChartAxis] 是主图和四个副图**共用**的横轴几何。这里锁死的核心不变量是**对齐**：
 * 同一个 bar 下标，在宽度相同的任意两块画布上必须落在同一个 x。
 *
 * 为什么值得测：副图原本各自写死 `padRight = 6f`，而主图右侧要留价格刻度槽（按标签字号算），
 * 于是同一根 bar 在上下面板里错开了十几个像素——十字光标的竖线就贯穿不成一条直线。
 * 这条性质是肉眼在透视截图里判不出来的（AGENTS.md 记过这条限制），只能靠单测守住。
 */
class ChartAxisTest {

    private val labelSize = 24f // 9sp @ ~2.67x density，取整方便手算
    private val width = 800f

    @Test
    fun `same index lands on the same x for every panel of equal width`() {
        // 三块画布只有"画什么"不同，几何一律走 ChartAxis.of，所以 x 必须逐位相同
        val price = ChartAxis.of(width, labelSize, count = 60)!!
        val volume = ChartAxis.of(width, labelSize, count = 60)!!
        val macd = ChartAxis.of(width, labelSize, count = 60)!!

        for (index in listOf(0, 1, 17, 42, 59)) {
            assertEquals(price.x(index), volume.x(index), 0f)
            assertEquals(price.x(index), macd.x(index), 0f)
        }
    }

    @Test
    fun `right gutter is reserved so the price scale never overlaps the plot`() {
        val axis = ChartAxis.of(width, labelSize, count = 10)!!
        assertEquals(6f, axis.padLeft, 0f)
        assertEquals(labelSize * 3.4f, axis.padRight, 0f)
        // 最后一根的中心必须落在刻度槽左侧
        assert(axis.x(9) < width - axis.padRight) { "末根 ${axis.x(9)} 侵入了右侧刻度槽" }
    }

    @Test
    fun `indexAt is the inverse of x`() {
        val axis = ChartAxis.of(width, labelSize, count = 40)!!
        for (index in 0 until 40) {
            assertEquals(index, axis.indexAt(axis.x(index)))
        }
    }

    /** 点到绘图区外（左边距里、右侧刻度槽里）也要给出最近的一根，而不是返回 null 让点击失效。 */
    @Test
    fun `indexAt clamps outside the plot instead of failing`() {
        val axis = ChartAxis.of(width, labelSize, count = 40)!!
        assertEquals(0, axis.indexAt(-500f))
        assertEquals(0, axis.indexAt(0f))
        assertEquals(39, axis.indexAt(width))
        assertEquals(39, axis.indexAt(99999f))
    }

    @Test
    fun `degenerate canvases yield no axis instead of dividing by zero`() {
        assertNull(ChartAxis.of(width, labelSize, count = 0))
        assertNull(ChartAxis.of(0f, labelSize, count = 10))
        // 宽度还不够容纳左右边距时也要放弃这一帧，而不是给出负的 slot
        assertNull(ChartAxis.of(labelSize * 3.4f, labelSize, count = 10))
    }

    /** 窗口越密，一根越窄——步进按钮存在的理由：220 根时一根不到 4px，靠注视点点不准。 */
    @Test
    fun `slot narrows as the window gets denser`() {
        val sparse = ChartAxis.of(width, labelSize, count = 22)!!
        val dense = ChartAxis.of(width, labelSize, count = 220)!!
        assert(dense.slot < sparse.slot)
        assert(dense.slot < 4f) { "220 根档位下一根 ${dense.slot}px，若已足够宽则步进按钮的理由需要重新评估" }
    }
}
