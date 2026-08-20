package tech.illusion.stockquote

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import tech.illusion.stockquote.content.DetailScreen
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
                WatchlistHost()
            }
        }
        WindowContainer(
            id = DETAIL_WINDOW_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = DetailWindowWidth, height = DetailWindowHeight),
        ) {
            PicoTheme(colorScheme = StockQuoteColorScheme) {
                // 窗口壳层负责把 bundle 解成 code；DetailScreen 本身不认识 Bundle，这样主窗口分栏区
                // 才能用同一个 Composable（详情页设计统一，见 DetailScreen 的 KDoc）。
                DetailScreen(code = bundle?.getString("code"))
            }
        }
    }
