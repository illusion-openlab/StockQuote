package tech.illusion.stockquote.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
import tech.illusion.stockquote.data.Candle
import tech.illusion.stockquote.data.KPeriod
import tech.illusion.stockquote.data.QuoteSnapshot
import tech.illusion.stockquote.data.StockRepository
import tech.illusion.stockquote.data.aggregateCandles
import tech.illusion.stockquote.data.buildCandles
import tech.illusion.stockquote.data.computeBoll
import tech.illusion.stockquote.data.computeKdj
import tech.illusion.stockquote.data.computeMacd
import tech.illusion.stockquote.data.computeRsi
import tech.illusion.stockquote.data.simpleMovingAverage

// 分时 Tab 已移除（契约 v3 §1）：没有分钟级数据源之前它只能是个点了没反应的视觉选中态，
// 连"禁用占位"都不需要。周K/月K 现在是**真实数据**——优先新浪原生周/月线，抓到之前用
// [aggregateCandles] 本地合并兜底——不再是视觉选中态（契约 v3 §3 曾把它列进不做清单，用户
// 2026-08-11 要求补上）。
private val periods = KPeriod.entries

/**
 * 区间档位，**每个周期各有一套，数字直接就是根数**。
 *
 * 早先的写法是一套共用档位（近1月/近3月/近1年，按交易日计）再除以"每根覆盖几个交易日"换算成根数，
 * 结果"近3月 + 月K"只有 4 根月线——用户 2026-08-11 反馈"明显不合理"，判断没错：月线图至少要几十根
 * 才看得出趋势，而档位标签诚不诚实在图都没法看的时候根本不重要。现在每个周期用自己的跨度，
 * 标签和实际跨度仍然一一对应，同时每档都落在 12–220 根这个能看的区间里。
 *
 * 三个周期都是 3 档，所以切周期时 `rangeIndex` 永远有效，不需要夹紧。
 */
private val rangeOptionsByPeriod: Map<KPeriod, List<Pair<String, Int>>> = mapOf(
    KPeriod.Daily to listOf("近1月" to 22, "近3月" to 66, "近1年" to 220),
    KPeriod.Weekly to listOf("近3月" to 13, "近1年" to 52, "近3年" to 156),
    KPeriod.Monthly to listOf("近1年" to 12, "近3年" to 36, "近5年" to 60),
)

/**
 * 副图指标芯片（第二行）。均线那批不在这里——它们跟着 [MaLines] 走，和区间档位一起排在第一行。
 *
 * 固定顺序：`SnapshotStateMap` 不保证遍历顺序，渲染必须用一份显式排好序的列表驱动，开关状态另存在 map 里。
 */
private val SubPanelChips: List<Pair<String, String>> =
    listOf("vol" to "VOL", "macd" to "MACD", "kdj" to "KDJ", "rsi" to "RSI", "boll" to "BOLL")

/** 图表区内边距。手势挂在 padding 之外，所以点击坐标要减掉它才是画布局部坐标。 */
private val ChartInnerPadding = 8.dp

private fun exchangeSuffix(code: String): String = when (code.firstOrNull()) {
    '6' -> " · 沪"
    '0', '3' -> " · 深"
    else -> ""
}

/** One swatch + label + current value, the shared shape of the MA legend and every panel title. */
private data class LegendEntry(val label: String, val value: String, val color: Color)

private data class ParamCell(val label: String, val value: String, val color: Color? = null)

/**
 * 个股详情内容（设计契约 v3）。**这是全项目唯一一份详情实现**：主窗口右侧分栏区和独立
 * `DetailWindow` 都渲染它，不存在"独立窗口专用"的第二套设计（用户 2026-08-11 裁决）。
 *
 * 所以这里刻意**不含**任何宿主级操作入口（"在新窗口打开"、"更多"菜单）——那些属于宿主（主窗口的分栏
 * 壳层）。放进来的话在独立窗口里也会冒出一个"在新窗口打开"，而那时你已经在新窗口里了。
 *
 * @param headerEndInset 头部行右侧要让出的宽度。宿主如果在右上角浮了自己的按钮（主窗口分栏区的"更多"
 *   菜单就是），把按钮宽度传进来，价格数字才不会和它撞在一起。独立窗口没有那种按钮，传默认的 0。
 */
