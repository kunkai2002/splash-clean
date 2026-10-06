package io.github.kunkai2002.splashclean.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.kunkai2002.splashclean.R

/**
 * App language. Android 13+: the system per-app language (Settings → Apps → language) is the source of
 * truth and the in-app picker writes it. Older versions: stored in Prefs and applied by wrapping contexts.
 * Services and notifications always go through [wrap] — the system does not localize them for us.
 */
object AppLocale {
    /** Language tag ("" = follow the system) to its label. */
    val options = listOf(
        "" to R.string.lang_system,
        "zh-CN" to R.string.lang_hans,
        "zh-TW" to R.string.lang_hant,
        "en" to R.string.lang_en,
    )

    fun currentTag(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else {
            Prefs.value.language
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            Prefs.update { it.copy(language = tag) }
            activity.recreate()
        }
    }

    fun wrap(base: Context): Context {
        val tag = runCatching { currentTag(base) }.getOrDefault("")
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(cfg)
    }
}

/** Context whose strings follow the app language (use for notifications, toasts and service messages). */
fun Context.l10n(): Context = AppLocale.wrap(this)
