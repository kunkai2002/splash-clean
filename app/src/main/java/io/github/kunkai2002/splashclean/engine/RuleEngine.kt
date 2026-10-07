// Event handling and rule matching loop.
// Modeled on gkd-kit/gkd `A11yRuleEngine.kt` and `A11yState.kt` (GPL-3.0): same top-activity tracking,
// reset semantics (activity / app / match), cooldowns, delays, forced polling and priority ordering.
package io.github.kunkai2002.splashclean.engine

import android.util.Log
import android.view.Display
import io.github.kunkai2002.splashclean.BuildConfig
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.rule.EngineClock
import io.github.kunkai2002.splashclean.rule.ResetMatchType
import io.github.kunkai2002.splashclean.rule.ResolvedRule
import io.github.kunkai2002.splashclean.rule.RuleStatus
import io.github.kunkai2002.splashclean.rule.RuleSummary
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val STATE_CHANGED = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
private const val CONTENT_CHANGED = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

class ActiveRules(
    val appId: String = "",
    val activityId: String? = null,
    val summary: RuleSummary = RuleSummary(),
    blocked: Boolean = false,
) {
    val appRules = summary.appIdToRules[appId] ?: emptyList()
    val rules: List<ResolvedRule> = if (blocked || appId.isEmpty()) emptyList() else
        (appRules.filter { it.matchActivity(appId, activityId) } +
            summary.globalRules.filter { it.matchActivity(appId, activityId) }).sortedBy { it.order }
    private val hasPriorityRule = rules.size > 1 && rules.any { it.priorityEnabled }
    val activePriority: Boolean get() = hasPriorityRule && rules.any { it.isPriority() }
    val priorityRules: List<ResolvedRule>
        get() = if (hasPriorityRule) rules.sortedBy { if (it.isPriority()) 0 else 1 } else rules
    val skipMatch: Boolean get() = rules.all { !it.status.ok }
    val skipConsumeEvent: Boolean get() = rules.all { !it.status.alive }
    val hasFeatureAction: Boolean
        get() = rules.any { it.checkForced() && (it.status == RuleStatus.StatusOk || it.status == RuleStatus.Cooldown) }
}

interface EngineHost {
    val selfPackage: String
    fun activeRoot(): AccessibilityNodeInfo?
    fun isInteractive(): Boolean
    fun performRuleAction(rule: ResolvedRule, node: AccessibilityNodeInfo): ActionResult
    fun onRuleActed(rule: ResolvedRule, result: ActionResult, appId: String, activityId: String?)
    fun onAppEnter(appId: String, activityId: String?, time: Long)
}

