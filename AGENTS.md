# AGENTS.md — StockQuote

## 这个项目是什么

`StockQuote`（包名 `tech.illusion.stockquote`）是一款 PICO Spatial SDK 空间股票行情应用（A 股，沪深）。
完整产品规划、澄清记录、数据源调研见
`/Users/zohar/WorkSpace/Project/PicoProjects/docs/superpowers/specs/2026-08-10-pico-stock-quote-app-design.md`
（下称"设计文档"）。**开始任何功能实现前先读这份文档**，尤其是它的"变更记录"——这份设计经过五轮用户澄清，
早期版本里出现过、后来被否掉的方案（Tab 分栏单窗口、Subwindow 伴生窗口、3D 蜡烛图/`Stage`）都在变更记录里
写明了否掉的原因，不要凭直觉把它们加回来。

**当前状态（2026-08-24，契约 v2 + v3 + v5 都已实施完毕）**：主窗口是**左右分栏**（v2：400dp 自选股列表 +
右侧详情区），点行选中、长按呼出上下文菜单（新窗口打开/置顶/移除）、右上角"在新窗口打开"弹独立窗口；
列表含编辑模式删除、**随时可点的表头排序**、**只接管左栏的搜索态**，自选股的**增删/顺序会真正持久化**
（`SharedPreferences`，已验证跨进程重启存活）。v2 走了 4 轮门禁 B，见下面"主窗口 v2 分栏改版验证"。
**契约 v5（"同花顺"深色改版，2026-08-24）已实施完毕，但还没有做过设备/模拟器截图验证**——改了什么、
还欠哪一步验证，见"下一步"第 14 条。
**详情窗口**（第二个独立 `WindowContainer`）已按**设计契约 v3** 改版完成并过了 5 轮门禁 B 截图验证：
周期 Tab 移除分时（只剩日K/周K/月K）、均线扩到 M5/M10/M20/M30/M180 + 图例数值条、成交量拆成独立面板
（带 M5/M10 量能均线）、K 线补右侧价格刻度/底部稀疏日期/自动极值标注、MACD/KDJ/RSI 面板标题栏显示最新值、
新增基本参数 3×3 网格 + "更多信息"折叠区、移除自绘关闭按钮。5 个指标 MA/MACD/KDJ/RSI/BOLL **均为真实计算**。
**市场数据**走 `StockDataRepository`（Eastmoney kline 主 → Eastmoney quote → Sina quote 三层降级）；
kline 现在解析真实 OHLCV+日期，但**那条链路至今没有一次被观测到成功过**（见下面"数据源"的纠正），当前
截图里的真实行情来自 Sina 快照。**语音控制还没有开始实现**——见本文件末尾"下一步"。

**详情页设计统一（用户 2026-08-11 裁决，别再按旧假设施工）**：不存在"独立窗口专用"的详情设计，全项目只有
一份详情实现 `DetailScreen`。这条**推翻了设计契约 v2 §1 第 3 条**那套"主窗口预览只放轻量版、完整指标留给
独立窗口"的分级设计。v2（主窗口左右分栏）本身**尚未确认、本轮完全没有触碰**；将来若落地，右侧预览区应当
直接复用同一个 `DetailScreen`，不要再造第二套。

## 为什么这么设计（摘自设计文档，含已核实的关键 API）

- **窗口结构：主窗口 + 独立的第二个 `WindowContainer`（不是 `Subwindow`）**。这是用户第四次澄清时明确要求
  并给出核心 API 后，通过 bundled 参考（`anything-to-spatial-app` 技能的 `window-container.md` /
  `window-model-decision.md`，`spatial-ui-design-style` 技能的 `spatial-capabilities.md`）交叉核实过的：
  - 两个窗口都在 `SpatialAppScope` 里用 Kotlin DSL 声明，**不是**在 manifest 里加 `<activity>`：
    ```kotlin
    fun mainApp(scope: SpatialAppScope) = with(scope) {
        DefaultWindowContainer { PicoTheme { WatchlistHost() } }
        WindowContainer(
            id = "DetailWindow",
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 640.dp, height = 480.dp),
        ) { PicoTheme { DetailScreen() } }
    }
    ```
  - 打开/关闭用 `LocalSpatialNavigator.current`（已读 `platform-0.13.3-sources.jar` 里
    `SpatialNavigator.kt` 的真实源码确认，不是猜的）：
    ```kotlin
    val navigator = LocalSpatialNavigator.current
    navigator.openWindowContainer("DetailWindow", tag = "detail", bundle = Bundle().apply { putString("code", stock.code) })
    navigator.closeWindowContainer("DetailWindow")   // 或无参 navigator.closeWindowContainer() 关闭"当前"这个窗口
    ```
    **`tag` 千万不要传 `stock.code`**——源码原文："当 tag 为 null 时总会创建新实例；tag 不为 null 且已有同
    id+tag 的窗口时会复用该实例；换一个新 tag 才会创建新实例"。如果按每只股票传不同 tag，点开不同股票会开出
    多个详情窗口实例，违反"同一时刻只保留一个详情窗口"的设计。本项目统一传常量 `DETAIL_WINDOW_TAG = "detail"`
    （定义在 `Main.kt`）。
  - **"复用同一 tag 的窗口实例时，内容会不会带着新 bundle 重新走一遍 composition"这件事，没有在真机/模拟器上
    实测确认过**——只从源码确认了 `WindowContainerScope.bundle` 是曾经打开时传入的值，但复用路径下 Compose
    是否会用新 bundle 重新触发 content lambda，源码层面看不出来。当时为了不依赖这条未验证的假设，开详情窗口曾改成保守写法：每次都先
    `closeWindowContainer(id)` 再 `openWindowContainer(id, tag, bundle)`。**这条后来被推翻并删掉了**——见
    文末"多个详情窗口靠 tag，不靠多声明 container"：不带 tag 的 `closeWindowContainer(id)` 会关掉该 id 下
    **所有**窗口，正是"只能开一个详情窗口"的直接原因。现在的实现是 `WatchlistHost.openInNewWindow`，
    只 open、不预先 close，用 `detailWindowTag(code)` 区分实例。
  - 每个 `WindowContainer` 绑定独立 Activity——**两个窗口之间不能假设共享 Compose 状态或内存对象引用**，
    自选股数据要通过进程内单例 Repository（`StockRepository`，已实现，见下方"持久化"）在两侧同步，不能靠
    直接传对象引用。
  - 同一时刻只保留一个详情窗口（不为每条自选股各开一个）。
- **不含 3D，不用 `Stage`**：3D 蜡烛图方案被用户明确否掉，所以选了 `planar` 模板而不是 `stage`/`volumetric`。
- **主窗口是左右分栏（设计契约 v2，2026-08-11 落地）**：`WatchlistHost` 是一个 `Row`——左边
  `WatchlistPane`（固定 400dp，对齐 v2 视觉稿的 `.list-pane`）+ `VerticalDivider` + 右边 `DetailPane`
  （`weight(1f)`）。主窗口 manifest 的 `defaultsize` 本来就是 1080×640dp，和视觉稿的 `.window` 尺寸一致，
  **没有改过尺寸**。
  - **右侧详情区渲染的就是那份唯一的 `DetailScreen`**（契约 v3 的完整版），不是轻量预览。v2 §1 第 3 条
    原本设计的"预览只画 MA5/MA10、完整指标留给独立窗口"那套分级**已被用户裁决推翻**，别照 v2 稿的
    `.legend` 文案施工。
  - **宿主级操作入口一律放在 `DetailScreen` 外面**（属于 `DetailPane` 这个宿主壳层）。放进 `DetailScreen`
    的话，独立窗口里也会冒出一个"在新窗口打开"，而那时你已经在新窗口里了。
  - **右上角是一颗 `⋯` 浮层按钮，不是独占一行的按钮**（2026-08-11 按用户反馈改）。原来那颗
    "⤢ 在新窗口打开"独占一行、白费约 40dp 高度；现在收进 `⋯` 菜单，三项 新窗口打开/置顶/移除。
    - **它和详情自己的价格数字都在右上角，必然重叠**——所以 `DetailScreen` 有个
      `headerEndInset: Dp = 0.dp` 参数，宿主把按钮宽度传进来（`MoreButtonInset = 52.dp`），头部行右侧让位；
      独立窗口没有这颗按钮，传默认 0。**动这颗按钮的尺寸时记得同步改 inset，否则价格会被压到按钮下面。**
    - **菜单三项和列表行长按菜单是同一个 `StockActionMenu`**，别在两处各写一份，否则将来只会改歪一个。
    - `Menu` 是 Popup，渲染在面板之外的独立层，锚在右上角**不会**被面板边缘裁切（已截图确认）。
  - **`sortKey`/`sortAscending` 和撤销条状态（`undo`/`undoGeneration`/`offerUndo`）都在 `WatchlistHost`
    层**，不在 `WatchlistPane` 里。原因：`置顶`/`移除` 有**三个**入口（列表行长按菜单、详情区 `⋯` 菜单、
    搜索结果行尾的四态钮），都必须共用同一份排序状态和同一条撤销条，否则会出现"从详情区置顶不清排序"或者
    冒出两条撤销条。（契约 v5 之前这里是 `pendingDeleteCode` + 删除确认 `AlertDialog`，已被撤销条取代。）
  - **切换选中股票时收起 `⋯` 菜单用 `LaunchedEffect(code)`，不要拿 `code` 当 `remember` 的 key**——SDK 的
    点击组件保留首次传入的 lambda，remember key 一变就会往已被丢弃的 `MutableState` 里写
    （`pico-sdk-compose-pitfalls` 陷阱 1）。
  - **`selectedCode` 提到 `WatchlistHost` 层**，并且是**派生值**而不是靠 `LaunchedEffect` 纠正：
    `watchlist.firstOrNull { it.code == selectedCode }?.code ?: watchlist.firstOrNull()?.code`。这样默认
    选中第一行、选中项被删除后自动落回第一行，都不需要额外同步逻辑，也不会出现指向已删除代码的空窗。
  - **点击列表行只是选中**（更新右侧详情区），不再弹独立窗口——弹窗口改由"在新窗口打开"按钮或长按菜单
    显式触发。
  - **长按列表行呼出上下文菜单**（新窗口打开/置顶/移除）。三个 API 都在 0.13.3 源码里核实过、不是臆造：
    `com.pico.spatial.ui.design.menu.Menu`/`MenuItem`、
    `com.pico.spatial.ui.foundation.gesture.detectTapGestures(context, targetedToEntity, onDoubleTap, onLongPress, onPress, onTap)`。
    **点击和长按必须写在同一个 `detectTapGestures` 里**——拆成两个 `pointerInput` 会互相抢事件消费，而这个
    SDK 签名本身就同时收三个回调。`targetedToEntity = null` 表示 2D/3D 事件都收（源码 KDoc 原文）。
    `Menu` 是 Popup，会定位到它所在的父布局，所以把它放进包着行的那个 `Box` 里就自动锚在被长按的行上。
  - **`MenuItem` 内部已经自带 `clickable + LocalIndication + spatialHoverEffect +
    controllerHapticFeedback`**（已读源码确认），菜单项不用自己补点击三件套。
  - **裸 `pointerInput` 不像 `clickable` 那样自带按压反馈**，所以行上的 `indication`/haptic 要靠
    `detectTapGestures` 的 `onPress` 手动 emit `PressInteraction.Press/Release/Cancel` 来驱动同一个
    `interactionSource`。`WatchlistRow` 里就是这么写的，照抄那个写法即可。
  - **搜索态只接管左栏**（契约 v5 §4，2026-08-24 落地，取代了 v2 时代 `QueryScreen` 的整窗接管）：
    `searchOpen` 为真时左栏那 400dp 换成 `SearchPane`，**右侧详情区原地不动**，继续显示当前选中的股票。
    这是相对整窗接管的核心收益——加完股票退出搜索，刚加的那只就在原地列表里，不会再出现"加完之后那只去哪了"。
  - **表头排序（名称/最新价/涨跌幅，降序→升序→取消三态）随时可点，不再受编辑模式限制**（契约 v5 §3.1）。
    编辑模式现在只剩一件事：为真时每行尾出现 `✕`。
  - **置顶**（`StockRepository.moveToTop`）把 `orderIndex` 压到当前最小值 − 1，**并且调用方要同时把
    `sortKey` 清成 null**（契约 v2 §3 第 2 条）：如果用户正按价格/涨跌幅排序，置顶只改默认顺序不会带来
    任何肉眼可见的位置变化，会显得"点了没反应"。清排序这件事留在 UI 侧，因为排序是 UI 状态。
  - **移除是即时的 + 一条 5 秒撤销条**（契约 v5 §6，取代了旧的二次确认 `AlertDialog`）。三个移除入口
    （编辑模式的 ✕、行长按菜单"移除"、详情区 `⋯` 菜单"移除"）都汇进 `WatchlistHost.onRemoved`。
    `StockRepository.remove(code)` 返回 `RemovedSnapshot`（股票 + 原 `orderIndex`），撤销按**原位**插回，
    不是追加到末尾（`restoreRemoved`，有单测守着）。**加入自选同样给撤销条**（"已加入「XX」"），撤销一次
    加入就是 `remove(code)`，复用同一套 `offerUndo`/`UndoBar`。撤销条是**浮层**（左栏那个 `Box` 的
    `align(Alignment.BottomCenter)`），不是 `Column` 的兄弟节点——写成兄弟节点会在它出现/消失时把整份列表
    顶上去又落回来。
    ⚠️ 撤销条必须 `key(undoGeneration) { UndoBar(...) }` 包一层，理由见 `UndoBar` 的 KDoc
    （`pico-sdk-compose-pitfalls` 陷阱 1：SDK 可点组件会保留**首次**传入的 lambda）。
  - 左栏顶部两行：第一行 `自选股` 标题 + 右侧"编辑"`ToggleButton`；第二行是一个**假装成搜索框的
    `Button`**（`🔍 代码 / 名称`），点了整栏切到 `SearchPane`。**刻意不放真 `SearchField`**：真输入框会把
    系统 IME 拉起来占掉半个视野，而这一步用户只是想"进搜索"，还没开始打字（真的 `SearchField` 在
    `SearchPane` 里）。
  - **搜索结果行的加/删收敛到行尾一颗四态钮 `MembershipToggle`**（契约 v5 §5.4）：
    `NotAdded ＋ / Adding ◌ / Added ✓ / Failed !`，四态一律留在琥珀色系里、只有形态在变。状态由
    `StockRepository.membershipOf(code)` 单一推导（`resolveMembership(inWatchlist, pending)`，"在自选表里"
    压过一切残留 pending，`WatchlistOpsTest` 锁了这条优先级），不再有"先点行选中、才冒出加入按钮"那个中间态。
    ⚠️ **调用方必须 `key(membership) { MembershipToggle(...) }` 包一层**，同样是陷阱 1：`onClick` 里
    `when (membership)` 会读到 SDK 保留下来的旧值，刚加完再点 ✓ 会走回 `onAdd()`。
