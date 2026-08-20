package tech.illusion.stockquote.data

import java.net.HttpURLConnection
import java.net.URL

/** Plain `HttpURLConnection` GET — no OkHttp dependency added just for a couple of free JSON endpoints. */
internal fun httpGet(
    url: String,
    referer: String? = null,
    charset: String = "UTF-8",
    timeoutMs: Int = 6000,
): String? =
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        referer?.let { connection.setRequestProperty("Referer", it) }
        connection.inputStream.bufferedReader(charset(charset)).use { it.readText() }
    }.getOrNull()
