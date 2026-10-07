package io.github.kunkai2002.splashclean.rule

import android.content.Context
import android.util.Log
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.data.Settings
import io.github.kunkai2002.splashclean.data.SubscriptionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class RuleSummary(
    val globalRules: List<GlobalRule> = emptyList(),
    val appIdToRules: Map<String, List<AppRule>> = emptyMap(),
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val builtAt: Long = 0,
)

/** A third-party subscription the user can add with one tap. Rules are downloaded from the author, never bundled. */
data class KnownSubscription(
    val name: String,
    val author: String,
    val homepage: String,
    val urls: List<String>,
)

object RuleRepository {
    private const val TAG = "RuleRepository"
    const val BUILTIN_ID = -1L
    const val USER_ID = -2L

    /** Categories we switch on by default (the user asked for splash ads, popups and update prompts). */
    val DEFAULT_ON_CATEGORIES = setOf("开屏广告", "全屏广告", "更新提示", "青少年模式", "评价提示")

    val knownSubscriptions = listOf(
        KnownSubscription(
            name = "GKD_subscription (Lin-arm)",
            author = "Lin-arm & contributors",
            homepage = "https://github.com/Lin-arm/GKD_subscription",
            urls = listOf(
                "https://gkd667.vv.ax/gkd.json5",
                "https://gkd-subscription-667.pages.dev/gkd.json5",
                "https://cdn.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5",
                "https://raw.githubusercontent.com/Lin-arm/GKD_subscription/main/dist/gkd.json5",
            ),
        ),
        KnownSubscription(
            name = "gkd-subscription (MengNianxiaoyao)",
            author = "MengNianxiaoyao",
            homepage = "https://github.com/MengNianxiaoyao/gkd-subscription",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/MengNianxiaoyao/gkd-subscription@main/dist/gkd.json5",
                "https://raw.githubusercontent.com/MengNianxiaoyao/gkd-subscription/main/dist/gkd.json5",
            ),
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private val subscriptions = java.util.concurrent.ConcurrentHashMap<Long, RawSubscription>()

    private val _summary = MutableStateFlow(RuleSummary())
    val summary: StateFlow<RuleSummary> get() = _summary

    private val _loaded = MutableStateFlow<Map<Long, RawSubscription>>(emptyMap())
    val loaded: StateFlow<Map<Long, RawSubscription>> get() = _loaded

    private val subsDir get() = File(appContext.filesDir, "subscriptions").apply { mkdirs() }
    private val userFile get() = File(appContext.filesDir, "user_rules.json5")

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            InstalledApps.refresh(appContext)
            loadAll()
            rebuild()
            Prefs.flow.map { RuleInputs(it) }.distinctUntilChanged().collect { rebuild() }
        }
    }

    /** The parts of Settings that change which rules are active. */
    private data class RuleInputs(
        val categories: Map<String, Boolean>,
        val groups: Map<String, Boolean>,
        val subs: List<Pair<Long, Boolean>>,
        val globalExcludes: Set<String>,
    ) {
        constructor(s: Settings) : this(
            s.categoryOverrides, s.groupOverrides, s.subscriptions.map { it.id to it.enabled }, s.globalExcludes,
        )
    }

    /**
     * Apps whose own screens are often mistaken for ads by generic rules. Built-in global groups list them
     * as `enable: false` (keep `builtin_rules.json5` in sync) and the OCR fallback skips them.
     */
    val RISKY_APPS = setOf(
        "com.xunmeng.pinduoduo", // 拼多多
        "com.eg.android.AlipayGphone", // 支付宝
        "com.sankuai.meituan", // 美团
        "com.taptap", // TapTap
    )

    fun ocrAllowed(appId: String): Boolean = appId !in RISKY_APPS && appId !in Prefs.value.ocrDisabledApps

    fun globalExcludeKey(subsId: Long, groupKey: Int, appId: String) = "$subsId|$groupKey|$appId"

    private fun loadAll() {
        runCatching {
            val text = appContext.assets.open("builtin_rules.json5").bufferedReader().use { it.readText() }
            subscriptions[BUILTIN_ID] = RawSubscription.parse(text)
        }.onFailure { Log.e(TAG, "builtin rules", it) }
        if (userFile.exists()) {
            runCatching { subscriptions[USER_ID] = RawSubscription.parse(userFile.readText()) }
                .onFailure { Log.e(TAG, "user rules", it) }
        }
        Prefs.value.subscriptions.forEach { src ->
            val f = File(subsDir, "${src.id}.json5")
            if (f.exists()) {
                runCatching { subscriptions[src.id] = RawSubscription.parse(f.readText()) }
                    .onFailure { Log.e(TAG, "subscription ${src.id}", it) }
            }
        }
        _loaded.value = subscriptions.toMap()
    }

    fun refreshInstalledApps() {
        scope.launch {
            InstalledApps.refresh(appContext)
            rebuild()
        }
    }

    @Synchronized
    fun rebuild() {
        val start = System.currentTimeMillis()
        val settings = Prefs.value
        val installed = InstalledApps.apps
        val filterInstalled = InstalledApps.listLooksComplete
        val enabledIds = settings.subscriptions.filter { it.enabled }.map { it.id }.toSet()
        // Order: user rules first, then third-party subscriptions, built-in fallback last.
        val active = buildList {
            subscriptions[USER_ID]?.let { add(it) }
            settings.subscriptions.filter { it.enabled }.forEach { src -> subscriptions[src.id]?.let { add(it) } }
            subscriptions[BUILTIN_ID]?.let { add(it) }
        }.filter { it.id == USER_ID || it.id == BUILTIN_ID || it.id in enabledIds }

        fun categoryEnabled(subs: RawSubscription, groupName: String) = categoryEnabled(settings, subs, groupName)

        // Global groups provided by third-party subscriptions replace our built-in fallback with the same prefix.
        val thirdPartyGlobalPrefixes = active.filter { it.id != BUILTIN_ID }
            .flatMap { s -> s.globalGroups.map { it.name.substringBefore('-') } }.toSet()

        // App groups by name, across all active subscriptions (for disableIfAppGroupMatch).
        val appGroupNames: List<Pair<String, String>> = active.flatMap { s ->
            s.apps.flatMap { a -> a.groups.filter { it.ignoreGlobalGroupMatch != true }.map { a.id to it.name } }
        }

        val globalRules = mutableListOf<GlobalRule>()
        val appRules = HashMap<String, MutableList<AppRule>>()
        var groupCount = 0
        for (subs in active) {
            val groupToRules = mutableMapOf<RawSubscription.RawGlobalGroup, List<GlobalRule>>()
            for (group in subs.globalGroups) {
                val prefix = group.name.substringBefore('-')
                if (subs.id == BUILTIN_ID && prefix in thirdPartyGlobalPrefixes) continue
                val enabled = settings.groupOverrides[Prefs.groupKey(subs.id, null, group.key)]
                    ?: (if (prefix in DEFAULT_ON_CATEGORIES) true else null)
                    ?: group.enable ?: true
                if (!enabled || !group.valid) continue
                val excluded = (group.disableIfAppGroupMatch?.let { n ->
                    val gName = n.ifEmpty { group.name }
                    appGroupNames.filter { it.second.startsWith(gName) }.map { it.first }.toHashSet()
                } ?: emptySet()) + userExcludedApps(settings, subs.id, group.key)
                val resolved = ResolvedGlobalGroup(group, subs, excluded)
                val rules = group.rules.map { GlobalRule(it, resolved, installed) { InstalledApps.launcherAppId } }
                groupToRules[group] = rules
                globalRules.addAll(rules)
                groupCount++
            }
            groupToRules.values.flatten().forEach { it.bindGroupRules(groupToRules) }

            for (app in subs.apps) {
                if (app.groups.isEmpty()) continue
                if (filterInstalled && app.id !in installed) continue
                val appGroupToRules = mutableMapOf<RawSubscription.RawAppGroup, List<AppRule>>()
                for (group in app.groups) {
                    val enabled = settings.groupOverrides[Prefs.groupKey(subs.id, app.id, group.key)]
                        ?: categoryEnabled(subs, group.name)
                        ?: group.enable ?: true
                    if (!enabled || !group.valid) continue
                    val resolved = ResolvedAppGroup(group, subs, app)
                    val rules = group.rules.map { AppRule(it, resolved, installed[app.id]) }.filter { it.enable }
                    appGroupToRules[group] = rules
                    if (rules.isNotEmpty()) {
                        appRules.getOrPut(app.id, ::mutableListOf).addAll(rules)
                        groupCount++
                    }
                }
                appGroupToRules.values.flatten().forEach { it.bindGroupRules(appGroupToRules) }
            }
        }
        _summary.value = RuleSummary(globalRules, appRules, appRules.size, groupCount, System.currentTimeMillis())
        Log.i(TAG, "rules rebuilt: ${globalRules.size} global, ${appRules.size} apps, $groupCount groups in ${System.currentTimeMillis() - start} ms")
    }

    private fun categoryEnabled(settings: Settings, subs: RawSubscription, groupName: String): Boolean? {
        val c = subs.getCategory(groupName)
        val name = c?.name ?: groupName.substringBefore('-')
        settings.categoryOverrides[Prefs.categoryKey(subs.id, name)]?.let { return it }
        if (name in DEFAULT_ON_CATEGORIES) return true
        return c?.enable
    }

    private fun userExcludedApps(settings: Settings, subsId: Long, groupKey: Int): Set<String> {
        val prefix = "$subsId|$groupKey|"
        return settings.globalExcludes.filter { it.startsWith(prefix) }.map { it.substring(prefix.length) }.toHashSet()
    }

    // ---- per-app view ---------------------------------------------------------------------------

    enum class Lock { AUTHOR_EXCLUDED, REPLACED_BY_APP_RULE }

    /** One rule group as it applies to one app (for the per-app page). */
    data class AppGroupItem(
        val subsId: Long,
        val subsName: String,
        val groupKey: Int,
        val name: String,
        val desc: String?,
        val global: Boolean,
        val enabled: Boolean,
        /** Set when the switch cannot turn it on for this app. */
        val lock: Lock? = null,
    )

    private fun activeSubscriptions(settings: Settings): List<RawSubscription> {
        val enabledIds = settings.subscriptions.filter { it.enabled }.map { it.id }.toSet()
        return buildList {
            subscriptions[USER_ID]?.let { add(it) }
            settings.subscriptions.filter { it.id in enabledIds }.forEach { src -> subscriptions[src.id]?.let { add(it) } }
            subscriptions[BUILTIN_ID]?.let { add(it) }
        }
    }

    fun groupsForApp(appId: String): List<AppGroupItem> {
        val settings = Prefs.value
        val active = activeSubscriptions(settings)
        val thirdPartyGlobalPrefixes = active.filter { it.id != BUILTIN_ID }
            .flatMap { s -> s.globalGroups.map { it.name.substringBefore('-') } }.toSet()
        val appGroupNames = active.flatMap { s ->
            s.apps.filter { it.id == appId }.flatMap { a -> a.groups.filter { it.ignoreGlobalGroupMatch != true }.map { it.name } }
        }
        val out = mutableListOf<AppGroupItem>()
        for (subs in active) {
            subs.apps.find { it.id == appId }?.groups?.forEach { g ->
                val enabled = settings.groupOverrides[Prefs.groupKey(subs.id, appId, g.key)]
                    ?: categoryEnabled(settings, subs, g.name) ?: g.enable ?: true
                out += AppGroupItem(subs.id, subs.name, g.key, g.name, g.desc, false, enabled && g.valid)
            }
        }
        for (subs in active) {
            for (g in subs.globalGroups) {
                val prefix = g.name.substringBefore('-')
                if (subs.id == BUILTIN_ID && prefix in thirdPartyGlobalPrefixes) continue
                val groupOn = settings.groupOverrides[Prefs.groupKey(subs.id, null, g.key)]
                    ?: (if (prefix in DEFAULT_ON_CATEGORIES) true else null) ?: g.enable ?: true
                if (!groupOn || !g.valid) continue
                val authorExcluded = g.appIdEnable[appId] == false
                val replaced = g.disableIfAppGroupMatch?.let { n ->
                    val gName = n.ifEmpty { g.name }
                    appGroupNames.any { it.startsWith(gName) }
                } ?: false
                val userOff = globalExcludeKey(subs.id, g.key, appId) in settings.globalExcludes
                out += AppGroupItem(
                    subs.id, subs.name, g.key, g.name, g.desc, true,
                    enabled = !userOff && !authorExcluded && !replaced,
                    lock = when {
                        authorExcluded -> Lock.AUTHOR_EXCLUDED
                        replaced -> Lock.REPLACED_BY_APP_RULE
                        else -> null
                    },
                )
            }
        }
        return out
    }

    fun setGroupEnabledForApp(subsId: Long, groupKey: Int, global: Boolean, appId: String, enabled: Boolean) {
        Prefs.update { s ->
            if (global) {
                val k = globalExcludeKey(subsId, groupKey, appId)
                s.copy(globalExcludes = if (enabled) s.globalExcludes - k else s.globalExcludes + k)
            } else {
                s.copy(groupOverrides = s.groupOverrides + (Prefs.groupKey(subsId, appId, groupKey) to enabled))
            }
        }
    }

    // ---- subscriptions -------------------------------------------------------------------------

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SplashClean")
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Tries each mirror in order; returns the URL that worked. */
    suspend fun addSubscription(urls: List<String>): Result<SubscriptionSource> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                val text = download(url)
                val subs = RawSubscription.parse(text)
                if (subs.id == BUILTIN_ID || subs.id == USER_ID) error("reserved subscription id ${subs.id}")
                File(subsDir, "${subs.id}.json5").writeText(text)
                subscriptions[subs.id] = subs
                _loaded.value = subscriptions.toMap()
                val src = SubscriptionSource(
                    id = subs.id, url = url, name = subs.name, enabled = true, version = subs.version,
                    updatedAt = System.currentTimeMillis(), appCount = subs.apps.size, groupCount = subs.groupsSize,
                )
                Prefs.update { s -> s.copy(subscriptions = s.subscriptions.filter { it.id != subs.id } + src) }
                return@withContext Result.success(src)
            } catch (t: Throwable) {
                Log.w(TAG, "add $url failed", t)
                lastError = t
            }
        }
        Result.failure(lastError ?: IllegalStateException("no url"))
    }

    /** Downloads newer versions (uses checkUpdateUrl when the subscription provides one). */
    suspend fun updateAll(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (src in Prefs.value.subscriptions) {
            try {
                val current = subscriptions[src.id]
                val checkUrl = current?.checkUpdateUrl?.let { URI(src.url).resolve(it).toString() }
                if (!force && current != null && checkUrl != null) {
                    val remoteVersion = Regex("version\\s*[:=]\\s*\"?(\\d+)").find(download(checkUrl))
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (remoteVersion != null && remoteVersion <= current.version) {
                        Prefs.update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == src.id) it.copy(updatedAt = System.currentTimeMillis(), lastError = null) else it }) }
                        continue
                    }
                }
                val text = download(current?.updateUrl?.let { URI(src.url).resolve(it).toString() } ?: src.url)
                val subs = RawSubscription.parse(text)
                if (subs.id != src.id) error("subscription id changed")
                File(subsDir, "${subs.id}.json5").writeText(text)
                subscriptions[subs.id] = subs
                updated++
                Prefs.update { s ->
                    s.copy(subscriptions = s.subscriptions.map {
                        if (it.id == src.id) it.copy(
                            name = subs.name, version = subs.version, updatedAt = System.currentTimeMillis(),
                            appCount = subs.apps.size, groupCount = subs.groupsSize, lastError = null,
                        ) else it
                    })
                }
            } catch (t: Throwable) {
                Log.w(TAG, "update ${src.url} failed", t)
                Prefs.update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == src.id) it.copy(lastError = t.message ?: t.javaClass.simpleName) else it }) }
            }
        }
        _loaded.value = subscriptions.toMap()
        if (updated > 0) rebuild()
        updated
    }

    fun removeSubscription(id: Long) {
        subscriptions.remove(id)
        File(subsDir, "$id.json5").delete()
        _loaded.value = subscriptions.toMap()
        Prefs.update { s -> s.copy(subscriptions = s.subscriptions.filter { it.id != id }) }
    }

    fun setSubscriptionEnabled(id: Long, enabled: Boolean) {
        Prefs.update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    // ---- user ("taught") rules -----------------------------------------------------------------

    fun userRulesText(): String? = if (userFile.exists()) userFile.readText() else null

    @Synchronized
    fun saveUserRules(text: String): Result<Unit> = runCatching {
        val subs = RawSubscription.parse(text)
        require(subs.id == USER_ID) { "user rules must use id $USER_ID" }
        subs.appGroups.forEach { g -> g.errorDesc?.let { error(it) } }
        userFile.writeText(text)
        subscriptions[USER_ID] = subs
        _loaded.value = subscriptions.toMap()
        rebuild()
    }
}
