package io.github.kunkai2002.splashclean.service

import io.github.kunkai2002.splashclean.data.l10n
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.kunkai2002.splashclean.MainActivity
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.capture.CaptureTrigger
import io.github.kunkai2002.splashclean.data.ActionLog
import io.github.kunkai2002.splashclean.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_EVENTS = "events"
    const val ID_STATUS = 1
    const val ID_CAPTURE = 2
    const val ID_PAIRING = 3
    const val ID_WARN = 4

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.l10n().getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.l10n().getString(R.string.channel_events), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Foreground service: shows the status notification, hosts the "capture this screen" action, runs the guard. */
class KeepAliveService : Service() {
    companion object {
        fun sync(context: Context) {
            val want = Prefs.value.enabled && Prefs.value.keepAliveNotification &&
                CleanAccessibilityService.instance != null
            val intent = Intent(context, KeepAliveService::class.java)
            if (want) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var guardJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startInForeground(buildNotification(ActionLog.todayCount()))
        scope.launch {
            combine(ActionLog.counters, CleanAccessibilityService.running) { c, r -> c to r }
                .debounce(1500)
                .collect { (_, running) ->
                    if (!running) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(Notifications.ID_STATUS, buildNotification(ActionLog.todayCount()))
                    }
                }
        }
        guardJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(10 * 60_000L)
                A11yGuard.ensureEnabled(this@KeepAliveService, "timer")
            }
        }
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_STATUS, n, type) }
            .onFailure { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-post so a language change shows up right away.
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_STATUS, buildNotification(ActionLog.todayCount()))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(today: Int): Notification {
        val capture = PendingIntent.getBroadcast(
            this, 1, Intent(this, CaptureTrigger::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(l10n().getString(R.string.status_running))
            .setContentText(l10n().getString(R.string.status_today, today))
            .setOngoing(true)
            .setShowWhen(false)
            // Own group so Android does not fold it (and its capture button) under other notifications.
            .setGroup("status")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, l10n().getString(R.string.action_capture), capture)
            .build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        A11yGuard.ensureEnabled(context, intent.action ?: "boot")
        GuardJob.schedule(context)
        if (Prefs.value.dnsBlockEnabled && android.net.VpnService.prepare(context) == null) {
            io.github.kunkai2002.splashclean.vpn.DnsVpnService.start(context)
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            io.github.kunkai2002.splashclean.adb.ShakeBlocker.scheduleAfterBoot(context)
        }
    }
}

/** Periodic safety net (every ~15 min) that re-enables the service after an OEM cleaner turned it off. */
class GuardJob : JobService() {
    companion object {
        private const val ID = 1001
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            val info = JobInfo.Builder(ID, ComponentName(context, GuardJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        A11yGuard.ensureEnabled(this, "job")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
