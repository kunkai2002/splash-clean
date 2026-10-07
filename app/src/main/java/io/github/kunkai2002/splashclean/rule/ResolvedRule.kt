// Adapted from gkd-kit/gkd `ResolvedRule.kt`, `AppRule.kt`, `GlobalRule.kt` (GPL-3.0).
// Changes: no coroutine jobs / Room / user exclusion data; timers are handled by RuleEngine.
package io.github.kunkai2002.splashclean.rule

import li.gkd.selector.MatchOptions
import li.gkd.selector.Selector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Engine-wide timestamps shared by all rules (mirrors GKD's top-level vars). */
object EngineClock {
    @Volatile
    var appChangeTime = 0L

    @Volatile
    var lastTriggerTime = 0L

    @Volatile
    var lastTriggerRule: ResolvedRule? = null
}

data class InstalledApp(
    val id: String,
    val versionCode: Int,
    val versionName: String?,
    val isSystem: Boolean,
)

sealed class ResolvedGroup(
    val group: RawSubscription.RawGroupProps,
    val subscription: RawSubscription,
)

class ResolvedAppGroup(
    group: RawSubscription.RawAppGroup,
    subscription: RawSubscription,
    val app: RawSubscription.RawApp,
) : ResolvedGroup(group, subscription)

class ResolvedGlobalGroup(
    group: RawSubscription.RawGlobalGroup,
    subscription: RawSubscription,
    /** Apps where this global group must not run (disableIfAppGroupMatch across subscriptions). */
    val groupExcludeAppIds: Set<String>,
) : ResolvedGroup(group, subscription)

sealed class ResetMatchType(val value: String) {
    data object Activity : ResetMatchType("activity")
    data object Match : ResetMatchType("match")
    data object App : ResetMatchType("app")
}

enum class RuleStatus(val ok: Boolean, val alive: Boolean) {
    StatusOk(true, true),
    MaxReached(false, false),
    NeedPreRule(false, false),
    MatchDelay(false, true),
    MatchTimeout(false, false),
    Cooldown(false, true),
    ActionDelay(false, true),
}

sealed class ResolvedRule(
    val rule: RawSubscription.RawRuleProps,
    val g: ResolvedGroup,
) {
    private val group = g.group
    val subscription = g.subscription
    val key = rule.key
    val index = group.rules.indexOfFirst { r -> r === rule }
    private val preKeys = (rule.preKeys ?: emptyList()).toSet()

    private fun compile(s: String): Selector = group.cacheMap[s] ?: Selector.compile(s).value
    val matches = (rule.matches ?: emptyList()).map(::compile)
    val anyMatches = (rule.anyMatches ?: emptyList()).map(::compile)
    val excludeMatches = (rule.excludeMatches ?: emptyList()).map(::compile)
    val excludeAllMatches = (rule.excludeAllMatches ?: emptyList()).map(::compile)

    private val resetMatch = rule.resetMatch ?: group.resetMatch
    val matchDelay = rule.matchDelay ?: group.matchDelay ?: 0L
    val actionDelay = rule.actionDelay ?: group.actionDelay ?: 0L
    private val matchTime = rule.matchTime ?: group.matchTime
    private val forcedTime = rule.forcedTime ?: group.forcedTime ?: 0L
    val matchOptions = MatchOptions(fastQuery = rule.fastQuery ?: group.fastQuery ?: false)
    val matchRoot = rule.matchRoot ?: group.matchRoot ?: false
    val order = rule.order ?: group.order ?: 0

    private val actionCdKey = rule.actionCdKey ?: group.actionCdKey
    private val actionCd = rule.actionCd ?: if (actionCdKey != null) {
        group.rules.find { r -> r.key == actionCdKey }?.actionCd
    } else {
        null
    } ?: group.actionCd ?: 1000L

    private val actionMaximumKey = rule.actionMaximumKey ?: group.actionMaximumKey
    private val actionMaximum = rule.actionMaximum ?: if (actionMaximumKey != null) {
        group.rules.find { r -> r.key == actionMaximumKey }?.actionMaximum
    } else {
        null
    } ?: group.actionMaximum

    val priorityTime = rule.priorityTime ?: group.priorityTime ?: 0
    val priorityActionMaximum = rule.priorityActionMaximum ?: group.priorityActionMaximum ?: 1
    val priorityEnabled: Boolean get() = priorityTime > 0

    /** Name of the rule group, e.g. "开屏广告-全局"; used for logs and category stats. */
    val groupName: String get() = group.name
    val groupKey: Int get() = group.key
    val subsId: Long get() = subscription.id
    val isGlobal: Boolean get() = this is GlobalRule

    fun isPriority(): Boolean {
        if (!priorityEnabled) return false
        if (priorityActionMaximum <= actionCount.get()) return false
        if (!status.ok) return false
        val t = System.currentTimeMillis()
        return t - matchChangedTime.get() < priorityTime + matchDelay
    }

    fun bindGroupRules(groupToRules: Map<out RawSubscription.RawGroupProps, List<ResolvedRule>>) {
        val selfGroupRules = groupToRules[group] ?: emptyList()
        val othersGroupRules = (group.scopeKeys ?: emptyList()).distinct()
            .filter { k -> k != group.key }
            .flatMap { k -> groupToRules.entries.find { e -> e.key.key == k }?.value ?: emptyList() }
        val groupRules = selfGroupRules + othersGroupRules
        if (actionMaximumKey != null) {
            groupRules.find { r -> r.key == actionMaximumKey }?.let { actionCount = it.actionCount }
        }
        if (actionCdKey != null) {
            groupRules.find { r -> r.key == actionCdKey }?.let { actionTriggerTime = it.actionTriggerTime }
        }
        preRules = groupRules.filter { o -> o.key != null && preKeys.contains(o.key) }.toSet()
    }

    private var preRules = emptySet<ResolvedRule>()

    private val actionDelayTriggerTime = AtomicLong(0L)

    @Volatile
    var actionDelayPending = false

    @Volatile
    var matchDelayPending = false

    fun checkDelay(): Boolean {
        if (actionDelay > 0 && actionDelayTriggerTime.get() == 0L) {
            actionDelayTriggerTime.set(System.currentTimeMillis())
            return true
        }
        return false
    }

    fun checkForced(): Boolean {
        if (forcedTime <= 0) return false
        return System.currentTimeMillis() < matchChangedTime.get() + matchDelay + forcedTime
    }

    private var actionTriggerTime = AtomicLong(0L)
    private var actionCount = AtomicInteger(0)
    private val matchChangedTime = AtomicLong(0L)

    fun trigger() {
        val t = System.currentTimeMillis()
        actionTriggerTime.set(t)
        actionDelayTriggerTime.set(0L)
        actionCount.incrementAndGet()
        EngineClock.lastTriggerTime = t
        EngineClock.lastTriggerRule = this
    }

    val isFirstMatchApp: Boolean get() = matchChangedTime.get() < EngineClock.appChangeTime

    private val matchLimitTime = (matchTime ?: 0) + matchDelay

    val resetMatchType: ResetMatchType = when (resetMatch) {
        ResetMatchType.App.value -> ResetMatchType.App
        ResetMatchType.Match.value -> ResetMatchType.Match
        else -> ResetMatchType.Activity
    }

    fun resetState(t: Long) {
        actionCount.set(0)
        actionDelayTriggerTime.set(0L)
        actionTriggerTime.set(0)
        actionDelayPending = false
        matchDelayPending = false
        matchChangedTime.set(t)
    }

    val status: RuleStatus
        get() {
            if (actionMaximum != null && actionCount.get() >= actionMaximum) return RuleStatus.MaxReached
            if (preRules.isNotEmpty() && !preRules.any { it === EngineClock.lastTriggerRule }) {
                return RuleStatus.NeedPreRule
            }
            val t = System.currentTimeMillis()
            val c = matchChangedTime.get()
            if (matchDelay > 0 && t - c < matchDelay) return RuleStatus.MatchDelay
            if (matchTime != null && t - c > matchLimitTime) return RuleStatus.MatchTimeout
            if (actionTriggerTime.get() + actionCd > t) return RuleStatus.Cooldown
            val d = actionDelayTriggerTime.get()
            if (d > 0 && d + actionDelay > t) return RuleStatus.ActionDelay
            return RuleStatus.StatusOk
        }

    abstract fun matchActivity(appId: String, activityId: String? = null): Boolean
}

fun getFixActivityIds(appId: String, activityIds: List<String>?): List<String> {
    if (activityIds.isNullOrEmpty()) return emptyList()
    return activityIds.map { if (it.startsWith('.')) appId + it else it }
}

class AppRule(
    rule: RawSubscription.RawAppRule,
    g: ResolvedAppGroup,
    appInfo: InstalledApp?,
) : ResolvedRule(rule, g) {
    val app = g.app
    val enable = appInfo?.let {
        if (rule.versionCode?.match(it.versionCode) == false) return@let false
        if (rule.versionName?.match(it.versionName) == false) return@let false
        null
    } ?: true
    val appId = app.id
    private val appGroup = g.group as RawSubscription.RawAppGroup
    private val activityIds = getFixActivityIds(app.id, rule.activityIds ?: appGroup.activityIds)
    private val excludeActivityIds =
        getFixActivityIds(app.id, rule.excludeActivityIds ?: appGroup.excludeActivityIds)

    override fun matchActivity(appId: String, activityId: String?): Boolean {
        if (!enable) return false
        if (appId != app.id) return false
        activityId ?: return true
        if (excludeActivityIds.any { activityId.startsWith(it) }) return false
        return activityIds.isEmpty() || activityIds.any { activityId.startsWith(it) }
    }
}

class GlobalRule(
    rule: RawSubscription.RawGlobalRule,
    g: ResolvedGlobalGroup,
    installed: Map<String, InstalledApp>,
    private val launcherAppId: () -> String,
) : ResolvedRule(rule, g) {
    private class GlobalApp(
        val enable: Boolean,
        val activityIds: List<String>,
        val excludeActivityIds: List<String>,
    )

    private val globalGroup = g.group as RawSubscription.RawGlobalGroup
    private val groupExcludeAppIds = g.groupExcludeAppIds
    private val matchAnyApp = rule.matchAnyApp ?: globalGroup.matchAnyApp ?: true
    private val matchLauncher = rule.matchLauncher ?: globalGroup.matchLauncher ?: false
    private val matchSystemApp = rule.matchSystemApp ?: globalGroup.matchSystemApp ?: false
    private val systemApps: Set<String> = installed.values.filter { it.isSystem }.map { it.id }.toHashSet()

    private val apps: Map<String, GlobalApp> = buildMap {
        (rule.apps ?: globalGroup.apps ?: emptyList()).filter { a ->
            installed.isEmpty() || installed.containsKey(a.id)
        }.forEach { a ->
            val enable = a.enable ?: installed[a.id]?.let { info ->
                if (a.versionCode?.match(info.versionCode) == false) return@let false
                if (a.versionName?.match(info.versionName) == false) return@let false
                null
            } ?: true
            put(
                a.id, GlobalApp(
                    enable = enable,
                    activityIds = getFixActivityIds(a.id, a.activityIds),
                    excludeActivityIds = getFixActivityIds(a.id, a.excludeActivityIds),
                )
            )
        }
    }
    private val excludeAppIds = apps.filter { !it.value.enable }.keys
    private val enableApps = apps.filter { it.value.enable }

    override fun matchActivity(appId: String, activityId: String?): Boolean {
        if (excludeAppIds.contains(appId) || groupExcludeAppIds.contains(appId)) return false
        val app = enableApps[appId]
        if (app != null) {
            activityId ?: return true
            if (app.excludeActivityIds.any { activityId.startsWith(it) }) return false
            return app.activityIds.isEmpty() || app.activityIds.any { activityId.startsWith(it) }
        }
        if (!matchLauncher && appId == launcherAppId()) return false
        if (!matchSystemApp && systemApps.contains(appId)) return false
        return matchAnyApp
    }
}