- **指标**：免费数据源都不直接提供计算好的技术指标，指标计算全部本地实现（`data/StockMath.kt`），**全部 5 个
  指标 MA/MACD/KDJ/RSI/BOLL 现在都是真实计算，没有占位**（2026-08-11 完成，见"关键文件"里 `StockMath.kt`/
  `KdjChart.kt`/`RsiChart.kt`/`KLineChart.kt` 的说明）。
  - **周K/月K 是真实聚合，不再是视觉选中态**（2026-08-11，用户要求，作废契约 v3 §3 不做清单第 3 条）。
    `data/StockMath.kt` 的 `KPeriod` + `aggregateCandles`，通达信口径：开=区间首根的开、收=末根的收、
    高/低取极值、量/额求和、日期取**区间内最后一个交易日**（所以本周/本月未走完时末根就是一根正在形成的
    bar，和行情软件一致）。周线按 **ISO 周**分组，必须用 `WEEK_BASED_YEAR` 而不是 `date.year`，否则
    12 月底那几天会被归到"当年第 1 周"、和一月初的数据劈成两根（`AggregateCandlesTest` 锁了这个用例）。
    `buildCandles` 造的合成蜡烛没有日期，**整段原样返回**，绝不按下标"每 5 根算一周"编假周线。
  - **⚠️ 详情页的"今日"字段必须取日线末根，不能取周期序列末根**。头部涨跌幅、基本参数网格的
    今开/最高/最低/成交额描述的是今天这一天；切到月K时周期序列末根是**整月**的开高低量额，拿它填
    "今开"就是把本月的数字标成今天的。`DetailScreen` 里 `latestDaily`（日线）和 `candles`（周期切片）
    是两条独立的线，改这块时别把它们并回一条。
  - 已知限制（没修，不是回归）：指标在**可见窗口**上计算，所以窗口左边缘那几根天然没有 MA 值
    （M5 的头 4 根、MACD 的头 34 根）。横向拖拽上线后这个边缘更容易被看到。要修得把指标改成在整条
    序列上算、再和蜡烛用**同一个**窗口切片一起切（契约 §2 警告过平行数组只切一边会让日期和数据错位），
    并且 `KLineChart` 内部自己算 MA 的那段也要改成接收预算好的数组——是个独立改动，没顺手做。
  - **原"档位点数不够撑满指标窗口"的限制已在契约 v3 里解决**：`rangeOptions` 从 10/20/30 放大成
    **22/66/220**（按真实交易日近似），`refreshAll` 拉取天数 60→230。这是契约 v3 §2 经用户确认的数值组，
    直接目的是让新增的 M180 有意义，顺带让 BOLL/MA20 在"近3月/近1年"档位下能真正画出线。
  - **⚠️ MACD 柱是 `2 × (DIFF − DEA)`，那个 ×2 不能省**。通达信/同花顺一系的 A 股软件都这么画，而西式
    写法常常直接用 `DIFF − DEA`。本项目设计源是同花顺截图，用户 2026-08-11 明确确认按国内惯例。早先漏了
    ×2，柱高只有应有的一半（当时 0.86，应为 1.72）。改这个数会让柱子整体高一倍，**是刻意的，别当 bug 改回去**。
  - **⚠️ BOLL 标准差口径未定**：现在用总体标准差（`÷N`，Bollinger 原始定义），而通达信/同花顺的 `STD()`
    是样本标准差（`÷(N−1)`），带宽差约 2.6%。免费源不提供算好的 BOLL，没有参考值可实证，所以没凭猜改。
    要定就拿同花顺/通达信同一只股票同一天的上下轨数值对一下，改 `computeBoll` 一个除数即可。
  - **8 个指标的数值都和外部独立重算对账过**（2026-08-11，`ui-verification-log.md` 第 4/5 轮有对照表）：
    M5/M10/M20/M30 到小数点后两位完全一致，MACD/KDJ/RSI 差值 ≤0.1（现价在计算与截图之间跳动所致）。
    对账方法值得复用：用 `curl` 取同一份新浪日线，在 Python 里复刻 Kotlin 的管道（230 行 + 追加今日 bar +
    `takeLast(N)`）重算一遍，和截图上的数比。**M180 和量能均线只验了渲染，还没做数值对账。**
  - **指标的 `null` 前缀是特性，不是 bug，而且必须一路传到 UI**：`simpleMovingAverage`/`computeRsi`/
    `computeBoll` 在窗口填满之前返回 `null`。UI 侧（均线图例条、RSI 面板标题）遇到 `null` 显示
    `NoValue`（"—"），**不要**改成显示 0 或用前值填充——"近1月"档位下 M180 本来就没有值，显示"—"才是
    对的。
- **⚠️ 交易日基准是 `Asia/Shanghai`，不许用 `LocalDate.now()`**（2026-08-11，`data/MarketClock.kt`）。
  真机那台 PICO 的系统时区是 `America/New_York`，`date` 报 `Tue Aug 11 04:30 EDT`。上海和纽约横跨日界，
  从纽约的 00:00 到上海的同一时刻之间 `LocalDate.now()` 会比上海**慢一天**，而 A 股这一整个交易日
  （上海 09:30–15:00）恰好整段落在那个窗口里。后果是 `mergeLatestPrice` 认不出"历史末尾这根就是今天"，
  给同一个交易日再追加一根 bar：K 线尾部凭空多一天、日期还是昨天的。模拟器是 CST，所以这个 bug 在模拟器上
  永远不会暴露。交易日属于市场，不属于用户站在哪儿——`MarketClock.today()/todayString()` 是唯一入口。

