package io.github.kunkai2002.splashclean.adb

import io.github.kunkai2002.splashclean.data.l10n
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.service.A11yGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Shake to open" splash ads read the accelerometer. The ADB shell may tell the sensor service
 * to treat an app as idle (`cmd sensorservice set-uid-state <pkg> idle`): the app then receives no
 * sensor events at all until a reboot. After a reboot we re-apply it through wireless debugging,
 * which the app can switch on by itself once it holds WRITE_SECURE_SETTINGS (needs Wi-Fi).
 */
object ShakeBlocker {
    private const val TAG = "ShakeBlocker"
    private const val ADB_WIFI = "adb_wifi_enabled"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun applyAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { apply(app) }
    }

    suspend fun apply(context: Context): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val want = Prefs.value.shakeBlockApps
            val before = Prefs.value.shakeAppliedApps
            if (want.isEmpty() && before.isEmpty()) return@withContext Result.success("")
            val resolver = context.contentResolver
            val wasOn = AdbAuthorizer.isWirelessDebuggingOn(context)
            val result = runCatching {
                if (!wasOn) {
                    check(A11yGuard.hasWriteSecureSettings(context)) { context.l10n().getString(R.string.shake_need_auth) }
                    Settings.Global.putInt(resolver, ADB_WIFI, 1)
                    delay(3000)
                }
                val m = SelfAdbManager.get(context)
                if (!m.isConnected) check(m.autoConnect(context, 15_000)) { context.l10n().getString(R.string.shake_need_wifi) }
                val log = StringBuilder()
                want.forEach { log.append(AdbAuthorizer.shell(m, "cmd sensorservice set-uid-state $it idle")) }
                (before - want).forEach { log.append(AdbAuthorizer.shell(m, "cmd sensorservice reset-uid-state $it")) }
                runCatching { m.disconnect() }
                log.toString()
            }
            if (!wasOn) runCatching { Settings.Global.putInt(resolver, ADB_WIFI, 0) }
            Prefs.update {
                if (result.isSuccess) {
                    it.copy(shakeAppliedApps = want, shakeLastApply = System.currentTimeMillis(), shakeLastError = null)
                } else {
                    it.copy(shakeLastError = result.exceptionOrNull()?.message ?: "error")
                }
            }
            result.exceptionOrNull()?.let { Log.w(TAG, "apply failed", it) }
            result
        }
    }

    /** After boot: sensor overrides are gone; re-apply as soon as any network is up. */
    fun scheduleAfterBoot(context: Context) {
        if (Prefs.value.shakeBlockApps.isEmpty()) return
        Prefs.update { it.copy(shakeAppliedApps = emptySet()) }
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(1002, ComponentName(context, ShakeJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setOverrideDeadline(6 * 3600_000L)
            .build()
        runCatching { js.schedule(info) }
    }
}

class ShakeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            val r = ShakeBlocker.apply(applicationContext)
            jobFinished(params, r.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