class RuleEngine(private val host: EngineHost) {
    private val eventExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sc-event") }
    private val queryExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sc-query") }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sc-timer") }

    @Volatile
    var current = ActiveRules()
        private set

    @Volatile
    private var summary = RuleSummary()

    private val lock = Any()
    private var lastValidActivity: Pair<String, String>? = null
    private var lastActivityUpdateTime = 0L
    private var lastContentEventTime = 0L
    private var lastEventTime = 0L

    @Volatile
    private var querying = false

    val topAppId: String get() = current.appId
    val topActivityId: String? get() = current.activityId

    fun shutdown() {
        eventExecutor.shutdownNow()
        queryExecutor.shutdownNow()
        scheduler.shutdownNow()
    }

    fun onSummaryChanged(newSummary: RuleSummary) {
        eventExecutor.execute {
            summary = newSummary
            synchronized(lock) {
                val top = current
                rebuildActive(top.appId, top.activityId, force = true)
            }
            startQuery()
        }
    }

    /** Screen unlocked: many apps show a "hot start" splash ad when they come back. */
    fun onUserPresent() {
        eventExecutor.execute {
            synchronized(lock) {
                val top = current
                if (top.appId.isNotEmpty()) enterApp(top.appId, top.activityId, System.currentTimeMillis())
            }
            startQuery()
        }
    }

    fun onEvent(event: AccessibilityEvent) {
        if (!Prefs.value.enabled) return
        val type = event.eventType
        if (type != STATE_CHANGED && type != CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33 && event.displayId != Display.DEFAULT_DISPLAY) return
        val now = System.currentTimeMillis()
        if (type == CONTENT_CHANGED) {
            if (!host.isInteractive()) return
            if (pkg == "com.android.systemui" && pkg != current.appId) return
            if (pkg == host.selfPackage) return
            if (pkg == InstalledApps.imeAppId && current.appId != pkg) return
            if (now - lastContentEventTime < 100 && now - EngineClock.appChangeTime > 5000 &&
                now - EngineClock.lastTriggerTime > 3000
            ) return
            lastContentEventTime = now
        }
        if (event.eventTime < lastEventTime) return
        lastEventTime = event.eventTime
        val cls = event.className?.toString()
        eventExecutor.execute { consume(type, pkg, cls) }
    }

    private fun consume(type: Int, evAppId: String, evClass: String?) {
        val oldAppId = current.appId
        val rightAppId = if (oldAppId == evAppId) evAppId else (activeWindowAppId() ?: return)
        if (rightAppId == evAppId && type == STATE_CHANGED && evClass != null) {
            if (InstalledApps.isActivity(evAppId, evClass)) updateTop(evAppId, evClass)
        }
        if (rightAppId != current.appId) updateTop(rightAppId, null)
        if (evAppId != rightAppId) return
        if (current.skipConsumeEvent) return
        startQuery()
    }

    private fun activeWindowAppId(): String? = try {
        host.activeRoot()?.packageName?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun updateTop(appId: String, activityId: String?) = synchronized(lock) {
        val t = System.currentTimeMillis()
        val old = current
        val idChanged = appId != old.appId
        val isSame = !idChanged && old.activityId == activityId
        if (isSame && t - lastActivityUpdateTime < 1000) return@synchronized
        lastActivityUpdateTime = t
        val resolvedActivity = activityId ?: lastValidActivity?.takeIf { it.first == appId }?.second
        if (activityId != null) lastValidActivity = appId to activityId
        if (idChanged) {
            enterApp(appId, resolvedActivity, t)
        } else if (old.activityId != resolvedActivity) {
            val next = rebuildActive(appId, resolvedActivity, force = true)
            next.rules.forEach { r ->
                when (r.resetMatchType) {
                    ResetMatchType.App -> if (r.isFirstMatchApp) r.resetState(t)
                    ResetMatchType.Activity -> r.resetState(t)
                    ResetMatchType.Match -> if (!old.rules.contains(r)) r.resetState(t)
                }
            }
        }
    }

    /** Called with [lock] held. */
    private fun enterApp(appId: String, activityId: String?, t: Long) {
        EngineClock.appChangeTime = t
        summary.globalRules.forEach { it.resetState(t) }
        summary.appIdToRules[current.appId]?.forEach { it.resetState(t) }
        summary.appIdToRules[appId]?.forEach { it.resetState(t) }
        rebuildActive(appId, activityId, force = true)
        if (!InstalledApps.isBlocked(appId, Prefs.value) && appId != host.selfPackage &&
            appId != InstalledApps.launcherAppId
        ) {
            host.onAppEnter(appId, activityId, t)
        }
    }

    /** Called with [lock] held. */
    private fun rebuildActive(appId: String, activityId: String?, force: Boolean): ActiveRules {
        val old = current
        if (!force && old.appId == appId && old.activityId == activityId && old.summary === summary) return old
        val blocked = appId.isEmpty() || appId == host.selfPackage || InstalledApps.isBlocked(appId, Prefs.value)
        val next = ActiveRules(appId, activityId, summary, blocked)
        if (old.summary !== summary) {
            val t = System.currentTimeMillis()
            next.rules.forEach { r ->
                when (r.resetMatchType) {
                    ResetMatchType.App -> if (r.isFirstMatchApp) r.resetState(t)
                    else -> r.resetState(t)
                }
            }
        }
        current = next
        return next
    }

    private fun fixAppId(rightAppId: String) {
        if (current.appId == rightAppId) return
        updateTop(rightAppId, null)
        schedule(300) { startQuery() }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        if (scheduler.isShutdown) return
        runCatching { scheduler.schedule({ runCatching(block) }, delayMs, TimeUnit.MILLISECONDS) }
    }

    fun startQuery(byForced: Boolean = false, delayRule: ResolvedRule? = null) {
        if (!Prefs.value.enabled) return
        if (current.rules.isEmpty()) return
        synchronized(this) {
            if (querying) return
            querying = true
        }
        queryExecutor.execute {
            val st = System.currentTimeMillis()
            try {
                queryAction(byForced, delayRule)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "RuleEngine",
                        "query ${System.currentTimeMillis() - st}ms at +${st - EngineClock.appChangeTime}ms rules=${current.rules.size} app=${current.appId}",
                    )
                }
            } catch (t: Throwable) {
                Log.w("RuleEngine", "query failed", t)
            } finally {
                querying = false
                checkFutureStartJob()
            }
        }
    }

    private fun checkFutureStartJob() {
        val t = System.currentTimeMillis()
        if (t - EngineClock.lastTriggerTime < 3000L || t - EngineClock.appChangeTime < 3000L) {
            schedule(300) { startQuery() }
        } else if (current.hasFeatureAction) {
            schedule(300) { startQuery(byForced = true) }
        }
    }

    private fun queryAction(byForced: Boolean, delayRule: ResolvedRule?) {
        val active = current
        active.rules.forEach { rule ->
            if (rule.status == RuleStatus.MatchDelay && !rule.matchDelayPending) {
                rule.matchDelayPending = true
                schedule(rule.matchDelay) {
                    rule.matchDelayPending = false
                    startQuery(delayRule = rule)
                }
            }
        }
        if (active.skipMatch) return
        val ctx = QueryContext { host.activeRoot() }
        for (rule in active.priorityRules) {
            if (active !== current) break
            if (delayRule != null && delayRule !== rule) continue
            if (rule.status != RuleStatus.StatusOk) continue
            if (byForced && !rule.checkForced()) continue
            if (RepeatGuard.isPaused(RepeatGuard.key(rule.subsId, rule.groupKey, active.appId))) continue
            val root = ctx.root() ?: break
            val rightAppId = root.packageName?.toString() ?: break
            if (active.appId != rightAppId) {
                eventExecutor.execute { fixAppId(rightAppId) }
                return
            }
            if (!rule.matchActivity(rightAppId)) continue
            val target = ctx.queryRule(rule, root) ?: continue
            if (rule.checkDelay() && !rule.actionDelayPending) {
                rule.actionDelayPending = true
                schedule(rule.actionDelay) {
                    rule.actionDelayPending = false
                    startQuery(delayRule = rule)
                }
                continue
            }
            if (rule.status != RuleStatus.StatusOk) break
            if (active !== current) break
            val result = host.performRuleAction(rule, target)
            if (result.result) {
                rule.trigger()
                schedule(300) { startQuery() }
                host.onRuleActed(rule, result, active.appId, active.activityId)
            }
        }
    }
}
