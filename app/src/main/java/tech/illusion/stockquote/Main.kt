package tech.illusion.stockquote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import tech.illusion.stockquote.content.DetailScreen
import tech.illusion.stockquote.content.DetailWindowRegistry
import tech.illusion.stockquote.content.StockQuoteColorScheme
import tech.illusion.stockquote.content.WatchlistHost

/**
 * 详情窗口的 WindowContainer id。**声明一次，可以开出任意多个实例**——实例是由 `tag` 区分的，
 * 不需要为每只股票再声明一个 container（见 [detailWindowTag]）。
 */
const val DETAIL_WINDOW_ID = "DetailWindow"

/**
 * 每只股票一个 tag，于是每只股票一个独立窗口。
 *
 * `SpatialNavigator.openWindowContainer` 的 KDoc 原文：tag 为 null 时**每次都新建**一个实例；
 * tag 非 null 时，已开着的同 id 同 tag 实例会被**复用**，换一个新 tag 则又是一个新实例。
 * 所以用 `detail-$code`：
 *  - 不同股票 → 不同 tag → 各自一个窗口，可以并排比较（用户 2026-08-11 要求）
 *  - 同一只股票再点一次 → 同 tag → 复用那个窗口，不会开出重复的第二个
 *
 * ⚠️ 同理，关窗口时**必须带上 tag**：`closeWindowContainer(id, tag = null)` 会把该 id 下**所有**
 * 窗口全关掉——早先"只能开一个"就是这么来的（每次开新窗口前先无差别关一次）。
 */
fun detailWindowTag(code: String): String = "detail-$code"

/**
 * 详情窗口尺寸，和主窗口右侧详情区**保持一致**（用户 2026-08-11：独立窗口尺寸过小，起码要和主窗口
 * 的详情视图一样高）。
 *  - 宽 680 = 主窗口 1080 − 左侧列表 400（`ListPaneWidth`）
 *  - 高 660 = 主窗口高度（manifest 的 `defaultsize`）
 * 这三个数是耦合的：改任何一个都要同步另外两个，否则独立窗口里的详情会和分栏里的排版不一样。
 */
private val DetailWindowWidth = 680.dp
private val DetailWindowHeight = 660.dp

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = StockQuoteColorScheme) {
                // 契约 v5 §1：manifest 已把 materialbackground 关成 0，窗口自己不再有毛玻璃底，
                // 所以必须在根部铺一层不透明底色，否则整块面板是全透的。
                Box(modifier = Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillPrimary)) {
                    WatchlistHost()
                }
            }
        }
        WindowContainer(
            id = DETAIL_WINDOW_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = DetailWindowWidth, height = DetailWindowHeight),
            // DSL 声明的窗口有这个参数（foundation/dsl/WindowContainer.kt:105）；主窗口没有，
            // 只能走 manifest。两处写法不一样，别以为有统一 API。
            enableMaterialBackground = false,
            // 契约增量 v6 → v8：新开的详情窗口在主窗口**左右两侧均匀分布**（v6 只往右排，用户反馈
            // "当前一直在右侧不太方便"）。哪侧少放哪侧、相等取右，锚在该侧最外侧那个窗口上。
            //
            // 位置只能在**声明侧**给：`openWindowContainer(id, tag, bundle)` 没有位置参数。
            // 这个 lambda 在每次 open 调用时求值（`SpatialContainerApis.kt:389`，在新容器参数构建
            // **之前**，所以 containers 里不含正在创建的这一个）。
            //
            // ⚠️ **同 tag 复用（拉前台）那次也会走到这里**——open 路径无条件调用
            // `toSpatialContainerParams`，而 placement 就在它里面。lambda 又拿不到本次的 tag，
            // 所以由 `DetailWindowRegistry.prepareOpen` 在调用前置 `pendingIsReuse`；
            // 见到它必须返回 `none()`，否则用户拖到别处的窗口会被拉回原位。
            placement = {
                // 先用权威快照对账，自愈漏掉的注销（见 DetailWindowRegistry.reconcile 的 KDoc）
                val liveCodes = containers.mapNotNull {
                    DetailWindowRegistry.codeOfTag(it.id, it.tag)
                }.toSet()
                // keep 必须是本次正在打开的那只：prepareOpen 已把它记进账本，但它的容器还没建好、
                // 不在 containers 里，不排除就会被当场清掉（见 pendingCode 的 KDoc）。
                DetailWindowRegistry.reconcile(liveCodes, keep = DetailWindowRegistry.pendingCode)

                if (DetailWindowRegistry.pendingIsReuse) {
                    Placement.none()
                } else {
                    val anchorCode = DetailWindowRegistry.pendingAnchorCode
                    val anchor =
                        if (anchorCode != null) {
                            // 账本说锚在某个详情窗上；万一它已经不在了（账本脏），退回主窗口
                            containers.firstOrNull { it.tag == detailWindowTag(anchorCode) }
                                ?: containers.firstOrNull { it.id != DETAIL_WINDOW_ID }
                        } else {
                            containers.firstOrNull { it.id != DETAIL_WINDOW_ID }
                        }
                    val orientation =
                        if (DetailWindowRegistry.pendingSide == DetailWindowRegistry.Side.LEFT) {
                            Placement.Orientation.Left
                        } else {
                            Placement.Orientation.Right
                        }
                    // offset 不传：KDoc 只说"从原始对齐位置的偏移"，沿哪个轴、正负方向都没写明，
                    // 没实测过就不猜数值，用系统默认间距（实测落地为 placementOffset=112）。
                    anchor?.let { Placement.placement(it, orientation) } ?: Placement.none()
                }
            },
        ) {
            val code = bundle?.getString("code")
            // 契约 v8 §3：窗口被关掉时把它从账本里注销。SDK 没有"窗口关闭"回调，能拿到的最接近的
            // 信号就是这个容器内容的 composition 被释放（用户从系统标题栏关窗 → Activity 销毁 →
            // 释放）。已知局限：`app stop` 是强杀，onDispose 不会跑——但那时整个进程连账本一起没了，
            // 不留脏数据。真正的残留风险与自愈手段见 placement lambda 里的 reconcile。
            if (code != null) {
                DisposableEffect(code) {
                    onDispose { DetailWindowRegistry.unregister(code) }
                }
            }
            PicoTheme(colorScheme = StockQuoteColorScheme) {
                Box(modifier = Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillPrimary)) {
                    // 窗口壳层负责把 bundle 解成 code；DetailScreen 本身不认识 Bundle，这样主窗口分栏区
                    // 才能用同一个 Composable（详情页设计统一，见 DetailScreen 的 KDoc）。
                    DetailScreen(code = code)
                }
            }
        }
    }
