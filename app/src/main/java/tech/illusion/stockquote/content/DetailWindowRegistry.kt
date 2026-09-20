package tech.illusion.stockquote.content

import tech.illusion.stockquote.DETAIL_WINDOW_ID
import tech.illusion.stockquote.detailWindowTag

/**
 * 进程内的详情窗口账本（契约增量 v8）。
 *
 * **为什么必须自己记账**：Spatial SDK **没有**任何"列出当前已开窗口"或"查询 id+tag 是否已开"的公开 API。
 * 已核实 `platform-0.13.3-sources.jar` 的 `SpatialNavigator` 只有 `openWindowContainer` /
 * `closeWindowContainer` / `minimizeWindowContainer` / `restoreWindowContainer` 四个方法；
 * `SpatialContainerStateManager` 只暴露**当前这一个**容器的 `isFocused`/`isOnstage`/`isSighted`。
 * 唯一能看到全部已开窗口的地方是 `WindowContainer(placement = { ... })` 这个 lambda 的
 * `PlacementContext.containers`，而它只在新窗口创建时才被调用。
 *
 * 所以这个对象承担两件事：
 *  1. 判断某只股票是否已经有窗口（决定"新建"还是"拉到前台"）；
 *  2. 为新窗口挑选左/右侧与锚点，实现左右均匀分布。
 *
 * **线程模型**：开窗、placement 求值、`DisposableEffect` 都在主线程，但 placement lambda 由 SDK 调用，
 * 不保证一定同线程，所以所有可变状态都用 `@Synchronized` 保护，`pending*` 用 `@Volatile`。
 *
 * 每个 `WindowContainer` 绑定独立 Activity，但都在**同一个进程**里（和 `StockRepository` 一样），
 * 所以单例可以跨窗口共享。
 */
internal object DetailWindowRegistry {

    enum class Side { LEFT, RIGHT }

    /** 每只已开窗股票在哪一侧。 */
    private val sideOf = LinkedHashMap<String, Side>()

    /**
     * 每侧的开窗顺序，**末尾 = 最外侧**。新窗口锚在这里的最后一个上，于是同侧窗口一个接一个往外排。
     * 不能直接用 [sideOf] 的插入序推导：那是全局顺序，拿不到"左侧最外面那个是谁"。
     */
    private val orderOf = mapOf(Side.LEFT to mutableListOf<String>(), Side.RIGHT to mutableListOf())

    // ── placement lambda 读的三个"待用"值 ────────────────────────────────────────
    // placement lambda 拿不到正在创建的那个容器的 tag/bundle（`PlacementContext` 只有 containers），
    // 所以由调用方在 open 之前把本次决策放在这里，lambda 再读走。

    /** true 表示本次 open 是同 tag 复用（拉前台），lambda 必须返回 `Placement.none()`。 */
    @Volatile
    var pendingIsReuse: Boolean = false
        private set

    /** 本次新窗口落在哪一侧。 */
    @Volatile
    var pendingSide: Side = Side.RIGHT
        private set

    /** 本次新窗口锚在哪只股票的窗口上；null 表示锚主窗口。 */
    @Volatile
    var pendingAnchorCode: String? = null
        private set

    /**
     * 本次正在打开的那只股票。
     *
     * placement lambda 里做对账时**必须**把它排除在"清理陈旧条目"之外：[prepareOpen] 已经把它记进账本，
     * 但它的容器还没创建完，不会出现在 `PlacementContext.containers` 里——不排除的话会被当成陈旧条目
     * 当场删掉，账本立刻就是错的。
     */
    @Volatile
    var pendingCode: String? = null
        private set

    @Synchronized
    fun isOpen(code: String): Boolean = code in sideOf

    /**
     * 在调用 `openWindowContainer` **之前**调用，决定本次是复用还是新建，并把 placement 决策写进
     * `pending*`。
     *
     * 为什么"新建"要在这里就记账，而不是等窗口内容的 `DisposableEffect` 注册：placement lambda 紧跟着
     * open 调用同步执行，那时窗口内容还没组合，`DisposableEffect` 还没跑。账本必须在 open 之前就是对的。
     */
    @Synchronized
    fun prepareOpen(code: String) {
        pendingCode = code
        if (code in sideOf) {
            pendingIsReuse = true
            return
        }
        pendingIsReuse = false

        val left = orderOf.getValue(Side.LEFT)
        val right = orderOf.getValue(Side.RIGHT)
        // 哪侧少放哪侧；相等取右（契约 v8 §2）。关掉中间某个窗口后计数变化，下一个自动补到少的那侧。
        val side = if (left.size < right.size) Side.LEFT else Side.RIGHT

        pendingSide = side
        // 该侧最外侧的那个；该侧为空则锚主窗口（null）
        pendingAnchorCode = orderOf.getValue(side).lastOrNull()

        sideOf[code] = side
        orderOf.getValue(side).add(code)
    }

    /** 详情窗口内容释放时调用（窗口被关掉）。 */
    @Synchronized
    fun unregister(code: String) {
        val side = sideOf.remove(code) ?: return
        orderOf.getValue(side).remove(code)
    }

    /**
     * 用 placement lambda 里拿到的**权威**窗口列表校正账本，自愈漏掉的注销。
     *
     * @param liveCodes 当前真实存在的详情窗口对应的股票代码
     * @param keep 正在创建、还没出现在 `containers` 里的那只，必须保留
     */
    @Synchronized
    fun reconcile(liveCodes: Set<String>, keep: String?) {
        val stale = sideOf.keys.filter { it != keep && it !in liveCodes }
        stale.forEach { code ->
            val side = sideOf.remove(code) ?: return@forEach
            orderOf.getValue(side).remove(code)
        }
    }

    /** 从 `ContainerProxy.tag` 反解股票代码；不是详情窗口的 tag 返回 null。 */
    fun codeOfTag(id: String, tag: String?): String? {
        if (id != DETAIL_WINDOW_ID || tag == null) return null
        // 用 detailWindowTag 自己生成的前缀反解，避免两处各写一份 "detail-" 字面量
        val prefix = detailWindowTag("")
        return tag.removePrefix(prefix).takeIf { it != tag && it.isNotEmpty() }
    }
}
