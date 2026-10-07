package io.github.kunkai2002.splashclean.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SubscriptionSource(
    val id: Long,
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    val version: Int = 0,
    val updatedAt: Long = 0,
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val lastError: String? = null,
)

@Serializable
data class Settings(
    val enabled: Boolean = true,
    val ocrEnabled: Boolean = true,
    val showToast: Boolean = true,
    val keepAliveNotification: Boolean = true,
    /** Packages where the service does nothing at all (user choice, on top of the built-in list). */
    val disabledApps: Set<String> = emptySet(),
    /** "subsId|categoryName" -> enabled. */
    val categoryOverrides: Map<String, Boolean> = emptyMap(),
    /** "subsId|appId|groupKey" or "subsId|*|groupKey" (global) -> enabled. */
    val groupOverrides: Map<String, Boolean> = emptyMap(),
    /** Global rule groups switched off for one app: "subsId|groupKey|appId". */
    val globalExcludes: Set<String> = emptySet(),
    /** Apps where the screenshot (OCR) fallback must not run. */
    val ocrDisabledApps: Set<String> = emptySet(),
    /** "subsId|groupKey|appId" (or "ocr||appId") the user said are fine: never ask "mistake?" again. */
    val noAskRules: Set<String> = emptySet(),
    val autoCheckUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val subscriptions: List<SubscriptionSource> = emptyList(),
    val autoUpdateSubscriptions: Boolean = true,
    val dnsBlockEnabled: Boolean = false,
    val dnsBlocklistUrl: String = "",
    val dnsUpstream: String = "",
    val dnsAllowlist: Set<String> = emptySet(),
    val shakeBlockApps: Set<String> = emptySet(),
    /** Apps whose sensors were last switched off successfully (to undo removed ones). */
    val shakeAppliedApps: Set<String> = emptySet(),
    val shakeLastApply: Long = 0,
    val shakeLastError: String? = null,
    val onboardingDone: Boolean = false,
    /** Android 12 and older only ("" = follow the system); Android 13+ uses the system per-app language. */
    val language: String = "",
    val guardAccessibility: Boolean = true,
)

object Prefs {
    private const val FILE = "settings"
    private const val KEY = "settings_json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var sp: SharedPreferences
    private val _flow = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> get() = _flow
    val value: Settings get() = _flow.value

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _flow.value = sp.getString(KEY, null)?.let {
            runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull()
        } ?: Settings()
    }

    @Synchronized
    fun update(block: (Settings) -> Settings) {
        val next = block(_flow.value)
        if (next == _flow.value) return
        _flow.value = next
        sp.edit().putString(KEY, json.encodeToString(Settings.serializer(), next)).apply()
    }

    fun categoryKey(subsId: Long, name: String) = "$subsId|$name"
    fun groupKey(subsId: Long, appId: String?, groupKey: Int) = "$subsId|${appId ?: "*"}|$groupKey"
}