- **数据源**：`StockDataRepository`（`data/StockDataRepository.kt`）已实现真实网络请求，Eastmoney 主
  + Sina 备用，失败自动降级，**已在模拟器上截图验证**（贵州茅台显示 1348.86/+3.03%，和手动 curl 验证的
  Eastmoney 实时行情接口返回值完全一致，和硬编码种子数据 1712.0 明显不同）：
  - **⚠️ 估值与盘口字段只有腾讯 `qt.gtimg.cn` 给（2026-08-11 接入）**。市值/流通市值/市盈率TTM/市净率/
    量比/换手率/涨停跌停/总股本/流通股本/内盘/外盘——新浪的行情接口一个都不给，东财 quote 有但整个会话
    从主机和设备两侧都不可达。所以 `fetchQuote` 的顺序是 **腾讯 → 东财 → 新浪**，退到后两家时这些字段
    会变回"—"。完整字段下标表在 `TencentSource.kt` 的 KDoc 里。
    - **⚠️ 市值单位是亿元不是元**（茅台 16832.35 = 1.68万亿）。用 `formatYi`，误用 `formatYuan`
      会差 8 个数量级、显示成"1.68万"。这是这次接入里最容易错的一处。
    - **⚠️ 腾讯只用快照，历史 K 线绝不能用它**：它的 kline 是**前复权**，而本项目历史走新浪**不复权**
      日线、与实时报价同口径。混用会在图上留台阶（茅台同一天两家差 3.6%）。它的**实时价**是不复权的，
      所以当报价源是安全的。
    - **字段位置是用算术恒等式钉死的，不是查文档或凭记忆**：涨停==昨收×1.10、内外盘之和==成交量、
      市值÷现价==股本、均价==成交额÷成交股数、振幅==(高−低)÷昨收。**两个反直觉的坑**：内外盘顺序靠
      "涨跌相反的两只股票"判定（涨势下外盘更大）得出 idx7=外盘/idx8=内盘；总股本与流通股本靠"有 H 股的
      招商银行"判定，结果 **idx73 才是总股本、idx72 是流通股本**，按"先总后流通"猜就错了。
    - **找不到校验恒等式的字段就不接**：每股收益TTM/每股净资产/ROE/市销率/股息率，以及响应里 idx63~66
      那几个数（1.37/3.86/30.53/26.78）——不知道对应什么就不贴标签，继续显示 `NoValue`。委比也留空，
      源只给了委差（idx50），算委比要委买/委卖总量。
  - **⚠️ 主历史源已换成新浪日线（2026-08-11）**，链路是
    **新浪日线 → 东财 kline → 东财 quote → 新浪 quote**。前两条给多日 OHLCV+日期，**后两条只给
    `[昨收, 现价]` 两个点**——"图上只有两根蜡烛"就是退到后两条时的真身，不是渲染 bug。
  - `SinaSource.fetchHistory`：`money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=sh600519&scale=240&ma=no&datalen=230`
    （`scale=240` = 240 分钟 = 日线）。**选它当主源是实测比较后的结论**，三条理由都可复现：
    ① `datalen` **真的被尊重**（要 230 返回正好 230 行、25KB；要 5 返回 5 行），而东财的 `lmt` 会被静默
    忽略、一次吐 5980 行约 360KB；② **具名 JSON 字段**（`day/open/high/low/close/volume`），不像东财那样
    是位置 CSV、而且它的列序是反直觉的 `date,open,close,high,low,…`（**收盘在高低之前**），列序一变就会
    静默把 close 塞进 high；③ **不复权**，和实时报价同口径——前复权历史拼上不复权的最新价会在图上留一个
    台阶，而东财 `fqt=1` 在早年历史上还会给出负价。**交叉验证**：600519 末行（2026-08-10）收盘 1348.86，
    与同一时刻实时报价给的昨收逐字相同。沪深两市代码都验过（`sh600519`/`sz000858`）。
    注意这个接口**不给成交额也不给名称**——成交额从 quote 快照来，名称本地已知。成交量单位是**股**，
    解析时 ÷100 换成手，和 `Candle.volume` 全项目统一。
  - `EastmoneySource.fetchHistory`：`push2his.eastmoney.com/api/qt/stock/kline/get`，现在解析真实
    OHLCV + 交易日期，退为第二源；`EastmoneySource.fetchQuote`：`push2.eastmoney.com/api/qt/stock/get`，
    只有最新价+昨收，没有历史。
  - **腾讯日线也实测可用**（`web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh600519,day,,,230,qfq`，
    HTTP 200，`qfqday` 数组 `[日期,开,收,高,低,量(手)]`，且**含今天这根**、值与实时报价逐字吻合）。
    没有接进来只是为了不堆第三个历史源；如果新浪哪天也不可用，这是已验证过的下一个候选。**它是前复权**，
    接的时候要注意上面第 ③ 条那个台阶问题。
  - **`StockRepository.refreshAll` 是小批量并发（3 个一批、批间 400ms），不是一次并发 12 个**。这些是免费
    无 SLA 接口、按 IP 限速，一次性打 12 个大响应请求正是 K 线时好时坏的最可能原因（同一天用 curl 快速
    连发也确实把出口 IP 打进过黑名单）。代价只有几百毫秒，换来"图上到底有没有 K 线"的确定性。
  - **`mergeLatestPrice` 必须按日期判断，不能无条件"替换最后一点"**：不同源的历史末尾落在不同的一天——
    东财 kline 带今天这根正在形成的 bar，**新浪日线停在上一个交易日**。对新浪那种形状还去替换最后一点，
    会把昨天的真实收盘悄悄覆盖掉并标成今天，既丢一个真实交易日又让 X 轴日期和数据错位。所以：末尾是今天
    → 更新那根；末尾是上一交易日且快照有真实开/高/低 → **新增**一根（自限，加完下次就走更新分支）；
    都不成立（只有 quote 兜底）→ 只推 hist 最后一点，不凭空造 bar。已截图验证：新浪历史到 08-10，而图上
    X 轴末尾是 08-11。
  - **⚠️ 纠正一条一直没被实证的说法**：本文件之前写"kline 取多日**真实历史**"，但真正被截图验证过的
    只有**报价**那条链路——原话"和手动 curl 验证的 Eastmoney **实时行情**接口返回值完全一致"，
    实时行情就是 quote，不是 kline。2026-08-11 的门禁 B 截图里详情窗口只画得出 **2 根蜡烛**，正是
    `[昨收, 现价]` 两个点的形状，说明当时喂给图表的数据来自 quote/新浪兜底，**kline 那条链路到今天
    仍然没有任何一次被观测到成功过**。别把它当成已验证的能力，接手时请先跑一次上面的 200 探测。
  - **kline 请求形状的两个实测结论**（2026-08-11，`beg=0` 那次唯一成功的 curl）：`lmt` 在带了显式
    `beg`/`end` 时**会被静默忽略**（`lmt=4` 返回了全部 5980 行，`dktotal=5980`）；且 `fqt=1`（前复权）
    在足够早的历史上会给出**负价**（600519 的 2001 年行是 `-312.68`），一行都能把整个价格轴拽走。
    现在的做法：优先请求按 `days` 折算的有界 `beg` 窗口（payload 小一个数量级），失败则退回唯一有过
    200 实证的 `beg=0`，解析时再 `takeLast(days)` 并丢掉非正价行。**有界 `beg` 这个形式至今没能联网
    验证过**（改动期间 IP 被封），所以那条 `?:` 兜底不要随手删掉。
  - `SinaSource.fetchQuote`：`hq.sinajs.cn/list=sh600519` 风格，GBK 编码，同样只有实时快照没有历史，是
    最后一层兜底。
  - 都是非官方免费接口，没有 SLA，参数可能随时变——解析逻辑隔离在各自的 `*Source.kt` 里，任何一层失败都
    静默降级（返回 `null`），不抛异常，调用方（`StockRepository`）保留上一次成功的数据不覆盖。
  - **已修正（2026-08-11）**：以前这里记着"只取了收盘价、丢弃真实开高低"的已知简化。现在
    `fetchHistory` 直接解析 kline 行的 `date,open,close,high,low,volume,amount`（注意**收盘价在高/低
    之前**，不是习惯的 OHLC 顺序），`RemoteStock.candles`/`Stock.candles` 带真实 OHLCV+日期，
    `buildCandles()` 只在"从来没抓到过任何 kline"的兜底路径上使用。
  - **合成数据不得以数字形式呈现**——这是这个 app 的一条硬规则，不只是风格问题。`buildCandles()` 造的
    `volume` 是 `abs(close-previous)*40+30+(index%5)*6`，纯粹是个形状；把它显示成"最新成交量"就是把
    编造值当实测值报出去。所以：成交量面板标题、基本参数网格的今开/最高/最低/成交额、K 线的极值标注，
    在没有真实 K 线时一律显示 `NoValue`（"—"）或干脆不标（见 `KLineOverlays.annotateExtremes`）。
    蜡烛本身可以是合成形状（那是占位图形），但**任何精确数字都必须有真实来源**。
  - **`SinaSource` 是目前唯一被实测验证过的完整快照来源**（2026-08-11 实测 HTTP=200）。它一行里就给了
    今开/昨收/现价/最高/最低/成交量(股)/成交额(元)，字段位置用"成交额÷成交量≈现价"反向核对过
    （2036498613 / 1513880 ≈ 1345.5，与现价 1346.48 只差一个跳动），映射表写在 `SinaSource.kt` 的
    KDoc 里。以前这里只取了昨收和现价，其余全丢；现在填进 `QuoteSnapshot`，撑起契约 v3 的基本参数网格。
    **注意单位**：新浪成交量是**股**，`QuoteSnapshot.volumeLots` 统一换算成**手**（÷100）和
    `Candle.volume` 对齐。
  - `StockRepository.refreshAll()`（一次性拉全量历史，`WatchlistHost` 里 `LaunchedEffect(Unit)` 触发一次）
    + `refreshWatchlistQuotes()`（自选股快照轮询，5 秒一次，只替换 `hist` 最后一个点，历史部分不动）——
    对应设计文档"行情快照 3–5 秒/次、K 线 1–5 分钟/次"，当前 K 线只在启动时拉一次，还没做分钟级重拉，
    如果要更实时的 K 线需要另加一个更低频的定时刷新。
  - **验证时的一个教训**：`adb logcat -d`（一次性 dump 当前缓冲区）在这台机器上**没能**捞到 `Log.d`/`Log.w`
    输出（哪怕是保证一定会打的兜底 `Log.w("...all sources failed")`），换成持续跟随的 `adb logcat`（不加
    `-d`）也一样没捞到，但**系统级** `NetworkWatchlistService: onDnsEvent` 日志和最终截图里的真实报价都
    证明网络请求确实发出且成功了——这台 Pico 系统 build 大概率对第三方 App 的 `Log.d`/`Log.w` 有静默限流/
    过滤，不代表代码没跑。**不要靠这台设备上的 `adb logcat` 判断第三方 App 的 debug 日志有没有打**，改用
    截图里的实际数值判断更可靠。
- **红涨绿跌**：中国大陆市场惯例（和欧美相反），UI 里任何涨跌色一定要按这个来，不要用国际默认的红跌绿涨。
- **"同花顺"深色不透明盘面配色（契约 v5 §1/§2，2026-08-24 取代了旧的"素雅"暖纸风）**：整套色仍然通过
  `PicoTheme(colorScheme = StockQuoteColorScheme)` 在 `Main.kt` 注入（`content/StockTheme.kt`），但色板换成
  了交易终端那一套深蓝灰底 + 琥珀金强调：`fillPrimary 0xFF0F1318`（面板基底）/ `fillSecondary 0xFF1C2530`
  （选中行底）/ `fillTertiary 0xFF151B22`（分区格子底）/ `fillLight 0xFF1A222C` / `labelPrimary 0xFFEDF1F6`
  …… / `interaction 0xFFE8B34A`（琥珀强调）/ `error 0xFFFF6B63`。仍然是**固定色而不是系统自适应**——这个
  app 要始终保持自己的视觉身份，不跟着头显深浅色偏好变（和 SpacePlayer"配色最终定版"时的选择一致）。
  图表业务色（红涨绿跌、四态钮槽色、左栏底色 `ListPaneBase`）全部收在 `content/StockColors.kt`，不进主题。
  - **两个窗口的毛玻璃都关掉了，改成应用自己画不透明底**：`AndroidManifest.xml` 的
    `pico.spatial.windowcontainer.materialbackground` 改成 `0`（`DefaultWindowContainer` 只收 content、
    没有 `enableMaterialBackground` 参数，只能走 manifest），详情窗口那个 `WindowContainer` 则显式传
    `enableMaterialBackground = false`；两处内容根部各铺一个
    `Box(Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillPrimary))`。这是对
    `spatial-ui-design-style` Rule 4「默认保留系统玻璃」的**有意偏离**，理由是盘面数字要绝对可读。
  - ⚠️ **`fillLight` 必须显式覆盖**：SDK 默认是 15% 白（`0x26FFFFFF`），而 `ListItem` 的默认
    `backgroundColor` 取的就是它——不覆盖的话深色面板上每一行又会变回浅色圆角卡片，选中态也会被它盖掉。
  - ⚠️ **`labelPrimaryLight` 保留默认（白）**：它同时是所有默认 `IconButton` 的 `contentColor`，改成深色
    会让默认 `IconButton` 变成"深底深字"整个消失。琥珀实心钮上的深色文字在调用处单独传。
  - ⚠️ **这套深色配色目前只是"已实现"，还没有在设备或模拟器上截图验证过**——实际不透明度、对比度、选中行
    那条 3dp 琥珀强调条的可见性都还没有画面证据，不要把它当成已确认的结论。见"下一步"第 14 条。
- **范围排除**：不做交易下单、不做投资建议、不做账号/云同步、首版不做港股/美股/期货。这些是刻意排除，不是
  漏做。
- **语音**：设计目标是让语音跳过多步手动流程（比如"加入 XX 自选"语音直接执行，跳过"点查询→输入→选中→点
  加入"四步）。**⚠️ 语音识别引擎的具体技术路径（PICO 系统能力 vs 标准 Android `SpeechRecognizer`）还没有
  验证**——这是开始做语音功能时第一件要查清楚的事，别假设某个 API 存在就直接写。
- **持久化：`SharedPreferences`，不是 Room**——自选股需要持久化的形状就是"一份股票代码的
  有序列表"，是扁平的 key=value 数据，不是关系型数据；引入 Room 需要新增 KSP/kapt 编译步骤，在这个已经因为
  `exclude()` 规则而比较脆弱的 classpath 上（见下面 `Modifier.weight` 那条坑）风险收益比不划算。真正的市场
  数据（`hist`/价格）从不写入本地——只有"哪些代码在自选里、顺序"这两样是需要跨重启保留的用户偏好，本质
  上和 SpacePlayer 项目当年为类似简单偏好选的方案一致。`WatchlistLocalStore` 现在只存**一个**字符串：
  逗号分隔的有序代码列表（契约 v5 删掉星标之后，那个加星集合的 key 也一并去掉了）；
  `StockRepository.init(context)` 在 `SpatialApplication.onCreate()` 里调一次，读到已保存数据就覆盖默认
  种子数据，读不到（首次安装）就保留硬编码的默认自选股。每次
  `remove`/`restore`/`moveToTop`/`addFromUniverse`/`addRemote` 之后立即同步落盘（数据量小，不需要防抖）。
  已验证（2026-08-10，还带星标的那个版本）：手动触发一次"删除 招商银行 + 星标 中国平安"的变更后，
  `adb install -r`（保留数据）+ 完全重启进程，变更原样还在（`./artifacts/persistence_verified.png`）。

