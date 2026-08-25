package tech.illusion.stockquote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin `SharedPreferences`-backed persistence for watchlist membership/order state.
 *
 * 每条记录存 `code:name`——2026-08-25 之前只存了 code，靠"下次启动时这个 code 刚好能在 universe 里
 * 查到"来重建 Stock 对象；但 universe 每次冷启动都只重新播种 12 只本地库存股，任何通过全市场搜索
 * 加的自选股在重启后都无法解析、被 `StockRepository.init` 的 mapNotNull 悄悄丢弃——这正是"搜索加的
 * 股票重启后消失了"这个 bug 的根因。现在额外存一份名字，重启时对本地库里查不到的 code 用它重新发
 * 一次真实抓取（见 `StockRepository.resolvePendingWatchlist`），不再依赖"这次启动 universe 里刚好
 * 有它"这个偶然条件。
 *
 * 向后兼容旧格式：某一条如果不含 `:` 分隔符，就是这次改动之前存的裸 code，视为"名字未知"（空串）——
 * `ensurePreviewData` 在名字未知时会退化用抓取结果自带的名字，抓不到名字就用 code 本身兜底，不会崩。
 *
 * Deliberately not Room: the persisted shape is one small flat list, not relational data, and
 * pulling in Room would mean a new Gradle/KSP surface on top of a classpath that's already fragile
 * (see the `Modifier.weight` import gotcha in AGENTS.md). A `key=value` preferences blob is the
 * smallest stable fit for this shape.
 */
object WatchlistLocalStore {
    private const val PREFS_NAME = "stockquote_watchlist"
    private const val KEY_CODES = "watchlist_codes"
    private const val ENTRY_SEPARATOR = ","
    private const val FIELD_SEPARATOR = ":"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Null means nothing has been saved yet (first run) — caller should keep its own default seed.
     * 每一项是 (code, name)；name 可能是空串（旧格式没存名字，或者极端情况下名字本身就是空）。
     */
    fun loadWatchlistEntries(): List<Pair<String, String>>? =
        prefs.getString(KEY_CODES, null)
            ?.split(ENTRY_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.map { entry ->
                // limit = 2：只在第一个分隔符处切一刀，就算名字本身含 FIELD_SEPARATOR 也不会被切碎。
                val parts = entry.split(FIELD_SEPARATOR, limit = 2)
                val code = parts[0]
                val name = parts.getOrElse(1) { "" }
                code to name
            }

    fun save(entriesInOrder: List<Pair<String, String>>) {
        prefs.edit()
            .putString(
                KEY_CODES,
                entriesInOrder.joinToString(ENTRY_SEPARATOR) { (code, name) -> "$code$FIELD_SEPARATOR$name" },
            )
            .apply()
    }
}
