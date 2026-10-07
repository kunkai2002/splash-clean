package io.github.kunkai2002.splashclean.engine

import io.github.kunkai2002.splashclean.data.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Catches rules that keep firing on what is probably not an ad: when one rule group acts 3 times
 * within 10 seconds in the same app, it is paused for that app and the user is asked
 * "mistaken for an ad?". Keys are "subsId|groupKey|appId".
 */
object RepeatGuard {
    private const val WINDOW_MS = 10_000L
    private const val THRESHOLD = 3
    private const val PAUSE_MS = 60_000L

    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val pausedUntil = ConcurrentHashMap<String, Long>()

    fun key(subsId: Long, groupKey: Int, appId: String) = "$subsId|$groupKey|$appId"

    fun isPaused(key: String): Boolean = (pausedUntil[key] ?: 0L) > System.currentTimeMillis()

    /** Records one action; returns true when the user should be asked (the rule is then paused). */
    @Synchronized
    fun record(key: String, now: Long = System.currentTimeMillis()): Boolean {
        if (key in Prefs.value.noAskRules) return false
        val q = hits.getOrPut(key) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && q.first() < now - WINDOW_MS) q.removeFirst()
        if (q.size >= THRESHOLD && !isPaused(key)) {
            q.clear()
            pausedUntil[key] = now + PAUSE_MS
            return true
        }
        return false
    }

    /** User answered "not a mistake" (or let the question time out). */
    fun resume(key: String, neverAskAgain: Boolean) {
        pausedUntil.remove(key)
        if (neverAskAgain) Prefs.update { it.copy(noAskRules = it.noAskRules + key) }
    }

    @Synchronized
    fun clearForTest() {
        hits.clear()
        pausedUntil.clear()
    }
}