## 关键文件

- `app/src/main/java/tech/illusion/stockquote/Main.kt` — `mainApp()` 入口，声明了两个 `WindowContainer`：
  `DefaultWindowContainer`（自选股列表）+ 命名的 `WindowContainer(id = DETAIL_WINDOW_ID, ...)`（详情窗口，
  640×480dp，`Form.Planar`）。`DETAIL_WINDOW_ID`/`DETAIL_WINDOW_TAG` 两个常量也定义在这个文件，其它文件要开
  详情窗口都应该 import 这两个常量，不要在别处重新写字符串字面量。
- `app/src/main/java/tech/illusion/stockquote/content/WatchlistHost.kt` — 主窗口入口（`WatchlistHost()`），
  左右分栏的那个 `Row`。左栏是一个 `Box`：`searchOpen ? SearchPane : WatchlistPane` + 浮在底部的撤销条；
  右栏是 `DetailPane`（`DetailScreen` + 右上角 `⋯` 浮层菜单）。宿主级状态（`searchOpen`/`selectedCode`/
  `sortKey`/`sortAscending`/`undo`/`undoGeneration`）、`offerUndo`、`openInNewWindow`、`pinToTop`、
  行情轮询的两个 `LaunchedEffect`，以及两处共用的 `StockActionMenu` 都在这个文件。
  （契约 v5 之前这里叫 `WatchlistScreen.kt`，内部切换 `WatchlistScreen`/`QueryScreen`；那两个文件已不存在。）
- `app/src/main/java/tech/illusion/stockquote/content/WatchlistPane.kt` — 左栏常态：标题行 + 编辑
  `ToggleButton` + 假搜索框 `Button` + 三个 `SortHeader` + `LazyColumn` of `WatchlistRow`。`ListPaneWidth`
  （400dp）定义在这里。`WatchlistRow` 是"行背景 / 选中态 / 长按菜单锚"三件事的落点，改行外观之前先读它上面
  那段关于 `ListItem` 内部图层顺序和 `fillLight` 的长注释。
- `app/src/main/java/tech/illusion/stockquote/content/SearchPane.kt` — 左栏搜索态：`SearchField` +
  防抖 300ms 的全市场搜索 + 本地/全市场两段结果列表（`ResultRow`）。清 `Adding`/`Failed` 残留的两条时机
  （结果集变化 → `pruneOpStates`；离开 composition → `clearOpStates`）都挂在这个文件自己的
  `LaunchedEffect`/`DisposableEffect` 上，不要挪回调用方的"取消"回调里——那样只覆盖一条退出路径。
- `app/src/main/java/tech/illusion/stockquote/content/MembershipToggle.kt` — 行尾四态钮
  `MembershipToggle`（＋/◌/✓/!）和 5 秒撤销条 `UndoBar`。**这两个组件的调用方都必须包一层 `key(...)`**，
  理由写在各自的 KDoc 里（`pico-sdk-compose-pitfalls` 陷阱 1）。
- `app/src/main/java/tech/illusion/stockquote/content/DetailScreen.kt` — 详情窗口内容（**契约 v3 改版后
  的唯一一份详情实现**，见下面"详情页设计统一"）。从 `bundle` 读 `code` 找到对应 `Stock`；周期 Tab 用
  `SegmentControl`/`SegmentItem`（现在只有 日K/周K/月K，分时已移除），区间和指标芯片用 `ToggleableChip`
  （10 个：M5/M10/M20/M30/M180/VOL/MACD/KDJ/RSI/BOLL）。根 `Column` 有
  `Modifier.verticalScroll(...)`——**480dp 高的窗口装不下 v3 的全部内容，子面板都在首屏之外**，见下面
  "详情窗口验证"的未决项。没有自绘关闭按钮（v3 移除，靠系统 caption bar）。
  文件内三个私有组件承担了重复结构：`LegendStrip`（色点+名称+当前值，均线图例和四个子面板标题共用同一
  形状）、`SubPanel`（成交量/MACD/KDJ/RSI 的统一容器）、`ParamGrid`（基本参数 3×3 和"更多信息"共用）。
  区间切片优先切 `stock.candles`（真实 OHLCV），只有从来没抓到过 kline 时才退回 `buildCandles(stock.hist)`。
- `app/src/main/java/tech/illusion/stockquote/content/MaLines.kt` — **调色板的单一来源**：`MaLines`
  （M5/M10/M20/M30/M180 的 key+标签+窗口+固定色）、`VolumeMaLines`（量能均线沿用前两个色）、
  `KdjLines`/`RsiLines`。图表画线和图例色点都读这里，**这是刻意的**：图例上的色点和图上的线如果各写一份
  颜色，改一处就会悄悄对不上。新增均线只需往 `MaLines` 里加一项，芯片/图例/绘制三处会自动跟上。
- `app/src/main/java/tech/illusion/stockquote/content/CanvasText.kt` — Canvas 里画坐标轴/标注文字的工具
  （`chartLabelPaint` + `drawChartLabel`）。**故意走平台 `android.graphics.Paint` 而不是 Compose 的
  `TextMeasurer`**：本模块的 `resolutionStrategy` 排除了 `androidx.compose.ui:ui-text`，那些类由 PICO 制品
  提供，平台这条路是这个 classpath 上确定能解析到的。Paint 要在每次 `Canvas {}` 里建一次复用，别每个
  label 建一个（每帧都会分配）。
- `app/src/main/java/tech/illusion/stockquote/content/VolumeChart.kt` — 独立成交量面板（契约 v3 把它从
  K 线图底部拆出来），柱子按当日涨跌取色 + M5/M10 量能均线。
- `app/src/main/java/tech/illusion/stockquote/content/NumberFormat.kt` — `NoValue`（"—"）、`formatLots`
  （手/万手/亿手）、`formatYuan`（万/亿/万亿）、`formatPrice`/`formatPercent`。**`NoValue` 是这个 app
  诚实性约定的载体**：拿不到的字段显示它，不要改成 0 或估算值。
- `app/src/main/java/tech/illusion/stockquote/content/KLineChart.kt` / `MacdChart.kt` — 蜡烛图+MA叠加线、
  MACD柱状图+信号线，都是手写 `Canvas` 绘制，没有引入任何图表库；点位计算移植自 HTML 原型的
  `drawCandles()`/`drawMacd()`。契约 v3 之后 `KLineChart` 还画：右侧价格刻度（每条网格线一个数）、底部
  3–4 个稀疏日期（来自 `Candle.date`）、可选的 BOLL 上中下轨、以及可见区间最高/最低价的自动标注。
  成交量已经不在这里了（拆到 `VolumeChart.kt`）。
  **`KLineOverlays.annotateExtremes` 必须在蜡烛来自 `buildCandles()` 时传 false**——合成蜡烛的高低点是
  形状不是实测值，给它标上精确价格等于报一个市场从没成交过的数字；Y 轴刻度可以留着，它描述的只是当前
  绘图量程。四个指标图（`MacdChart`/`KdjChart`/`RsiChart`）现在都接收**预计算好的结果对象**而不是
  `closes`，这样面板标题栏显示的最新值和图上画的线必然出自同一次计算，不会各算一遍还对不上。
- `app/src/main/java/tech/illusion/stockquote/data/StockMath.kt` — `buildCandles`/`simpleMovingAverage`/
  `computeMacd`/`computeKdj`/`computeRsi`/`computeBoll` 等纯函数，全部真实计算（2026-08-11 补全 KDJ/RSI/
  BOLL），不是占位：KDJ(9,3,3) 用 candle 的 high/low/close 算 RSV 再递归平滑；RSI(6)/RSI(12) 用 Wilder 平滑；
  BOLL(20,2) 是 SMA±2 倍标准差。（`expandCloses` 假数据插值函数已随真实数据源上线一起删除，不再需要。）
- `app/src/main/java/tech/illusion/stockquote/content/KdjChart.kt` / `RsiChart.kt` — KDJ 三线 / RSI 双线
  的 Canvas 绘制，风格和 `MacdChart.kt` 一致（无图表库）；线条固定色沿用 MA 线的"design-style: fixed-figma-
  color"惯例，不是主题语义色。KDJ 用自动量程（J 值可能超出 0–100），RSI 固定 0–100 量程 + 20/50/80 参考线。
- BOLL 画在主图上而不是单独面板（布林带和价格同一量纲）；量程计算把 BOLL 上下轨和所有已开启均线都算进
  `max`/`min`，避免线被裁切出画布——新增叠加线时记得一起加进量程，不然 M180 这种长窗口均线会贴边消失。
- `app/src/main/java/tech/illusion/stockquote/data/StockDataRepository.kt` / `EastmoneySource.kt` /
  `SinaSource.kt` / `RemoteStock.kt` / `NetUtil.kt` — 真实市场数据抓取，见上面"数据源"小节。
- `app/src/main/java/tech/illusion/stockquote/content/Sparkline.kt` — 列表行的迷你走势线，纯 `Canvas`
  画线，不依赖任何图表库。
- `app/src/main/java/tech/illusion/stockquote/content/StockColors.kt` — 红涨绿跌两个固定色，均带
  `// design-style: fixed-figma-color` 标注（`spatial-ui-design-style` 技能的 lint 约定，参见
  `scripts/verify-design-style.sh`）。
- `app/src/main/java/tech/illusion/stockquote/content/StockTheme.kt` — `StockQuoteColorScheme`，"同花顺"
  深色不透明盘面配色（契约 v5），通过 `PicoTheme(colorScheme = ...)` 在 `Main.kt` 里整体注入，不要在各个 Composable 里散着写
  `Color(0x...)` 覆盖颜色——新组件要跟着这套色板就用 `PicoTheme.colorScheme.<role>`，要新增角色就改这个文件。
- `app/src/main/java/tech/illusion/stockquote/data/Stock.kt` / `StockRepository.kt` — 数据模型 +
  进程内单例（Compose 快照状态 `mutableStateOf`，不是 `StateFlow`——同一进程内任何 Composition 读取
  `StockRepository.watchlist`/`universe` 都会在写入时自动收到重组通知）。`refreshAll()`/
  `refreshWatchlistQuotes()` 调 `StockDataRepository` 拉真实数据、写回 `_universe`/`_watchlist`（两边都要
  同步更新，因为 `_watchlist` 里存的是独立拷贝，不是对 `_universe` 条目的引用）。硬编码的 `universeSeed`
  现在只是"抓取成功前的占位显示 + 两个源都失败时的永久兜底"，不再是唯一数据源。自选股成员/顺序是独立
  的真实持久化数据，见下一条。
  加/删自选相关的接口：`membershipOf` / `addFromUniverse`（本地命中，同步）/ `addRemote`（全市场命中，
  `suspend`，**必须先抓到真实历史才入表**，否则 `hist` 不足 2 个点的空壳行一进 composition 就崩）/
  `remove` + `restore`（撤销）/ `clearOpStates` + `pruneOpStates`（清 `Adding`/`Failed` 残留）。
  ⚠️ `addRemote` 开头那条 `if (_opStates[code] == Membership.Adding) return false` 是**并发守卫，别删**：
  它后面的 `fetchHistory` 是挂起点，两次快速点击会双双越过 `isInWatchlist` 检查，把同一个 code 追加两次，
  然后 `LazyColumn` 的 `key = { it.code }` 撞上重复 key 直接抛异常。
