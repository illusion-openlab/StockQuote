package tech.illusion.stockquote.data

/**
 * 一只股票相对自选列表的状态（契约 v5 §5）。
 *
 * 刻意不含 Android / Compose 依赖：`StockRepository` 是带 Context 和快照状态的单例，把判定留在
 * 那里就没法写 JVM 单测。这里只放纯函数，Repository 负责持有可变状态并调用它们。
 */
enum class Membership { NotAdded, Adding, Added, Failed }

/**
 * 由"在不在自选表里" + "有没有正在飞/失败的操作"推导出**唯一**状态。
 *
 * "在表里"压过一切残留 pending —— 这条规则以前是 `QueryScreen` 里 when 分支的书写顺序，
 * 换个顺序就会重现"加完之后变回未追踪"的 bug。现在它是一条有单测守着的规则。
 */
fun resolveMembership(inWatchlist: Boolean, pending: Membership?): Membership = when {
    inWatchlist -> Membership.Added
    pending == Membership.Adding -> Membership.Adding
    pending == Membership.Failed -> Membership.Failed
    else -> Membership.NotAdded
}

/** 移除时的撤销快照：股票本身 + 它原来的排序位。 */
data class RemovedSnapshot(val stock: Stock, val orderIndex: Int)

/**
 * 把撤销快照按**原** `orderIndex` 插回，返回按 `orderIndex` 排好的列表。
 *
 * 不能简单写 `current + stock`：那会落到末尾，用户看到的是"撤销成功了但位置变了"。
 * 已经在表里就原样返回（幂等），避免重复点撤销插进两条。
 */
fun restoreRemoved(current: List<Stock>, snapshot: RemovedSnapshot): List<Stock> =
    if (current.any { it.code == snapshot.stock.code }) {
        current
    } else {
        (current + snapshot.stock.copy(orderIndex = snapshot.orderIndex)).sortedBy { it.orderIndex }
    }
