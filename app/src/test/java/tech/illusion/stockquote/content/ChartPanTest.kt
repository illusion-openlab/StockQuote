package tech.illusion.stockquote.content

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ChartPan] 的像素↔根数换算。这部分是纯逻辑，可以完整测；**不能**测的是 SDK 到底会不会把拖拽事件
 * 送进来（那要真机上手，`adb shell input swipe` 送不到 spatial 面板里，实测两张截图逐字节相同）。
 * 所以这里把能测的全测死：方向、亚根余量累积、边界夹紧。
 */
class ChartPanTest {

    private fun pan(slot: Float = 10f, maxOffset: Int = 100) = ChartPan().apply {
        // slotPx 是从绘制期回填的几何里读的，测试直接塞一份等价几何
        axis = ChartAxis(padLeft = 0f, padRight = 0f, slot = slot, count = 500)
        maxOffsetBars = maxOffset
    }

    /** 正的位移 = 手往右拖 = 把内容往右推 = 露出更早的历史。 */
    @Test
    fun `dragging right walks back into history`() {
        val p = pan()
        p.onDragPx(35f) // 3.5 根
        assertEquals(3, p.offsetBars)
    }

    @Test
    fun `dragging left returns toward the latest bar`() {
        val p = pan()
        p.onDragPx(50f)
        assertEquals(5, p.offsetBars)
        p.onDragPx(-20f)
        assertEquals(3, p.offsetBars)
    }

    /** 慢拖：每次都不足一根。余量必须累积，否则 `toInt()` 每次抹成 0，永远不动。 */
    @Test
    fun `sub-bar drags accumulate instead of being discarded`() {
        val p = pan()
        repeat(4) { p.onDragPx(3f) } // 4 × 0.3 根 = 1.2 根
        assertEquals(1, p.offsetBars)
        repeat(3) { p.onDragPx(3f) } // 再 0.9 根，累计 2.1
        assertEquals(2, p.offsetBars)
    }

    @Test
    fun `offset clamps at both ends`() {
        val p = pan(maxOffset = 5)
        p.onDragPx(1000f)
        assertEquals(5, p.offsetBars) // 翻不过历史起点
        p.onDragPx(-1000f)
        assertEquals(0, p.offsetBars) // 也翻不过最新一根
    }

    /** 序列比窗口还短（maxOffsetBars=0）时拖拽必须是彻底的 no-op，不能把偏移拖成负数或越界。 */
    @Test
    fun `no panning when the whole series fits in the window`() {
        val p = pan(maxOffset = 0)
        p.onDragPx(500f)
        assertEquals(0, p.offsetBars)
        assertEquals(0, p.visibleOffset())
    }

    /** slotPx 还没被绘制回填（或算成 0）时不能除零。 */
    @Test
    fun `zero slot width is ignored`() {
        val p = pan(slot = 0f)
        p.onDragPx(500f)
        assertEquals(0, p.offsetBars)
    }

    /** 切周期/区间/标的后归零，且累积的余量也要清掉——否则切完第一下拖拽会突然跳一段。 */
    @Test
    fun `reset clears both the offset and the pending remainder`() {
        val p = pan()
        p.onDragPx(37f)
        assertEquals(3, p.offsetBars)
        p.reset()
        assertEquals(0, p.offsetBars)
        p.onDragPx(3f) // 若残留 0.7 根余量，这一下就会凑够 1 根而错误地移动
        assertEquals(0, p.offsetBars)
    }

    /** 窗口缩小后旧偏移可能越界；组合期只读的 visibleOffset 必须夹紧，且不写回状态。 */
    @Test
    fun `visibleOffset clamps without mutating state`() {
        val p = pan(maxOffset = 100)
        p.onDragPx(500f)
        assertEquals(50, p.offsetBars)
        p.maxOffsetBars = 10 // 换了更长的区间档位，可翻范围变小
        assertEquals(10, p.visibleOffset())
        assertEquals(50, p.offsetBars) // 原始状态没被改写
    }
}
