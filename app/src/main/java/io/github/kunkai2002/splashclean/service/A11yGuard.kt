package io.github.kunkai2002.splashclean.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.kunkai2002.splashclean.data.Prefs

/**
 * Keeps the accessibility service switched on once the app holds WRITE_SECURE_SETTINGS
 * (granted once over ADB, survives reboots). Force-stopping the app still removes the service;
 * it comes back the next time any of our components runs (boot, job, notification, app open).
 */
object A11yGuard {
    private const val TAG = "A11yGuard"

    fun component(context: Context) = ComponentName(context, CleanAccessibilityService::class.java)

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()

    fun isEnabledInSettings(context: Context): Boolean {
        val me = component(context)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    /** Returns true when the service is (now) listed as enabled. */
    fun ensureEnabled(context: Context, reason: String): Boolean {
        if (!Prefs.value.guardAccessibility || !Prefs.value.enabled) return isEnabledInSettings(context)
        if (!hasWriteSecureSettings(context)) return isEnabledInSettings(context)
        val me = component(context)
        val list = enabledList(context)
        val listed = list.any { ComponentName.unflattenFromString(it) == me }
        return try {
            val resolver = context.contentResolver
            if (!listed) {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me.flattenToString()).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "re-enabled accessibility service ($reason)")
            } else if (CleanAccessibilityService.instance == null && SystemClock.elapsedRealtime() > 60_000 && processAgeMs() > 15_000) {
                // Listed but not bound (some ROMs leave it in this state): toggle to force a rebind.
                val others = list.filter { ComponentName.unflattenFromString(it) != me }
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "rebound accessibility service ($reason)")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot write secure settings", t)
            false
        }
    }

    private val processStart = SystemClock.elapsedRealtime()
    private fun processAgeMs() = SystemClock.elapsedRealtime() - processStart
}