- `app/src/main/java/tech/illusion/stockquote/data/WatchlistLocalStore.kt` — `SharedPreferences` 持久化，
  只存一个字符串（逗号分隔的有序代码列表），不存市场数据，也不再有加星集合。`StockRepository` 的每个写操作之后都会调它的 `save()`。
- `app/src/main/java/tech/illusion/stockquote/platform/LaunchActivity.kt` — 模板生成，暂未改动。
- `app/src/main/java/tech/illusion/stockquote/platform/SpatialApplication.kt` — `onCreate()` 里现在会先
  `StockRepository.init(this)`（触发 `WatchlistLocalStore` 读盘）再 `launch(::mainApp)`；这是整个 app 唯一
  保证在任何 `WindowContainer`/Activity 起来之前执行一次的地方，后续任何"进程启动时要做一次"的初始化都应该
  加在这里，不要塞进某个具体 Composable。
- `app/src/main/AndroidManifest.xml` — `DefaultWindowContainer` 的 `pico.spatial.windowcontainer.*`
  meta-data 都在这里（id/style/defaultsize/resizetype/materialbackground...），新增第二个 `WindowContainer`
  不需要在这里加 `<activity>`（DSL 自己注册）。

## SpatialUI 强制规则

全部 2D UI 必须用 SpatialUI（`com.pico.spatial.ui.design.*`），根节点/每个 `WindowContainer` 内容都要包一层
`PicoTheme { ... }`。**禁止** `androidx.compose.material` / `material3` / `MaterialTheme`。当前两个窗口
（`WatchlistHost`/`DetailScreen`）都已确认干净，没有 Material 依赖——后续每次改动前后都要重新 grep 一遍
确认没有引入。

## 详情窗口验证（`spatial-design-first-build` 门禁 B，2026-08-10）

详情窗口是第一个走完整套 `spatial-design-first-build` 门禁 A/B 流程实现的功能——设计契约在
`.spatialsdk/design-contract.md`，两轮截图验证记录在 `.spatialsdk/ui-verification-log.md`，截图在
`.spatialsdk/ui-verify/`。后续任何新增/大改 Spatial UI 面板，都应该先走这个 Skill，不要跳过契约直接写代码。

**两轮验证已确认修复**：`detailStockCode` 交易所后缀（"600519 · 沪"）、`detailIndicatorChips` 渲染顺序
（改用固定 `indicatorOrder` 列表，不再遍历 `mutableStateMapOf` 的 `keys`——`SnapshotStateMap` 不保证插入
顺序，这条本身也是一个通用坑，其它地方如果也用 map 驱动列表渲染要留意）。

**两轮都没能闭环的问题（交给你决策，不是我自己拍板改掉的）**：

1. **MACD 面板 / KDJ 占位提示在默认视口里看不到**——根 `Column` 已经加了
   `Modifier.verticalScroll(rememberScrollState())`，内容不会再被裁切丢失，但默认滚动位置在最上面，这两块
   还是要往下滚才能看到。契约原本假定"默认态就能看到 MACD 面板"，现在这个假设不成立了。需要你决定：接受
   "MACD 需要滚动查看"，还是收紧默认展示的内容（比如默认不展开 MACD 子面板，只有点开 MACD 芯片才展开——
   目前已经是这样了，问题是 `macd` 默认就是 `true`，所以"默认展开但看不见"），或者把窗口默认尺寸从
   640×480dp 改大（这个改动会碰到已经在设计契约里定过的尺寸，需要走契约增量流程，不是我能单方面改的）。
2. **"近1月"区间切换从未在截图里观察到发生过**——两轮验证（4张有效截图，各自跨越 10-22 秒）都停在默认的
   "近3月"。已经对着 `DetailScreen.kt` 的 `rangeIndex`/`LaunchedEffect` 代码看过一遍没找到明显 bug，但也没有
   拿到确凿证据证明这是真的交互 bug——怀疑（未验证）是这次验证撞上另一个会话在同一台模拟器上跑
   TossAR（持续占用 GPU/CPU 做平面检测+手部追踪），导致 `DetailScreen` 真正开始渲染/计时的时间点比预期晚
   很多。下次要验证这条，建议：(a) 找一个没有其它 App 在跑的干净模拟器时段，或 (b) 不用定时脚手架，改用
   真机/模拟器上的真实点击（配合 `uiautomator dump` 找 `ToggleableChip` 的精确 bounds，SpacePlayer 项目的
   AGENTS.md 记录过这个手法对自定义 `clickable` 组件是可靠的），或 (c) 加一行 `Log.d` 在 `rangeIndex` 赋值
   处，用 `adb logcat` 直接看时间戳，不用靠截图猜。

## 主窗口 v2 分栏改版验证（门禁 B 4 轮，2026-08-11）

截图 `.spatialsdk/ui-verify/v2-*.png`，逐项核对与"没能验证到什么"写在
`.spatialsdk/design-contract.md` 的"契约增量 v2 — 确认与实施记录"里。4 轮 `adb logcat -b crash -d`
全部为空。**首次构建即通过**——三个关键 API（`Menu`/`MenuItem`、`detectTapGestures` 的长按、
`VerticalDivider`）都事先在 0.13.3 制品源码里查证过再动手，没有靠猜。

- `v2-1-split-default.png`：**默认态即分栏，这一轮不需要任何临时代码**——默认选中第一项、右上角
  "在新窗口打开"、右侧是完整 v3 详情。
- `v2-2-longpress-menu.png`：长按菜单三项正确锚定在被长按的行下方。
- `v2-3-newwindow-coexist.png`：独立窗口与主窗口分栏共存，且独立窗口里**没有**"在新窗口打开"按钮。
- `v2-4-shipped-regression.png`：删掉全部临时验证代码后的终态。

**实施期发现的真实缺陷（还没修）**：K 线拿到多日历史时好时坏，见上面"数据源"里 `refreshAll` 12 路并发
那一条，以及契约 v2 记录的第 5 节。

## 详情窗口 v3 改版验证（门禁 B 5 轮，2026-08-11）

截图在 `.spatialsdk/ui-verify/v3-*.png`，逐项核对结果和"没能验证到什么"都写在
`.spatialsdk/design-contract.md` 的"契约增量 v3 — 确认与实施记录"里，**接手前先看那一节**。5 轮
`adb logcat -b crash -d` 全部为空，无本应用异常。

已验证：周期 Tab 3 项且默认日K、10 个指标芯片及默认开关、基本参数网格真实值（今开/最高/最低/成交额，
且同一行内各自独立判断红涨绿跌——截图里今开绿、最高红）、"更多信息"真实值（昨收/振幅/均价/总手）、
四个子面板渲染 + MACD/KDJ 标题栏真实指标值、Y 轴价格刻度、极值标注在合成数据下被正确抑制。

**后来在 v2 那轮补上的**（`v2-2-longpress-menu.png`，那一轮 kline 恰好成功）：真实多日蜡烛图、
均线图例真实数值（M5 1322.95 / M10 1333.54 / M20 1311.90）、X 轴稀疏日期（05-11/06-10/07-13/08-11）、
极值标注落在真实高低点（1363.35/1151.01）、MACD 真实柱状图。

**最后三条也补齐了**（`v3-8-year-m180-realdata.png`，换成新浪主源之后拍的）：M180 图例真实值 1372.20 且
线段真的画出来了（SMA(180) 在 220 点上只有最后 41 点有值，所以是右侧一段短线，符合预期）；"近1年"密度
明显高于"近3月"、X 轴跨一整年（09-11/12-31/04-24/08-11）、极值 1568.00/1151.01；成交量面板真实数值
（标题 2.80万手，量能均线 M5 3.52万手 / M10 4.39万手）。**至此契约 v3 §4 的三张截图全部完成。**

**未闭环、需要产品决策的三项**（详见契约那一节的第 5 节）：

1. **窗口关不掉的风险没排除**：v3 移除了自绘关闭按钮，赌的是系统 caption bar 自带关闭控件；5 轮截图里
   只看到窗口底部中央一个系统小圆点（抓取手柄），**看不到明确的关闭控件**，而它很可能要悬停/注视才展开，
   静态截图判定不了。**请在设备上手动确认一次能不能关掉详情窗口**，不能就把按钮加回来。
2. **640×480dp 装不下 v3 的内容**：默认态下 MACD 面板就已在首屏之外。这正是下面"详情窗口验证"里早就
   记着、两轮没闭环的老问题，被 v3 新增的网格/图例/面板放大了。要改默认尺寸的话得走契约增量（尺寸是
   契约 v1 定过的）。
3. **10 个指标芯片装不满一行**，第 10 个 BOLL 在右边缘被截成"BOL"；当前是可横向滚动的 `LazyRow`。

## ⚠️ 门禁 B 必须用 `.spatialsdk/gate-b-verify.sh`，不要手搓验证脚本

`spatial-design-first-build` 这个 skill 装在**父工作区** `PicoProjects/.claude/skills/`，而工作目录是子目录
`StockQuote/`（没有自己的 `.claude/`），所以**它不会出现在会话的可用技能列表里，`Skill` 工具也调不到**。
2026-08-11 那次就因此踩了坑：只从本文件知道"有个设备锁"和"门禁 A/B"的说法，就自己搭了验证脚本，结果漏掉
它的几条硬约束——最要紧的是**截图后没有 `app stop`**，应用在共享模拟器上挂了十几分钟占着设备；还有锁内
跑 logcat/pull、锁 TTL 传 300 而不是 120、锁内耗时超 90s 目标。

现在规则固化在 `.spatialsdk/gate-b-verify.sh` 里，**直接用它**：

```bash
.spatialsdk/gate-b-verify.sh <截图基名> [渲染等待秒数]
```

它保证：锁内只做 install → launch → 等渲染 → 截图 → `app stop` → release；TTL 120；显式传 serial（本机
多 AVD，端口会跳）；`trap` 兜底先 stop 再 release；结束打印实测锁内秒数；崩溃日志在**锁外**读。
`acquire` 返回 1（设备被占）时**报告并跳过，不重试第二遍**。

**渲染等待秒数**：skill 的默认值是 6 秒，但本应用启动后要先抓 12 只股票各 230 天日线，6 秒时数据没到、
截出来是 quote 兜底的 2 根蜡烛，等于白截一轮。用 **50 秒**（实测锁内总耗时 54s，仍在 90s 目标内）。

### ⚠️ 截图判定不要靠数像素，让画面自己报状态

2026-08-11 第 10 轮踩到过：临时把默认态改成"月K + 近1年"去截图，结果画面上高亮的是 **周K**、蜡烛量出来
44 根（= 周线近1年）。反复量像素、`javap` 反编译确认字节码里两个默认值都是 `iconst_2`，二进制没问题。
真因是**模拟器那 55 秒等待里射线误点到了 SegmentControl 的中间那格**——从 p=2 被点成了 p=1，而 44 根正好
是这个状态的正确渲染结果。

教训：涉及"哪个选中态/取了几根数据"的判定，**在临时验证代码里加一行状态自述文本**（`DBG p=… r=… n=…`），
截图一眼就能读，比数蜡烛可靠得多，也能立刻区分"代码错"和"运行时状态被改过"。第 11 轮加上之后一次判定完成。
临时代码统一打 `// GATE-B-TEMP:` 标记，验证完必须删。

### ⚠️ `adb shell input swipe/tap` 送不进 spatial 面板

想验证 K 线横向拖拽时试过在锁内注入 `adb -s <serial> shell input swipe 1200 1120 1650 1120 400`，
拖前拖后两张截图**逐字节相同**（`md5` 一致）。连射线光标的像素都没变，说明事件根本没到应用。
这和 `spatial-app-dev-workflow` 的警告一致：2D 屏幕坐标注入对 spatial 容器不可靠。

