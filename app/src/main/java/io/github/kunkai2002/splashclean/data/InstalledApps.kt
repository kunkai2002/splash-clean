package io.github.kunkai2002.splashclean.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import io.github.kunkai2002.splashclean.rule.InstalledApp

object InstalledApps {
    @Volatile
    var apps: Map<String, InstalledApp> = emptyMap()
        private set

    @Volatile
    var launcherAppId: String = ""
        private set

    @Volatile
    var imeAppId: String = ""
        private set

    private lateinit var pm: PackageManager

    /** Packages the service never touches, whatever the rules say. */
    val builtinBlocked: Set<String> = setOf(
        "com.tencent.mm", // WeChat: scrambles accessibility data and has banned accounts using click tools (2025-2026)
        "com.android.systemui",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.phone",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
    )

    fun refresh(context: Context) {
        pm = context.packageManager
        val list: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())
        apps = list.associate { p ->
            val ai = p.applicationInfo
            val flags = ai?.flags ?: 0
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
            p.packageName to InstalledApp(p.packageName, code, p.versionName, isSystem)
        }
        launcherAppId = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(pm)?.packageName ?: ""
        imeAppId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)?.packageName ?: ""
    }

    /** True when the package list looks complete (some ROMs hide it behind a runtime permission). */
    val listLooksComplete: Boolean get() = apps.size > 30

    fun isBlocked(appId: String, settings: io.github.kunkai2002.splashclean.data.Settings): Boolean =
        appId in builtinBlocked || appId == imeAppId || appId in settings.disabledApps

    fun label(context: Context, appId: String): String = labelCache.get(appId) ?: runCatching {
        val ai = context.packageManager.getApplicationInfo(appId, 0)
        context.packageManager.getApplicationLabel(ai).toString()
    }.getOrDefault(appId).also { labelCache.put(appId, it) }

    private val labelCache = LruCache<String, String>(256)

    private val activityCache = object : LruCache<String, Boolean>(512) {
        override fun create(key: String): Boolean {
            val i = key.indexOf('/')
            return try {
                pm.getActivityInfo(ComponentName(key.substring(0, i), key.substring(i + 1)), 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun isActivity(appId: String, className: String): Boolean {
        if (!::pm.isInitialized) return false
        return activityCache.get("$appId/$className")
    }
}
