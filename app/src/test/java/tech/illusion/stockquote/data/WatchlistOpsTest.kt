package tech.illusion.stockquote.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这两个函数是"搜索列表和自选列表读同一份状态"的落点（契约 v5 §5.2）。
 *
 * 值得测的是**优先级**：旧代码 QueryScreen.kt:216-218 里那句"必须排在最前面：加完之后
 * adding/selected 都会被清空，不排在最前面就会落回下面的'未追踪'分支——这正是用户报告的那个 bug"
 * 说的就是这条规则，但它当时只是 when 分支的书写顺序，改一下顺序就会重现 bug。这里把它变成
 * 可执行的断言。
 */
class WatchlistOpsTest {

    private fun stock(code: String, order: Int) =
        Stock(code = code, name = "测试$code", hist = listOf(1.0, 2.0), orderIndex = order)

    @Test
    fun `already in watchlist wins over any leftover pending state`() {
        // 加成功之后 opStates 里可能还留着 Adding/Failed 残渣，"在表里"必须压过它们
        assertEquals(Membership.Added, resolveMembership(inWatchlist = true, pending = null))
        assertEquals(Membership.Added, resolveMembership(inWatchlist = true, pending = Membership.Adding))
        assertEquals(Membership.Added, resolveMembership(inWatchlist = true, pending = Membership.Failed))
    }

    @Test
    fun `pending state shows only when not in the watchlist`() {
        assertEquals(Membership.NotAdded, resolveMembership(inWatchlist = false, pending = null))
        assertEquals(Membership.Adding, resolveMembership(inWatchlist = false, pending = Membership.Adding))
        assertEquals(Membership.Failed, resolveMembership(inWatchlist = false, pending = Membership.Failed))
    }

    @Test
    fun `pending Added is never a thing on its own`() {
        // Added 只能由"在表里"推导出来，不允许 opStates 里塞一个 Added 就算数
        assertEquals(Membership.NotAdded, resolveMembership(inWatchlist = false, pending = Membership.Added))
    }

    @Test
    fun `undo puts the stock back at its original position, not at the end`() {
        val current = listOf(stock("A", 0), stock("C", 2), stock("D", 3))
        val restored = restoreRemoved(current, RemovedSnapshot(stock("B", 1), orderIndex = 1))

        assertEquals(listOf("A", "B", "C", "D"), restored.map { it.code })
        assertEquals(1, restored.first { it.code == "B" }.orderIndex)
    }

    @Test
    fun `undo is idempotent when the stock is already back`() {
        val current = listOf(stock("A", 0), stock("B", 1))
        val restored = restoreRemoved(current, RemovedSnapshot(stock("B", 1), orderIndex = 1))

        assertEquals(listOf("A", "B"), restored.map { it.code })
    }

    @Test
    fun `undo restores the snapshot's order index, not the live stock's`() {
        // 被移除的那只当时排在最前（置顶过，orderIndex 是负数），撤销必须还原到最前
        val current = listOf(stock("A", 0), stock("C", 2))
        val restored = restoreRemoved(current, RemovedSnapshot(stock("B", -1), orderIndex = -1))

        assertEquals(listOf("B", "A", "C"), restored.map { it.code })
    }
}