所以**手势类需求（拖拽/缩放/长按）没法用截图自证**。能做的是：把纯逻辑抽成可单测的状态类
（`content/ChartPan.kt` + `ChartPanTest`，8 个用例覆盖方向/亚根余量累积/两端夹紧/除零），
然后如实说明"事件能不能送达要真机上手确认"，**不要把编译通过说成验证通过**。

需要读它的完整规则时直接读文件（不能用 `Skill` 工具调）：
`/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/SKILL.md`，
比对清单在同目录 `references/ui-verification-checklist.md`（8 个维度 + Blocker/Major/不可判定 分级 +
"透视截图判不出精确 dp，一律归不可判定"这条已知限制）。逐轮记录写进
`.spatialsdk/ui-verification-log.md`（那份文件开头有 2026-08-11 那批非合规轮次的追溯说明）。

## 共享模拟器设备锁（`spatial-design-first-build` 提供，本次是第一次用）

`.claude/skills/spatial-design-first-build/scripts/device-lock.sh`（在 PicoProjects 工作区根目录下，不在
本项目里）给这台模拟器加了跨会话的文件锁，解决的是"两个 Claude 会话同时对同一个模拟器发 install/launch
命令互相冲突"。用法：`device-lock.sh acquire <owner> <ttl_seconds> [serial]`——**`serial` 是第三个位置参数，
不是 flag，不要在它前面加 `--`**（本次会话头一次用的时候手滑传了个 `--` 当 serial，创建了一个
`~/.pico-device-lock/--.lock` 的废锁目录，后来手动删掉了；正确调用见下面例子）。

```bash
LOCK="/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh"
OWNER="StockQuote-$$"
"$LOCK" acquire "$OWNER" 120 emulator-5554 || echo busy
trap '"$LOCK" release "$OWNER" emulator-5554 >/dev/null 2>&1' EXIT INT TERM
# ...install/launch/capture/stop...
"$LOCK" release "$OWNER" emulator-5554
```

**这个锁只能防止"命令层面"的冲突（两边同时 install 会失败/覆盖），防不住"另一个已经在跑的 App 的画面出现在
固定截图相机取景范围里"**——这次验证时就实测到另一个会话的 TossAR 项目全程在跑，它的平面检测调试 UI 反复
出现在我方截图里（见"详情窗口验证"）。锁生效不代表截图一定干净，遇到截图被别的 App 遮挡时，那是环境问题，
不代表锁没起作用。

### ⚠️⚠️ 真机掉线时 `pico-cli --device` 会**静默回落到另一台设备**

2026-08-11 装机时踩到，后果很实：脚本先用 `adb devices` 预检确认头显 `PB3B4XJGL2090011G` 在线、拿到它的锁，
然后 `pico-cli app install --device PB3B4XJGL2090011G`。头显在这两步之间掉线了，而 pico-cli **没有报错**，
输出的是 `Installing ... -> emulator-5554`：它把包装到了模拟器上并启动，而我并没有为模拟器持锁、也没打算
在那儿留一个跑着的进程（违反共享模拟器规则，事后手动 `app stop` 才清掉）。

所以对真机操作时：
- **每一步之后都要核对 pico-cli 输出里的目标 serial**，不能只看 `Success`。预检通过 ≠ 全程在线。
  可靠写法：把输出抓进变量，`grep -q "$SERIAL"` 不中就中止（2026-08-11 17:38 那次装机就是这么把住的，
  输出确认是 `Installing ... -> PB3B4XJGL2090011G`）。
- 头显会自己睡/断 USB，掉线是常态，不是异常。`adb devices` 里彻底消失就是真掉线，等它回来再做。
- 一旦发现误落到模拟器：立刻 `pico-cli app stop <pkg> --device emulator-5554`，并确认那台的锁状态。
- 装机后核对二进制身份用 `adb shell pm path <pkg>` 取真实路径再 `stat -c%s`，**不要用
  `/data/app/*/<pkg>*/base.apk` 通配**——真机上那层目录带随机 hash（`~~5slJZT7…==`），通配匹配不到、
  返回空串，看起来像"设备上没这个包"。

### ⚠️ 真机不能截图（模拟器可以）

`pico-cli capture screenshot --device PB3B4XJGL2090011G` 在这台头显上失败：
`Error: ADB screencap returned non-PNG output: Failed to take screenshot. Capturing failed.`
`pico-cli capture` 只有 `record`/`screenshot` 两个子命令，没有绕过这个的选项。

所以**门禁 B 的截图验证只能在模拟器上做**，真机装机后的核对手段是：`pidof` 看进程、`pm path` + `stat`
比包大小、`logcat -b crash -d` 和 `logcat --pid=<pid> '*:E'` 看崩溃与错误。要看画面就得戴上头显。

## 本机环境注意事项

- **⚠️ 必须用 JDK 17 构建，本机默认 JDK 已经是 25，会让构建直接挂掉**（2026-08-11 踩到）。症状极具
  误导性：`./gradlew assembleDebug` 报的 `* What went wrong:` 只有一行 `25.0.2`，看不出跟 JDK 有关，
  `--stacktrace` 才露出真因——Gradle 8.13 内嵌的 Kotlin 编译器用
  `org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse("25.0.2")` 解析不了这个版本号，抛
  `IllegalArgumentException`，而且失败发生在**编译 `.gradle.kts` 构建脚本**阶段，跟项目源码无关。
  本机有 17.0.18 可用，每次构建前显式指定即可（没有改项目配置，所以新会话要自己带上这一行）：
  ```bash
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
  ./gradlew assembleDebug
  ```
  如果希望一劳永逸，可以在 `gradle.properties` 里写 `org.gradle.java.home=...`，但那会把一台机器的
  绝对路径写进仓库，需要先决定要不要这么做。
- **⚠️ eastmoney 按请求速率限流，触发后连模拟器里的 App 都跟着拿不到数据**（2026-08-11 反复实测）。
  一整个会话下来观察到的规律：
  - 为核字段用 curl 在几分钟内连发十几次之后，`push2his`/`push2` 从**主机和设备两侧同时**变成连不上
    （curl `HTTP=000` / `Empty reply from server`；设备侧三个 URL 全部 ~300ms 立即返回 null）。
  - **两台主机的可达性是独立的、恢复时间也不同**：约一小时后 `push2his`（kline）从设备侧恢复，能拉回
    真实多日历史；而 `push2`（quote）在同一时刻从主机和设备**都仍然取不到**。所以判定时要分开测，
    别用一台的结果推另一台。
  - DNS 全程正常（logcat 里
    `NetworkWatchlistService: onDnsEvent [returnCode: 0, hostname: push2.eastmoney.com]`），失败发生在
    TCP/TLS 层，**不要误判成域名或代码问题**。
  - **⚠️ 触发限流的不只是 curl 探测——`StockRepository.refreshAll()` 自己一次就并发发起 12 个
    `lmt=230` 的 kline 请求**，这很可能是 K 线时好时坏的真正原因（同一份代码，一轮拿到完整多日图，
    另外两轮只有 2 根蜡烛）。已开成独立任务去证实并加限流。判断"这次到底有没有拿到历史"最快的办法是看
    详情图：完整蜡烛图 = kline 成功；只有 2 根 = 退到了 quote/新浪兜底的 `[昨收, 现价]` 两个点。
  - 判定某台主机是否恢复的最快方法（拿到 200 才算通）：
  ```bash
  curl -s -o /dev/null -m 15 -w 'HTTP=%{http_code}\n' -H 'Referer: https://quote.eastmoney.com/' \
    'https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600519&klt=101&lmt=5&fields1=f1&fields2=f51,f52,f53'
  ```
  拿到 200 才说明恢复了。核字段请**一次请求带上全部要验的 field 码**，不要一个个试。
  另外新浪那条链路（`hq.sinajs.cn`）不受影响，本机沙箱可直连（实测 HTTP=200），可以拿它交叉验证。
- `pico-cli project create` **不会**生成 `local.properties`，构建前必须手动创建（已在 `.gitignore` 里，
  不会提交）：
  ```
  sdk.dir=/Users/zohar/Library/Android/sdk
  spatial.tools.dir=/Users/zohar/Library/PICO/sdk
  ```
- 已验证：`./gradlew assembleDebug` / `installDebug` 成功，`adb shell am start -n
  tech.illusion.stockquote/.platform.LaunchActivity` 能正常启动，截图确认渲染正常（见 `./artifacts/`：
  `watchlist_default.png` 只读态、`watchlist_editmode.png` 编辑态+涨跌幅排序中、`query_screen.png` 查询子
  视图、`persistence_verified.png` 验证自选股变更能跨进程重启存活）。**注意这批截图全部是契约 v5 改版
  之前的**：配色还是旧的"素雅"暖纸风，左栏还有星标、查询还是整窗接管，只能当历史记录看，不能拿来判断
  当前 UI。v5 之后还没有任何截图证据。
- **验证"数据能不能跨重启存活"不能只靠 `am force-stop` + 重新 `am start`**——同一次 `adb install` 之后如果
  只是杀进程重开，看到状态还在，不能排除是因为 `SharedPreferences` 从来没真的落盘、只是内存里的单例对象碰巧
  没被系统真的回收。这次验证特意多做一步：改完状态之后先把触发变更的临时代码从源码里删掉、重新编译、
  `adb install -r`（保留数据，但会重新走一次完整的进程冷启动），再看变更是否还在——这样才能证明数据是真的从
  磁盘读回来的，不是进程侥幸没被杀。
- `pico-spatial-knowledge` MCP 的知识图谱数据（`graph.json`）在这台机器上缺失（`pico-cli doctor` 报
  `MCP_GRAPHIFY_UNAVAILABLE`），本次任务不需要它所以没有修——如果后续要用 MCP 查 SDK 文档发现查不到，先跑
  `pico-cli setup --tool claude-code --plugin pico-spatial-agentic-tools` 重新拉取知识库，再开新会话。
- **`Modifier.weight(1f)` 不要显式 `import androidx.compose.foundation.layout.weight`**——这个项目的
  classpath 上同时有真实 `androidx.compose.foundation:foundation-layout:1.10.4` 和
  `com.pico.spatial.ui.foundation` 系列（见 `app/build.gradle.kts` 里的 `exclude()` 列表，只排除了
  `androidx.compose.ui:ui`/`ui-graphics`/`ui-text`/`androidx.compose.foundation:foundation`，**没有**排除
  `foundation-layout`）；显式 import 顶层 `weight` 函数会解析到一个和当前 `RowScope`/`ColumnScope` 不匹配的
  版本，报 `Cannot access 'val RowColumnParentData?.weight: Float': it is internal in file`。直接在
  `Row {}`/`Column {}` 内容 lambda 里写 `Modifier.weight(1f)`，靠隐式 `RowScope`/`ColumnScope` receiver 成员
  解析，不要加显式 import——这样才能编译通过（`WatchlistHost.kt` 里的 `DetailPane` 就是这样用的）。
- **本机同时装了多个 PICO 模拟器 AVD（`Pico_Emulator_0_13` 和 `Pico_Emulator_6_0`），`adb`/`emulator-5554`
  这个端口号会在两者之间跳**——这次会话里 `emulator-5554` 一度从 `Pico_Emulator_0_13`（本项目
  spatialBom 0.13.3 对应的那个）变成了 `Pico_Emulator_6_0`，导致重新安装/启动时报
  `Activity class ... does not exist`（其实是问对了错的模拟器，不是应用真的丢了）。排查方法：
  `pico-cli emulator status --format json` 看 `state.avd` 字段确认当前到底是哪个 AVD；如果不对，
  `pico-cli emulator stop` 之后 `pico-cli emulator start --avd Pico_Emulator_0_13 --wait-timeout 180 -y`
  显式指定，注意新实例可能换到 `emulator-5556` 等其它端口（`adb devices -l` 确认），后续所有
  `adb`/`gradlew install*` 命令都要用 `-s <正确的设备id>` 显式指定，不能假设 `emulator-5554` 一定是对的。
