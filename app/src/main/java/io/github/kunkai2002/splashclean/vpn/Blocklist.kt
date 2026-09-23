package io.github.kunkai2002.splashclean.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Domain blocklist. Accepts adblock (`||example.com^`), hosts (`0.0.0.0 example.com`) and
 * plain-domain lines; `*` wildcards become patterns. A domain is blocked when it or any parent
 * domain is listed. Default list: AWAvenue Ads Rule (GPL-3.0, TG-Twilight/AWAvenue-Ads-Rule),
 * which targets the ad SDKs used for splash ads in Chinese apps.
 */
class Blocklist(private val domains: Set<String>, private val patterns: List<Regex>) {
    val size: Int get() = domains.size + patterns.size

    fun isBlocked(name: String, allow: Set<String>): Boolean {
        val d = name.trimEnd('.')
        if (d.isEmpty()) return false
        if (matchesSuffix(d, allow)) return false
        if (matchesSuffix(d, domains)) return true
        return patterns.any { it.matches(d) }
    }

    private fun matchesSuffix(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var d = name
        while (true) {
            if (d in set) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }

    companion object {
        const val ASSET = "dns/awavenue-adblock.txt"
        val DEFAULT_URLS = listOf(
            "https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/Filters/AWAvenue-Ads-Rule-Adblock.txt",
            "https://cdn.jsdelivr.net/gh/TG-Twilight/AWAvenue-Ads-Rule@main/Filters/AWAvenue-Ads-Rule-Adblock.txt",
        )

        fun parse(text: String): Blocklist {
            val domains = HashSet<String>()
            val patterns = ArrayList<Regex>()
            text.lineSequence().forEach { raw ->
                var l = raw.trim()
                if (l.isEmpty() || l.startsWith("!") || l.startsWith("#") || l.startsWith("[") || l.startsWith("@@")) return@forEach
                l = l.substringBefore('$').substringBefore(" #")
                when {
                    l.startsWith("||") -> l = l.removePrefix("||").removeSuffix("^").removeSuffix("|")
                    l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ") || l.startsWith(":: ") -> l = l.substringAfter(' ').trim()
                }
                l = l.lowercase().trim().trimEnd('.')
                if (l.isEmpty() || l.contains('/') || l.contains(' ')) return@forEach
                if (l.contains('*')) {
                    val re = l.trim('*').split('*').joinToString(".*") { Regex.escape(it) }
                    runCatching { patterns.add(Regex(".*$re.*")) }
                } else if (l.contains('.') && l != "localhost") {
                    domains.add(l)
                }
            }
            return Blocklist(domains, patterns)
        }

        private fun cacheFile(context: Context) = File(context.filesDir, "dns_blocklist.txt")

        fun load(context: Context): Blocklist {
            val f = cacheFile(context)
            val text = if (f.exists()) f.readText() else context.assets.open(ASSET).bufferedReader().use { it.readText() }
            return parse(text)
        }

        fun updatedAt(context: Context): Long = cacheFile(context).takeIf { it.exists() }?.lastModified() ?: 0L

        /** Downloads a newer list (custom URL first, then the default mirrors). */
        suspend fun update(context: Context, customUrl: String?): Result<Int> = withContext(Dispatchers.IO) {
            var last: Throwable? = null
            val urls = listOfNotNull(customUrl?.takeIf { it.startsWith("http") }) + DEFAULT_URLS
            for (u in urls) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    val text = try {
                        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        conn.disconnect()
                    }
                    val list = parse(text)
                    if (list.size < 50) error("list too small (${list.size})")
                    cacheFile(context).writeText(text)
                    return@withContext Result.success(list.size)
                } catch (t: Throwable) {
                    last = t
                }
            }
            Result.failure(last ?: IllegalStateException("no url"))
        }
    }
}
