package tech.illusion.stockquote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin `SharedPreferences`-backed persistence for watchlist membership/order/star state.
 *
 * Deliberately not Room: the persisted shape is one small flat list (ordered codes + a starred
 * subset), not relational data, and pulling in Room would mean a new Gradle/KSP surface on top of
 * a classpath that's already fragile (see the `Modifier.weight` import gotcha in AGENTS.md). A
 * `key=value` preferences blob is the smallest stable fit for this shape — same call SpacePlayer
 * made for its own simple preference-like state.
 */
object WatchlistLocalStore {
    private const val PREFS_NAME = "stockquote_watchlist"
    private const val KEY_CODES = "watchlist_codes"
    private const val KEY_STARRED = "starred_codes"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Null means nothing has been saved yet (first run) — caller should keep its own default seed. */
    fun loadCodesInOrder(): List<String>? =
        prefs.getString(KEY_CODES, null)?.split(",")?.filter { it.isNotBlank() }

    fun loadStarredCodes(): Set<String> =
        prefs.getString(KEY_STARRED, null)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun save(codesInOrder: List<String>, starredCodes: Set<String>) {
        prefs.edit()
            .putString(KEY_CODES, codesInOrder.joinToString(","))
            .putString(KEY_STARRED, starredCodes.joinToString(","))
            .apply()
    }
}