- **`adb` 连接在这台机器上会自发掉线**（`no devices/emulators found` / `device offline`），和 SpacePlayer
  项目记录的坑一样，`adb kill-server && adb start-server` 通常就能自愈，重试一次前先确认
  `pico-cli emulator status` 里 `processRunning: true`（模拟器进程本身没死，只是 adb 桥接断了）。
- **这台机器的 PICO 模拟器 Shared Space 里经常同时挂着好几个项目的窗口**（这次会话同时看到过
  `tech.illusion.spaceplayer`、`tech.illusion.boxdepthpoc`、`com.illusion.portalcantoss`、
  `tech.illusion.stockquote` 好几个 app 的窗口，其中 `BoxDepthPoC` 还弹过一次"没有响应"的系统 ANR 对话框
  挡在画面中间——那是别的项目的问题，不代表本项目有毛病）——
  `adb shell screencap` 截到的是摄像机固定视角看到的画面，不代表"当前活动窗口"，如果截图里出现的是别的 app，
  不代表本项目崩溃或没启动，先 `adb shell dumpsys activity activities | grep mFocusedApp` 确认自己的
  Activity 是否真的 resumed，再 `am force-stop` 掉挡在视野里的其它 app 重新截图。
- **验证 `editMode`/`showQuery` 这类只能靠手势触发的状态时，`adb shell input tap` 对这套 spatial
  WindowContainer 不可靠**（和 SpacePlayer 记录的限制一样），这次验证用的是临时把
  `mutableStateOf(false)` 改成 `mutableStateOf(true)` 强制默认进入该状态、截图确认、再改回去——比反复试
  tap 坐标可靠得多，缺点是每次只能验证一条路径，别忘了验证完改回去（这次已确认改回，`grep "TEMP
  verification"` 应该找不到任何结果）。**用这个手法验证"数据持久化"类需求时要格外小心**：临时触发变更的
  `LaunchedEffect(Unit)` 只会在每次新的 Composition 创建时跑一次——如果不删掉这段代码就直接"杀进程重开"来看
  变更是否还在，它会在重开时再跑一次，非幂等的操作（比如"移除某一只自选"）会被再跑一遍，看起来像是
  "持久化失败"，其实是测试代码自己把状态改回去了。正确顺序是：先带着临时代码跑一次触发变更→截图确认→**把临时
  代码删掉**→重新编译安装→再重启验证，这样第二次重启时已经没有代码会再改动状态了。
- **应用窗口在 Shared Space 里的摆放位置有时会"飘出"固定截图摄像机的取景范围**——即使
  `dumpsys activity activities` 确认自己的 Activity 是 `mFocusedApp`、进程也在跑、`adb logcat -b crash`
  也没有任何异常，`adb shell screencap` 仍然可能拍到空房间（或者拍到别的 app，见上一条）。这次会话里试过
  `am force-stop` 别的挡路 app、`pm clear` 清本应用数据，都没能让窗口重新出现在取景范围里；最后是完整重启
  模拟器（`pico-cli emulator stop` 再 `pico-cli emulator start --avd Pico_Emulator_0_13 --wait-timeout 180
  -y`，注意重启后 `adb devices -l` 确认的设备 id 可能变，比如从 `emulator-5556` 变回 `emulator-5554`）才解决
  ——推测是这台机器上这个 AVD 被很多个项目来回装卸/切换焦点后，窗口摆放的记忆状态积累出了问题，跟本项目代码
  无关。遇到"焦点确认对了，截图还是空的"时，不要继续在 `force-stop`/`pm clear` 上反复试，直接重启模拟器更
  快。

## 资讯面板（"资讯"按钮 + `Subwindow` 新闻列表，2026-08-25）

`EastmoneyNewsSource.kt`（`StockNews` 模型 + URL/JSONP/高亮标签纯函数 + `fetchNews` 真实抓取）→
`StockDataRepository.fetchNews` 包装 → `DetailScreen.kt` 的"资讯"`ToggleableChip` + 条件 `Subwindow` +
`NewsPanel`/`NewsRow`，三个提交（`79a1daa`/`3a83e24`/`7b078b1`）分三步落地。

- **数据源和设计文档原始假设不一致**：设计文档原来写的是 `so.eastmoney.com/news/s`，但那其实是一个 SPA
  搜索页面（返回 HTML，不是 JSON），不能直接当接口打。写计划阶段用 curl 验证后确认真正的数据来自另一个
  域名：`https://search-api-web.eastmoney.com/search/jsonp`（JSONP 包装，`cb=<callback>&param=<url
  编码后的 JSON body>`）。`EastmoneyNewsSource.kt` 实现的是这个修正后的接口，不是文档字面写的那个 URL。
- **接口的 `sort=default` 不是时间倒序**——curl 600519 实测过，一次真实返回里日期顺序是
  08-15/08-21/08-21/08-24/08-21，完全是乱的。`EastmoneyNewsSource.fetchNews` 在客户端按 `publicTime`
  做降序重排来补偿，这不是防御性瞎猜，是有 curl 证据支撑的硬需求。
- **`Subwindow`/`SubwindowPlacement` 包路径和 `spatial-ui-ability` 技能的 bundled 参考不一致**：该技能的
  `references/ability-augment.md` 写的是 `com.pico.spatial.ui.augment`，这对本项目锁定的 SDK 版本
  （`gradle/libs.versions.toml` 里 `spatialBom = "0.13.3"`）是过期信息。解压 `design-0.13.3-sources.jar`
  直接读 `Subwindow.kt` 源码确认的真实包路径是 `com.pico.spatial.ui.design.windows`，`DetailScreen.kt`
  用的是这个修正后的 import。已经另开了一个任务去修那份技能参考（不在本仓库范围内）——这里只是记一笔，
  免得以后哪个会话又照着 `ability-augment.md` 抄错包名踩同一个坑。
- **模拟器验证做了什么（2026-08-25）**：
  - `./gradlew assembleDebug` → BUILD SUCCESSFUL。
  - 用本项目自己的 `.spatialsdk/gate-b-verify.sh`（设备锁安全的门禁 B 脚本，理由见上面"门禁 B 必须用
    `.spatialsdk/gate-b-verify.sh`"那节）装机+启动到 `emulator-5554`，渲染等待用本项目已确定的 50 秒
    （见上面"本机环境注意事项"里"12 只股票各 230 天日线"那条）。
  - 第一轮截图前的 crash buffer 里有一条 `FORTIFY: pthread_mutex_lock called on a destroyed mutex`
    SIGABRT，但时间戳比这次跑的时间早了大约 2 小时，且这轮的运行日志没有任何失败步骤。为消除歧义，
    显式清空 crash buffer（`adb logcat -c`），原样重跑一遍 install→launch→等 50 秒→截图→stop：
    crash buffer 回来是**完全空的**。结论：那条 crash 是这台共享模拟器上别的并发会话留下的陈旧记录
    （本工作区的模拟器多会话共享，见上面"共享模拟器设备锁"一节），跟本功能无关，这次构建确认无崩溃。
  - 截图确认"资讯 ▾"芯片渲染正确，位置在价格头部下方、今开/最高/最低网格上方，拍到的是**主窗口右侧
    分栏里的 `DetailScreen` 实例**（自选列表 + 详情左右分栏，示例股票是中铁装配/300374）。这是真实的
    视觉证据：芯片存在、和深色主题其余部分风格一致、没有和任何东西视觉冲突。
  - ⚠️ **最终整体评审抓到一个只截"芯片"这张图看不出来的 Critical 问题**：`Subwindow` 里包内容用的是
    裸 `PicoTheme { ... }`（没传 `colorScheme`）。`PicoTheme` 的真实签名是
    `colorScheme: ColorScheme = systemColorScheme(LocalContext.current)`——**不会**自动继承外层
    `LocalColorScheme`，裸调用会把面板换成系统自适应配色，而不是本项目固定的 `StockQuoteColorScheme`
    （`Main.kt:51,68` 两个宿主窗口都是显式传 `PicoTheme(colorScheme = StockQuoteColorScheme)`）。加上
    `Subwindow` 内部写死 `Material.Regular`（毛玻璃）且没有关闭选项，配色一错，面板底色就可能变成半透明的
    系统色而不是不透明的 `#0F1318`，正好复现"面板发白/可读性不强"这类以前改版专门修过的问题。已修
    （commit `82134cc`）：改成 `PicoTheme(colorScheme = StockQuoteColorScheme) { ... }`。
    **教训记一笔**：`PicoTheme` 的 `colorScheme` 参数不继承环境，每一处嵌套的 `PicoTheme` 调用都要显式传
    `StockQuoteColorScheme`，别假设"反正在 `WindowContainer` 里就会自动继承外层主题"。
  - 用本项目已有的"临时强制默认开启→截图→改回"手法（KDJ/RSI/BOLL 当年也是这么验证的，见下面"下一步"第 6
    条）在修完配色后重新截了一张**真正打开的资讯面板**：`Subwindow` 贴着**整个主窗口**右边界浮出，深色
    背景、白色标题"资讯"、真实抓到的新闻列表（标题+发布时间，例如"股东户数降幅榜：4股最新股东户数..."
    "2026-08-20 17:15:00"），没有和自选列表/详情分栏产生任何重叠或裁切。这确认了：①配色修复生效
    ②数据链路（`fetchNews` → 解析 → 排序 → 渲染）端到端走通③"两处都要"的空间关系设计是对的——面板浮在
    整个宿主窗口外面，不会挤占分栏内部空间。
  - **没验证**：芯片/面板在**独立详情窗口**里的外观（两轮截图用的都是默认启动的主窗口分栏，独立窗口
    那条还是空的）。
  - **没验证、也没法自动化验证**：真正的交互流程——点"资讯"、`Subwindow` 弹出、loading→有数据/空态的
    切换、点某条新闻展开/收起、再点一次"资讯"关闭、切换股票时面板自动收起。本项目已有的一条硬限制
    （见上面"`adb shell input swipe/tap` 送不进 spatial 面板"，K 线拖拽那次注入前后两张截图逐字节相同）
    同样适用于这里：`adb` 注入的 2D 屏幕坐标对这套 spatial UI 不是可靠输入，自动化"点一下芯片"的实验
    做出来也不能当真凭实据，所以**没有尝试**——这符合本项目一贯的做法：手势类交互流程如实标"未经自动化
    验证"，不假装截图能证明点击生效。
  - **没验证**：`Subwindow` 在真机（`PB3B4XJGL2090011G`，这次会话也在线）上的摆放/重叠表现。按本项目
    既有的"真机给用户玩、模拟器给 agent 验证"分工（见上面"真机不能截图（模拟器可以）"一节），真机这一步
    留给用户自己去试，agent 没有代做。
- **下一个人接手建议**：手动试一遍"资讯"按钮——用模拟器自身的指针/手势交互（不要用 adb 注入 tap），
  或者戴上真机——独立详情窗口和主窗口分栏两种场景都要看，确认：点击开合面板、loading→有数据/"暂无相关
  资讯"两种转场、点一条新闻能展开收起、切换选中股票时面板会自动收起。顺带对 `Subwindow` 相对各自宿主
  窗口的摆放位置做个第一印象判断，是不是长在预期的地方。
- **这一版明确不做**（免得以后有人以为漏了、误加回来）：个股公告/大事，跳转到外部原文链接，分页/
  "加载更多"，跨进程重启的资讯持久化缓存。
- **和设计稿的一处偏差**：设计文档原来画的是 `LazyColumn`，实现用的是普通 `Column` + `forEach`——列表
  上限 20 条且已经在外层 `verticalScroll` 里，不需要 `LazyColumn` 的按需组合，属于合理偏差。

## 下一步

1. ~~用真实的 `WatchlistScreen`（自选股列表 + 编辑模式 + 查询子视图）替换 `HomePage.kt`，先接本地假数据。~~
   **已完成（2026-08-10）**，见上面"关键文件"和截图证据。