@Composable
fun DetailScreen(code: String?, headerEndInset: Dp = 0.dp) {
    val stock = code?.let { c -> StockRepository.universe.find { it.code == c } }
    val context = LocalContext.current
    val chartInnerPaddingPx = with(LocalDensity.current) { ChartInnerPadding.toPx() }

    // 无自绘关闭按钮：每个 WindowContainer 都有系统 caption bar（契约 v3 §1）。这一条是本轮
    // 门禁 B 截图要专门确认的事项——若截图证明系统 caption bar 上没有关闭控件，按钮要加回来。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (stock == null) {
            Text(
                text = "未找到股票数据",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelTertiary,
            )
            return@Column
        }

        var periodIndex by remember { mutableIntStateOf(0) }
        var rangeIndex by remember { mutableIntStateOf(1) }
        var moreInfoOpen by remember { mutableStateOf(false) }
        val indicators = remember {
            mutableStateMapOf(
                "ma5" to true, "ma10" to true, "ma20" to true, "ma30" to false, "ma180" to false,
                "vol" to false, "macd" to true, "kdj" to false, "rsi" to false, "boll" to false,
            )
        }

        // 真实 OHLCV 优先。只有在从未抓到任何 kline（只有 quote 快照或种子兜底）时才退回
        // buildCandles 的合成蜡烛——那种情况下所有"数值"字段一律显示 NoValue，绝不把合成的
        // 成交量/开高低当成真实测量值报给用户。
        val hasRealBars = stock.candles.size >= 2
        val period = periods[periodIndex]

        // **日线全量**。两个用途：一是周期聚合的输入，二是头部涨跌幅和基本参数网格取"今日"值的唯一来源。
        // 后者是关键：那些字段描述的是今天这一天，绝不能跟着 K 线周期变——切到月K时"今开"必须还是今天的
        // 开盘价、涨跌幅必须还是相对昨收，而不是本月首日的开盘价和上月收盘。
        val dailyBars = remember(stock.candles, stock.hist) {
            if (hasRealBars) stock.candles else buildCandles(stock.hist)
        }
        val dailyCloses = remember(dailyBars) { dailyBars.map { it.close } }

        // **整条周期序列**（不切区间）。周/月线优先用新浪的原生 K 线（深度大得多：120 根月线回溯到 2016 年，
        // 而本地合并 500 根日线只能给 24 根月线）；还没抓到时退回本地合并，所以不需要任何加载态——先看到
        // 浅一点的图，抓到了自动变深。日线不走原生：refreshAll 那份已经被 mergeLatestPrice 并进了实时报价。
        LaunchedEffect(stock.code, period) { StockRepository.ensurePeriodHistory(stock.code, period) }
        val nativeSeries = StockRepository.periodBars(stock.code, period)
        // 聚合顺序不能反——先截区间再聚合，边界那根周/月线只含区间内的几天，会画出一根缺胳膊少腿的 bar，
        // 而且开盘价是区间起点那天的、不是那一周/月真正的开盘。
        val series = remember(dailyBars, periodIndex, nativeSeries) {
            nativeSeries ?: aggregateCandles(dailyBars, period)
        }

        // 可见窗口 = 这条序列上的一个滑动切片：宽度由区间档位决定，位置由横向拖拽决定。
        val rangeOptions = rangeOptionsByPeriod.getValue(period)
        val barCount = rangeOptions[rangeIndex].second
        val pan = remember { ChartPan() }

        pan.maxOffsetBars = (series.size - barCount).coerceAtLeast(0)
        // 归零的 key 只能是这三个。**不能**把 series 放进来：每次轮询刷新都会换一个新 list，
        // 那样用户正翻着历史就会被定时弹回最右端。
        LaunchedEffect(code, periodIndex, rangeIndex) { pan.reset() }
        val offset = pan.visibleOffset()
        val candles = remember(series, barCount, offset) {
            if (series.isEmpty()) return@remember emptyList()
            val end = (series.size - offset).coerceIn(1, series.size)
            series.subList((end - barCount).coerceAtLeast(0), end)
        }
        val closes = remember(candles) { candles.map { it.close } }

        val macd = remember(closes) { computeMacd(closes) }
        val kdj = remember(candles) { computeKdj(candles) }
        val rsi6 = remember(closes) { computeRsi(closes, 6) }
        val rsi12 = remember(closes) { computeRsi(closes, 12) }
        val boll = remember(closes) { computeBoll(closes) }

        // 整屏兜底只看**日线**够不够：日线不足 2 点意味着连价格和涨跌幅都算不出来，这一屏没有任何内容可显示。
        // 聚合后区间内不足 2 根是另一回事（"近1月 + 月K"就是），那种情况只让图表区给提示——见下面的 chartReady。
        // 这里绝不能用 closes（周期序列）判：周期/区间选择器都排在图表下方，整屏 return 会把选择器一起藏掉，
        // 用户就被锁在那个组合里切不回来了。
        if (dailyCloses.size < 2) {
            Text(
                text = "行情数据不足，无法绘制 K 线",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelTertiary,
            )
            return@Column
        }

        /** 图表区能不能画。不足 2 根时图表给提示，但头部/参数网格/选择器照常渲染。 */
        val chartReady = closes.size >= 2

        val latestDaily = dailyBars.last()
        val price = dailyCloses.last()
        val prevClose = dailyCloses[dailyCloses.size - 2]
        val change = (price - prevClose) / prevClose * 100
        val changeColor = if (change >= 0) StockUpColor else StockDownColor

        // 今日各项优先取 quote 快照：新浪那条链路真实给到今开/最高/最低/昨收/成交量/成交额，字段
        // 位置已用"成交额÷成交量≈现价"实测反向核对过（见 SinaSource 的表）。没有快照时退回真实 kline
        // 的最后一根；两者都没有就是 NoValue——绝不用推算值冒充数据。
        // 一律取 latestDaily（日线末根），不是 candles 末根——周期序列末根在周K/月K下是整周/整月的
        // 开高低量额，拿它填"今开/最高/最低"就是把本月的数字标成今天的。
        val snapshot = stock.snapshot
        val todayOpen = snapshot?.open ?: latestDaily.open.takeIf { hasRealBars }
        val todayHigh = snapshot?.high ?: latestDaily.high.takeIf { hasRealBars }
        val todayLow = snapshot?.low ?: latestDaily.low.takeIf { hasRealBars }
        val todayBase = snapshot?.prevClose ?: prevClose
        val todayLots = snapshot?.volumeLots ?: latestDaily.volume.takeIf { hasRealBars }
        val todayAmount = snapshot?.amountYuan
            ?: latestDaily.amount.takeIf { hasRealBars && latestDaily.amount > 0.0 }

        /** 今开/最高/最低 各自独立判断红涨绿跌（契约 v3 §1），不是整行只看一个方向。 */
        fun priceCell(label: String, value: Double?): ParamCell = ParamCell(
            label = label,
            value = value?.let { formatPrice(it) } ?: NoValue,
            color = value?.let { if (it >= todayBase) StockUpColor else StockDownColor },
        )

        Row(
            // 右侧让出宿主浮层按钮的宽度，否则价格会和它重叠（见 headerEndInset 的说明）
            modifier = Modifier.fillMaxWidth().padding(end = headerEndInset),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stock.name, style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
                Text(
                    text = stock.code + exchangeSuffix(stock.code),
                    style = PicoTheme.typography.bodySmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPrice(price), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
                Text(
                    text = "%s%s".format(if (change >= 0) "+" else "", formatPercent(change)),
                    style = PicoTheme.typography.labelMedium,
                    color = changeColor,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // 基本参数 3×3（契约 v3 §1/§2.5）。今开/最高/最低/成交额来自 kline 行本身，是真实值；
        // 市值/流通市值/市盈率/量比/换手率要靠 quote 端点的扩展字段，字段码尚未核实（东财对本机
        // 限流），按契约"拿不到就显示 —，不用假数据凑数"的原则先显示 NoValue。
        ParamGrid(
            listOf(
                listOf(priceCell("今开", todayOpen), priceCell("最高", todayHigh), priceCell("最低", todayLow)),
                // 市值/流通市值/市盈率(TTM)/量比/换手率 —— 契约 §2.5 标的"中把握"那批，现在有真值了：
                // 来自腾讯 qt.gtimg.cn（唯一给这些字段的可用源，字段下标全部算术校验过，见 TencentSource）。
                // ⚠️ 市值单位是**亿元**，必须用 formatYi 而不是 formatYuan，否则差 8 个数量级。
                listOf(
                    ParamCell("市值", snapshot?.marketCapYi?.let { formatYi(it) } ?: NoValue),
                    ParamCell("流通", snapshot?.floatMarketCapYi?.let { formatYi(it) } ?: NoValue),
                    ParamCell("市盈(TTM)", snapshot?.peTtm?.let { formatRatio(it) } ?: NoValue),
                ),
                listOf(
                    ParamCell("量比", snapshot?.volumeRatio?.let { formatRatio(it) } ?: NoValue),
                    ParamCell("换手", snapshot?.turnoverRatePercent?.let { formatPercent(it) } ?: NoValue),
                    ParamCell("额", todayAmount?.let { formatYuan(it) } ?: NoValue),
                ),
            ),
        )

        // "更多信息"折叠区紧跟在基本参数网格下方——契约 v3 §2.6 原文是"基本参数网格下方一个默认折叠的
        // 更多信息展开区"，v3 原型也画在这个位置。（第一轮门禁 B 比对时它被放在了最底部、所有子面板之后，
        // 判为 Major 并在第二轮修正。折叠态只占一行，不会把图表推太远。）
        Spacer(modifier = Modifier.height(6.dp))
        ToggleableChip(
            label = { Text(if (moreInfoOpen) "更多信息 ▴" else "更多信息 ▾") },
            isToggleOn = moreInfoOpen,
            onClick = { moreInfoOpen = !moreInfoOpen },
        )
        if (moreInfoOpen) {
            Spacer(modifier = Modifier.height(8.dp))
            MoreInfoSection(
                snapshot = snapshot,
                prevClose = snapshot?.prevClose ?: prevClose.takeIf { hasRealBars },
                high = todayHigh,
                low = todayLow,
                volumeLots = todayLots,
                amountYuan = todayAmount,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        SegmentControl(modifier = Modifier.fillMaxWidth()) {
            periods.forEachIndexed { index, item ->
                SegmentItem(selected = periodIndex == index, onClick = { periodIndex = index }, title = { Text(item.label) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 十字光标选中的 bar 下标（相对当前可见窗口）。点图表选中、再点同一根取消。
        // 平移/换周期/换区间/换标的都要清掉：窗口一动，同一个下标指的就是另一根 bar 了。
        // 十字光标选中的 bar 下标（相对当前可见窗口）。点图表选中、再点同一根取消，也可以用读数行右边的
        // ‹ › 逐根步进（同花顺也是这么给的：先大致定位，再微调到确切那一根）。
        // 平移/换周期/换区间/换标的都要清掉：窗口一动，同一个下标指的就是另一根 bar 了。
        var selectedIndex by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(code, periodIndex, rangeIndex, offset) { selectedIndex = null }
        val selected = selectedIndex?.takeIf { it in candles.indices }?.let { candles[it] }

        // **所有面板的读数都用这一个下标**：选中时是那一根，没选中时是区间内最新那根。主图均线、成交量、
        // MACD、KDJ、RSI 全部跟着它走——这就是"选中某个数据展示具体数值"的落地方式，和同花顺各面板
        // 标题行随十字线联动的行为一致。
        val valueIndex = selectedIndex?.coerceIn(candles.indices) ?: candles.lastIndex

        if (selected != null) {
            SelectedBarReadout(
                candle = selected,
                previousClose = selectedIndex?.takeIf { it > 0 }?.let { candles[it - 1].close },
                showMeasured = hasRealBars,
                onStep = { delta ->
                    selectedIndex = ((selectedIndex ?: candles.lastIndex) + delta).coerceIn(candles.indices)
                },
                onDismiss = { selectedIndex = null },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 均线图例条：只列出已开启的均线。窗口还没填满时（例如"近1月"档位下的 M180）显示 NoValue，
        // 而不是拿一个不存在的数字充数。
        val activeMaKeys = MaLines.map { it.key }.filter { indicators[it] == true }.toSet()
        if (activeMaKeys.isNotEmpty()) {
            val maAtIndex = remember(closes, valueIndex) {
                MaLines.associate { line ->
                    line.key to simpleMovingAverage(closes, line.period).getOrNull(valueIndex)
                }
            }
            LegendStrip(
                entries = MaLines.filter { it.key in activeMaKeys }.map { line ->
                    LegendEntry(
                        label = line.label,
                        value = maAtIndex[line.key]?.let { formatPrice(it) } ?: NoValue,
                        color = line.color,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(PicoTheme.colorScheme.fillTertiary, RoundedCornerShape(12.dp))
                // 横向平移。**必须走空间手势，2D 手势通路在头显上一律无效**——真机 GAZE_PINCH 拖拽时
                // 注视点几乎不动，2D 坐标就是注视点，位移只存在于 3D 字段里；`ScrollEvent3D` 更是从来
                // 没有效过。详细的实测数据见 [ChartPan] 的说明。
                // key 固定 Unit：SDK 手势只保留首次传入的 lambda，回调里只调 pan 的方法、不捕获组合期
                // 局部量（pico-sdk-compose-pitfalls 陷阱 1），pan 的身份在整个生命周期里不变。
                // 放在 padding 之前，让整块图表区（含 8dp 内边距）都能拖。
                .pointerInput(Unit) {
                    detectSpatialDragGesture(context = context, targetedToEntity = null) { value ->
                        pan.onDragPx(value.dragAmount.x)
                    }
                }
                // 十字光标：点选最近的一根 bar，再点同一根取消。
                //
                // 用**点击**而不是悬停跟随，是被平台约束逼出来的：真机输入日志里 `HoverEvent3D` 有效 0 次，
                // 悬停位置根本拿不到。但注视点作为"指哪儿"是准的（不准的只是捏合过程中的连续位移，
                // 见 [ChartPan]），所以点击选中完全可靠。
                //
                // 和上面的拖拽分成两个 pointerInput 是可以的：detectSpatialDragGesture 只在越过 slop、
                // 真的开始拖之后才 consume，纯点击不会被它吃掉。（WatchlistRow 那条"点击和长按必须写在
                // 同一个 detectTapGestures 里"的教训不适用——那是**同族**两个检测器互相抢事件。）
                .pointerInput(Unit) {
                    detectTapGestures(context = context, targetedToEntity = null) { offset ->
                        // 手势在 padding 之外，所以点击坐标要减掉内边距才是画布局部坐标。
                        val index = pan.axis?.indexAt(offset.x - chartInnerPaddingPx)
                        selectedIndex = if (index != null && index == selectedIndex) null else index
                    }
                }
                .padding(ChartInnerPadding),
            contentAlignment = if (chartReady) Alignment.TopStart else Alignment.Center,
        ) {
            if (chartReady) {
                KLineChart(
                    candles = candles,
                    overlays = KLineOverlays(
                        activeMaKeys = activeMaKeys,
                        boll = if (indicators["boll"] == true) boll else null,
                        annotateExtremes = hasRealBars,
                        selectedIndex = selectedIndex,
                    ),
                    upColor = StockUpColor,
                    downColor = StockDownColor,
                    axisColor = PicoTheme.colorScheme.labelTertiary,
                    modifier = Modifier.fillMaxSize(),
                    onAxis = { pan.axis = it },
                )
            } else {
                // 兜底路径：整条序列还不够一个窗口宽（新上市个股，或周/月线原生数据还没抓到、
                // 本地聚合只合出一两根）。刻意不在这里悄悄放宽区间去凑够根数——那会让档位标签变成假的。
                Text(
                    text = "${rangeOptions[rangeIndex].first}只有 ${closes.size} 根${period.label.replace(" ", "")}，" +
                        "换个更长的区间看这个周期",
                    style = PicoTheme.typography.bodySmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 芯片分成两行，按"作用在哪张图上"分（用户 2026-08-11：作用于不同视图的指标不要挤在同一行）：
        //
        //  第一行 = 区间档位 + 均线 —— 都只改**主图**：档位决定画多少根，均线画在蜡烛上面。
        //  第二行 = VOL/MACD/KDJ/RSI/BOLL —— 用户点名的"其它几个指标"，各自开一块**副图**面板。
        //
        // ⚠️ 有一条不一致值得知道：BOLL 其实也画在主图上（走 KLineOverlays，不是 SubPanel），按上面
        // 那条"作用在哪张图"的原则它更该待在第一行。这里按用户的原话"其它几个指标在另一行"放第二行，
        // 要调整的话把 "boll" 从 SubPanelChips 挪到第一行即可。
        //
        // 区间行右侧的"双指缩放/语音"提示文案已按契约 v3 删除（用户反馈那是通识，不值得占位置）。
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rangeOptions) { (label, _) ->
                val index = rangeOptions.indexOfFirst { it.first == label }
                ToggleableChip(
                    label = { Text(label) },
                    isToggleOn = rangeIndex == index,
                    onClick = { rangeIndex = index },
                )
            }
            items(MaLines) { line ->
                ToggleableChip(
                    label = { Text(line.label) },
                    isToggleOn = indicators[line.key] == true,
                    onClick = { indicators[line.key] = indicators[line.key] != true },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SubPanelChips) { (key, label) ->
                ToggleableChip(
                    label = { Text(label) },
                    isToggleOn = indicators[key] == true,
                    onClick = { indicators[key] = indicators[key] != true },
                )
            }
        }

        // 四个指标面板都跟着**周期序列**走，所以周K下 MACD 就是周MACD、成交量就是整周成交量——这是行情
        // 软件的口径。不足 2 根时整批跳过（图表区已经给了提示），芯片行照常留着让用户能切回来。
        if (chartReady && indicators["vol"] == true) {
            SubPanel(
                title = "成交量 " + if (hasRealBars) formatLots(candles[valueIndex].volume) else NoValue,
                legend = VolumeMaLines.map { line ->
                    val series = remember(candles, line.period) {
                        simpleMovingAverage(candles.map { it.volume }, line.period)
                    }
                    LegendEntry(
                        label = line.label,
                        value = series.getOrNull(valueIndex)?.let { formatLots(it) } ?: NoValue,
                        color = line.color,
                    )
                },
            ) {
                VolumeChart(
                    candles = candles,
                    upColor = StockUpColor,
                    downColor = StockDownColor,
                    selectedIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }

        if (chartReady && indicators["macd"] == true) {
            val histogram = macd.histogram[valueIndex]
            SubPanel(
                title = "MACD（12,26,9）",
                legend = listOf(
                    LegendEntry("DIFF", formatPrice(macd.diff[valueIndex]), PicoTheme.colorScheme.interaction),
                    LegendEntry("DEA", formatPrice(macd.signal[valueIndex]), PicoTheme.colorScheme.alert),
                    LegendEntry("MACD", formatPrice(histogram), if (histogram >= 0) StockUpColor else StockDownColor),
                ),
            ) {
                MacdChart(
                    macd = macd,
                    upColor = StockUpColor,
                    downColor = StockDownColor,
                    diffColor = PicoTheme.colorScheme.interaction,
                    signalColor = PicoTheme.colorScheme.alert,
                    selectedIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }

        if (chartReady && indicators["kdj"] == true) {
            SubPanel(
                title = "KDJ（9,3,3）",
                legend = listOf(kdj.k[valueIndex], kdj.d[valueIndex], kdj.j[valueIndex]).mapIndexed { index, value ->
                    LegendEntry(KdjLines[index].label, "%.2f".format(value), KdjLines[index].color)
                },
            ) {
                KdjChart(kdj = kdj, selectedIndex = selectedIndex, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        }

        if (chartReady && indicators["rsi"] == true) {
            SubPanel(
                title = "RSI（6,12）",
                legend = listOf(rsi6, rsi12).mapIndexed { index, series ->
                    LegendEntry(
                        label = RsiLines[index].label,
                        value = series.getOrNull(valueIndex)?.let { "%.2f".format(it) } ?: NoValue,
                        color = RsiLines[index].color,
                    )
                },
            ) {
                RsiChart(rsi6 = rsi6, rsi12 = rsi12, selectedIndex = selectedIndex, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        }
    }
}

/**
 * "更多信息"展开区（契约 v3 §2.6）。只显示能从真实数据（quote 快照或 kline 行）直接得到或直接算出
 * 的字段；需要 eastmoney quote 扩展字段的（委比、内外盘、总股本、市净率…）一律 NoValue，等字段码
 * 核实后再接。
 *
 * 涨停/跌停刻意不用"昨收×1.1"推算——主板/创业板/科创板/ST 的涨跌幅限制各不相同，算出来的是规则
 * 假设而不是行情数据，宁可显示"—"。
 */
@Composable
private fun MoreInfoSection(
    snapshot: QuoteSnapshot?,
    prevClose: Double?,
    high: Double?,
    low: Double?,
    volumeLots: Double?,
    amountYuan: Double?,
) {
    val amplitude = if (high != null && low != null && prevClose != null && prevClose > 0.0) {
        formatPercent((high - low) / prevClose * 100)
    } else {
        NoValue
    }
    // 均价 = 成交额 / 成交股数，成交量单位是手（1 手 = 100 股）。
    val averagePrice = if (volumeLots != null && amountYuan != null && volumeLots > 0.0) {
        formatPrice(amountYuan / (volumeLots * 100))
    } else {
        NoValue
    }
    ParamGrid(
        listOf(
            listOf(
                ParamCell("昨收", prevClose?.let { formatPrice(it) } ?: NoValue),
                ParamCell("振幅", amplitude),
                ParamCell("均价", averagePrice),
            ),
            listOf(
                ParamCell("总手", volumeLots?.let { formatLots(it) } ?: NoValue),
                // 涨停/跌停从源直接取，不用"昨收×1.1"推算——创业板/科创板/ST 的限制各不相同
                ParamCell("涨停", snapshot?.limitUp?.let { formatPrice(it) } ?: NoValue),
                ParamCell("跌停", snapshot?.limitDown?.let { formatPrice(it) } ?: NoValue),
            ),
            listOf(
                // 委比要委买/委卖总量才能算，源只给了委差，所以这一项仍然空着
                ParamCell("委比", NoValue),
                ParamCell("内盘", snapshot?.innerVolLots?.let { formatLots(it) } ?: NoValue),
                ParamCell("外盘", snapshot?.outerVolLots?.let { formatLots(it) } ?: NoValue),
            ),
            listOf(
                ParamCell("总股本", snapshot?.totalShares?.let { formatShares(it) } ?: NoValue),
                ParamCell("流通股", snapshot?.floatShares?.let { formatShares(it) } ?: NoValue),
                // 每股收益/每股净资产/ROE：响应里有几个没法校验的数，不猜着贴标签（那等于编数据）
                ParamCell("每股收益", NoValue),
            ),
            listOf(
                ParamCell("市净率", snapshot?.pb?.let { formatRatio(it) } ?: NoValue),
                ParamCell("每股净资产", NoValue),
                ParamCell("净资产收益率", NoValue),
            ),
        ),
    )
}

@Composable
private fun LegendStrip(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(entry.color, CircleShape))
                Text(
                    text = "${entry.label} ${entry.value}",
                    style = PicoTheme.typography.labelSmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
        }
    }
}

/**
 * 十字光标读数行：选中某根 bar 后显示**那一根**的日期与开高低收量，而不是最新值。
 *
 * 涨跌幅按"相对上一根的收盘"算——这才是那根 bar 自己的涨跌。窗口最左边那根没有上一根可比
 * （它的前一根在可见区间之外），这时显示 [NoValue] 而不是拿区间外的数据偷偷补上：那会让同一根 bar
 * 在平移前后显示不同的涨跌幅。
 *
 * @param showMeasured 蜡烛是不是真实抓来的。[buildCandles] 合成的开高低量是形状不是测量值，
 *   这种情况下一律显示"—"，绝不把合成数字当行情报给用户。
 */
@Composable
private fun SelectedBarReadout(
    candle: Candle,
    previousClose: Double?,
    showMeasured: Boolean,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val change = previousClose?.takeIf { it > 0.0 }?.let { (candle.close - it) / it * 100 }
    val closeColor = when {
        previousClose == null -> PicoTheme.colorScheme.labelPrimary
        candle.close >= previousClose -> StockUpColor
        else -> StockDownColor
    }

    @Composable
    fun cell(label: String, value: String, color: Color = PicoTheme.colorScheme.labelPrimary) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelTertiary)
            Text(value, style = PicoTheme.typography.labelSmall, color = color)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = candle.date.ifBlank { NoValue },
            style = PicoTheme.typography.labelMedium,
            color = PicoTheme.colorScheme.labelPrimary,
        )
        cell("开", if (showMeasured) formatPrice(candle.open) else NoValue)
        cell("高", if (showMeasured) formatPrice(candle.high) else NoValue, StockUpColor)
        cell("低", if (showMeasured) formatPrice(candle.low) else NoValue, StockDownColor)
        cell("收", formatPrice(candle.close), closeColor)
        cell(
            label = "涨跌",
            value = change?.let { "%s%s".format(if (it >= 0) "+" else "", formatPercent(it)) } ?: NoValue,
            color = closeColor,
        )
        cell("量", if (showMeasured) formatLots(candle.volume) else NoValue)

        // 逐根步进 + 取消。注视点只能把光标定位到"大概那一根"，密集档位（近1年 220 根）下一根只有几个像素，
        // 靠点击没法精确落到目标那根——所以必须给步进按钮。同花顺的 ‹ › 也是同样的作用。
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleableChip(label = { Text("‹") }, isToggleOn = false, onClick = { onStep(-1) })
            ToggleableChip(label = { Text("›") }, isToggleOn = false, onClick = { onStep(1) })
            ToggleableChip(label = { Text("✕") }, isToggleOn = false, onClick = onDismiss)
        }
    }
}

/** Shared container for the 成交量/MACD/KDJ/RSI panels: one title + its current values, then the chart. */
@Composable
private fun SubPanel(title: String, legend: List<LegendEntry>, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PicoTheme.colorScheme.fillTertiary, RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelQuaternary)
            LegendStrip(legend)
        }
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ParamGrid(rows: List<List<ParamCell>>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    // Modifier.weight 靠 RowScope 隐式 receiver 解析，不要显式 import（见 AGENTS.md 的 classpath 坑）
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = cell.label,
                            style = PicoTheme.typography.labelSmall,
                            color = PicoTheme.colorScheme.labelQuaternary,
                        )
                        Text(
                            text = cell.value,
                            style = PicoTheme.typography.labelSmall,
                            color = cell.color ?: PicoTheme.colorScheme.labelSecondary,
                        )
                    }
                }
            }
        }
    }
}
