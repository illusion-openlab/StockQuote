package tech.illusion.stockquote.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * K 线横向平移状态：可见窗口在整条周期序列上往历史方向滑多少根。
 *
 * **必须配 `detectSpatialDragGesture`，2D 手势通路在头显上一律无效。** 这一条是 2026-08-11 从真机
 * `ViewRootImpl` 的原始输入日志里量出来的，前两次基于源码推理的改法（`detectHorizontalDragGestures`、
 * `Modifier.scrollable`）都被这份数据否掉了：
 *
 * 一次会话 29 段完整按下序列、95 条空间事件，全部是 `kind=GAZE_PINCH`（眼动注视 + 手部捏合，不是射线）：
 *  - **`ScrollEvent3D` 有效 0 次** —— `Modifier.scrollable` 永远等不到滚动量。
 *  - **每段按下的 2D `ACTION_MOVE` 大多只有 1 次**，因为捏合拖拽时**注视点几乎不动**，2D 坐标就是注视点；
 *    手的位移只存在于 3D 字段里。所以任何基于 2D touch slop 的检测器（`detectHorizontalDragGestures`）
 *    都拿不到连续位移。
 *  - **`DragEvent3D` 有效 23 次** —— 真实的拖拽量只在这条空间通路上。
 *
 * `detectSpatialDragGesture` 读的正是 `getRuntimeDragData`（即 `DragEvent3D`），SDK 自带的 sample
 * `SpatialDragSampleForUI` 就是给 2D 面板用的。`targetedToEntity = null` 表示 2D/3D 事件都收。
 *
 * **为什么是一个类，而不是几个散放的 `remember { mutableStateOf(…) }`**：手势回调里绝不能捕获组合期
 * 算出来的局部值（pico-sdk-compose-pitfalls 陷阱 1/2），那些值会停在首帧那一份。把会变的量挂到这个
 * 身份稳定的对象上、让回调在**触发时刻**去读，就绕开了这个坑。
 *
 * 只有 [offsetBars] 需要驱动重组，所以只有它是 snapshot state。[slotPx] / [maxOffsetBars] 是给手势
 * 做像素↔根数换算的普通 var，由绘制和组合阶段回填；它们**不该**触发重组（[slotPx] 是绘制期写的，
 * 如果它是 snapshot state 就会绘制→重组→再绘制地自激）。
 */
class ChartPan {

    /** 距最右端（最新一根）的偏移，单位是 bar。0 = 贴最新。 */
    var offsetBars by mutableIntStateOf(0)
        private set

    /**
     * 绘制期回填的横轴几何。除了拖拽换算，十字光标把点击坐标换算成 bar 下标也读它——两处必须用**同一份**
     * 几何，否则拖拽的步长和点选的落点会对不上。普通 var，不能是 snapshot state（绘制期写入会自激）。
     */
    var axis: ChartAxis? = null

    /** 一根 bar 当前占的像素宽度。几何还没回填时是 0，[onDragPx] 会因此直接 no-op。 */
    val slotPx: Float get() = axis?.slot ?: 0f

    /** 还能往历史方向翻多少根 = 序列长度 − 可见根数。 */
    var maxOffsetBars: Int = 0

    /** 不足一根的余量。攒着，否则慢拖每次都被 `toInt()` 抹成 0，永远不动。 */
    private var pendingPx: Float = 0f

    /**
     * 手势回调入口，喂本次增量。
     *
     * **入参是手指（手部）位移**：正数 = 往右拖 = 把内容往右推 = 露出更早的历史，所以 [offsetBars] 变大。
     */
    fun onDragPx(dx: Float) {
        if (slotPx <= 0f || maxOffsetBars <= 0) return
        pendingPx += dx
        val steps = (pendingPx / slotPx).toInt()
        if (steps == 0) return
        pendingPx -= steps * slotPx
        offsetBars = (offsetBars + steps).coerceIn(0, maxOffsetBars)
    }

    /** 周期/区间/标的变了就回到最新端——旧偏移对新序列可能直接越界。 */
    fun reset() {
        offsetBars = 0
        pendingPx = 0f
    }

    /**
     * 组合期读取用的安全偏移。**只读不写**：在组合里写 snapshot state 会引起重组自激，
     * 真正的夹紧交给 [onDragPx]（它每次都按当期 [maxOffsetBars] 夹）。
     */
    fun visibleOffset(): Int = offsetBars.coerceIn(0, maxOffsetBars)
}