2. ~~实现 `LocalStore`（自选股/标记/排序持久化），把 `StockRepository` 的内存态换成真正落盘。~~
   **已完成（2026-08-10）**，`WatchlistLocalStore`（`SharedPreferences`），已验证跨进程重启存活，见上面
   "持久化"小节和 `persistence_verified.png`。
3. ~~按设计文档第 3 节声明第二个 `WindowContainer`，实现点击个股打开详情窗口的完整链路。~~
   **基本完成（2026-08-10）**，`spatial-design-first-build` 门禁 B 跑了两轮，还有 2 个未闭环问题（MACD 面板
   默认视口外可见性、"近1月"区间切换未确认），见上面"详情窗口验证"——**下一个人接手前先看那一节，别重新踩同
   样的坑或者以为这两条已经解决了**。
4. ~~`IndicatorEngine`（MA/MACD 先做真实计算）+ K 线图渲染。~~ **已完成**：MA5/10/20 + MACD 是真实计算
   （`data/StockMath.kt`）。
5. ~~`StockDataRepository`（东财/新浪 + 抽象层），先接一个源验证真实数据能显示，再补容灾切换，替换掉
   `StockRepository` 里硬编码的假数据。~~ **已完成（2026-08-10）**，Eastmoney 主 + Sina 备用，已截图验证
   真实报价正确显示（贵州茅台 1348.86/+3.03%，和手动 curl 核实的接口返回值一致），见上面"数据源"小节。
6. ~~KDJ/RSI/BOLL 真实计算。~~ **已完成（2026-08-11）**，`computeKdj`/`computeRsi`/`computeBoll`
   （`data/StockMath.kt`），KDJ/RSI 各有独立的 Canvas 面板（`KdjChart.kt`/`RsiChart.kt`），BOLL 叠加在
   K 线图本身（`KLineChart.kt`）。已用"临时强制默认开启→截图→改回"的方式在模拟器上验证三个指标都画出了
   真实、彼此不同的曲线（不是占位文字，也不是三条重合的假线）。当时记着的"`rangeOptions` 点数
   （10/20/30）撑不满 BOLL/MA20 的 20 点窗口"这条限制**已在契约 v3 里解决**（放大成 22/66/220），
   见上面"指标"小节。
7. ~~详情窗口按设计契约 v3 改版。~~ **已完成（2026-08-11）**，5 轮门禁 B，见上面"详情窗口 v3 改版验证"。
   `rangeOptions` 点数放大、K 线改用真实 OHLC（不再用 `buildCandles` 二次合成）这两条老待办也随之闭环。
8. ~~主窗口按设计契约 v2 改成左右分栏。~~ **已完成（2026-08-11）**，4 轮门禁 B，见上面"主窗口 v2 分栏
   改版验证"。
9. ~~K 线只有两根 / 时好时坏。~~ **已解决（2026-08-11）**：换成新浪日线当主历史源 + `refreshAll` 改小批量
   并发 + `mergeLatestPrice` 按日期合并，见上面"数据源"。已截图验证默认态就是完整多日蜡烛图
   （`kline-sina-primary.png`，均线真实值/X轴日期到今天/极值标注/MACD 柱状图/列表迷你走势线全部到位）。
10. **等 `push2`（quote）恢复后，补两件事**（注意它和 `push2his` 的可达性是独立的）：
   (a) 用**一次**请求核实 quote 扩展字段码和缩放倍率，把基本参数网格里"—"的市值/流通/市盈(TTM)/量比/
   换手，以及"更多信息"里那批字段接上（契约 §2.5/§2.6 已列出候选字段码）；(b) 确认 kline 链路真的能成功、
   补做契约 §4 第 3 张截图（近1年 + M180），并验证有界 `beg` 那个形式是否被接口接受——如果接受，
   `fetchHistory` 里那条 `?: fetchKline(code, days, "0")` 兜底就可以留着当保险而不是主路径。
   探测方法和"别再快速连发"的教训见上面"本机环境注意事项"。
11. **三项待你决策的产品问题**：详情窗口能不能靠系统 caption bar 关掉（关系到要不要把关闭按钮加回来）、
    独立窗口的 640×480dp 要不要改大、10 个指标芯片要不要换行（后两条在主窗口右侧分栏区里都不成问题，
    那边约 680dp 宽）。见上面"详情窗口 v3 改版验证"。
12. **搜索结果行的 `clickable()` 仍缺 SpatialUI 要求的 `LocalIndication.current` + 共享
    `controllerHapticFeedback`**——这条问题跟着代码从已删除的 `QueryScreen` 搬到了 `SearchPane.kt` 的
    `ResultRow`，`verify-design-style.sh` 大概率还是会报 1 个 R6 error。自选列表行那一处已在 v2 改造里
    顺带修掉了，照 `WatchlistPane.kt` 里 `WatchlistRow` 的写法改即可。
13. 语音控制——第一步是先验证语音识别引擎的技术路径（见上面"语音"小节的 ⚠️），别直接假设某个 API 存在。
14. **契约 v5「同花顺」改版（2026-08-24）已实现完毕，只欠一次门禁 B 截图验证。** 这一轮换掉了上面若干条
    v2 时代的设计决策，接手前先认清现状，别按旧假设施工：
    - **配色**：旧的"素雅"暖纸风 + 系统毛玻璃 → 深色不透明的"同花顺"盘面（`fillPrimary 0xFF0F1318` 系
      + 琥珀 `interaction`），两个窗口的 `materialbackground`/`enableMaterialBackground 都关成 false`，
      各自根部铺一层不透明 `fillPrimary`。见上面"「同花顺」深色不透明盘面配色"那一节。
    - **搜索/加删**：整窗接管的 `QueryScreen` → 只接管左栏的 `SearchPane`；"选中行才冒出加入按钮" →
      行尾一颗 `Membership` 状态机驱动的四态钮 `MembershipToggle`（＋/◌/✓/!），状态由
      `resolveMembership(inWatchlist, pending)` 单一推导。
    - **移除/加入**：二次确认 `AlertDialog` → 即时执行 + 5 秒撤销条 `UndoBar`，**加和删都有**。
    - **排序**：不再被编辑模式门住，表头随时可点；编辑模式只剩行尾 `✕`。
    - **星标**：`Stock.star` / `StockRepository.toggleStar` / 行 `leadingContent` 的星标钮 /
      `WatchlistLocalStore` 的加星持久化**全部删除**，不要再加回来。
    - **文件**：`WatchlistScreen.kt` / `QueryScreen.kt` 已不存在，拆成
      `WatchlistHost.kt` / `WatchlistPane.kt` / `SearchPane.kt` / `MembershipToggle.kt`。
    - ⚠️ **还欠什么**：这一轮**完全没有在设备或模拟器上跑过截图验证**（`./gradlew :app:assembleDebug` 和
      `:app:testDebugUnitTest` 都是绿的，但那只证明能编译能过单测）。深色底到底够不够不透明、选中行的
      `fillSecondary` 底 + 3dp 琥珀条在头显里看不看得出来、撤销条浮层会不会挡住列表最后一行、四态钮的
      `Adding` 转环在真机上是否可见——**这些都还没有画面证据**。下一步就是按
      `.spatialsdk/gate-b-verify.sh` 跑一轮门禁 B。

建议用 `spatial-app-dev-workflow` 技能逐条实现以上任务（一次一个需求，构建→装→模拟器截图验证→修
crash→下一条）。

## ⚠️⚠️ 头显的输入是 GAZE_PINCH，2D 手势通路一律无效（2026-08-11 实测）

**这是本项目踩得最深的一个坑，连错两版才靠真机日志纠正。** K 线横向拖拽在模拟器上好使、真机上完全不动，
两次基于 SDK 源码推理的改法都被数据否掉：

| 试过的通路 | 结果 |
|---|---|
| `pointerInput { detectHorizontalDragGestures(...) }` | 真机无效 |
| `Modifier.scrollable(...)` + `focusable` | 模拟器有效、**真机仍无效** |
| **`detectSpatialDragGesture(context, targetedToEntity = null)`** | ✅ 真机实测可用（用户 2026-08-11 确认） |

真因来自设备自己的 `ViewRootImpl` 输入日志（29 段完整按下序列、95 条空间事件）：

- 输入类型 **`kind=GAZE_PINCH` 占 95/95** —— 交互是**眼动注视 + 手部捏合**，不是控制器射线。
- **捏合拖拽时注视点几乎不动，而 MotionEvent 的 2D `x/y` 就是注视点**；手的位移只存在于 3D 字段
  （`SelectEvent3D.mInputDevicePosition*`）。所以每段按下的 `ACTION_MOVE` 大多**只有 1 次**，
  任何基于 2D touch slop 的检测器都拿不到连续位移。
- `ScrollEvent3D` 有效 **0 次** → `Modifier.scrollable` 永远等不到滚动量。
- `DragEvent3D` 有效 **23 次** → 真实拖拽量只在这条空间通路上，
  而 `detectSpatialDragGesture` 读的正是它（`getRuntimeDragData`）。SDK 自带 sample
  `SpatialDragSampleForUI` 本来就是给 2D 面板用的。

**规则：头显上任何拖拽/缩放/旋转，一律用 `com.pico.spatial.ui.foundation.gesture.detectSpatial*Gesture`，
不要用 androidx 的 2D 手势或 `scrollable`/`draggable`。** 点击可以用 2D 通路（`clickable`/`detectTapGestures`
实测一直好使），竖向页面滚动也能用 `verticalScroll`——只有**拖拽类连续手势**必须走空间 API。

顺带记一条被推翻的推理，免得下次又绕进去：`IndirectPointerInputModifierNode` 的 KDoc 说 indirect 事件按
**焦点**路由、`Scrollable` 自带的 `FocusTargetModifierNode` 是 `Focusability.Never`——这些都是真的，
但**和这台设备的输入路径无关**（它根本不走 indirect pointer）。源码自洽 ≠ 现场成立，**先量再改**。

### 真机 logcat 只保留约 8 分钟，探针必须当场读

实测：`adb logcat -d` 全缓冲区 153403 行只覆盖 06:28–06:36，本应用进程只剩 88 行，
埋的探针日志**一条不剩**（被内核日志洪流挤掉，约 1.8 万行/分钟）。

所以真机埋点取证要么**操作完立刻读**，要么开后台 `adb logcat` 实时落盘，不能隔几分钟回来捞。
另外这台设备 **`persist.log.tag=I`，`Log.d` 被全局过滤**——探针一律用 `Log.i`，否则你会得到一个哑仪器
（第一版探针就是这么白费一轮的）。

## 窗口尺寸的三处耦合

改任何一个都要同步另外两个，否则同一份 [DetailScreen] 在分栏区和独立窗口里排版会分叉：

| 位置 | 值 | 含义 |
|---|---|---|
| `AndroidManifest.xml` 的 `windowcontainer.defaultsize` | `1080x660` | 主窗口 |
| `WatchlistPane.kt` 的 `ListPaneWidth` | `400.dp` | 左侧列表栏 |
| `Main.kt` 的 `DetailWindowWidth/Height` | `680.dp / 660.dp` | 独立详情窗口 = (1080−400) × 主窗口高 |

## 多个详情窗口靠 tag，不靠多声明 container

`SpatialNavigator.openWindowContainer(id, tag, bundle)` 的 KDoc 原文：tag 为 null **每次都新建**实例；
tag 非 null 时同 id 同 tag 会**复用**，换新 tag 又是新实例。所以一个 `WindowContainer` 声明就够了，
用 `detailWindowTag(code) = "detail-$code"` 做实例键：不同股票各开一个窗口，同一只再点复用已开的那个。

**`closeWindowContainer(id, tag = null)` 会关掉该 id 下的所有窗口**——早先"只能开一个详情窗口"就是因为
每次开新窗口前先无差别关了一次。要关某一个就必须带上 tag。
