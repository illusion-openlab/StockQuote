package tech.illusion.stockquote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin `SharedPreferences`-backed persistence for watchlist membership/order state.
 *
 * Deliberately not Room: the persisted shape is one small flat list (ordered codes), not
 * relational data, and pulling in Room would mean a new Gradle/KSP surface on top of a classpath
 * that's already fragile (see the `Modifier.weight` import gotcha in AGENTS.md). A `key=value`
 * preferences blob is the smallest stable fit for this shape — same call SpacePlayer made for its
 * own simple preference-like state.
 */
object WatchlistLocalStore {
    private const val PREFS_NAME = "stockquote_watchlist"
    private const val KEY_CODES = "watchlist_codes"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Null means nothing has been saved yet (first run) — caller should keep its own default seed. */
    fun loadCodesInOrder(): List<String>? =
        prefs.getString(KEY_CODES, null)?.split(",")?.filter { it.isNotBlank() }

    fun save(codesInOrder: List<String>) {
        prefs.edit().putString(KEY_CODES, codesInOrder.joinToString(",")).apply()
    }
}
