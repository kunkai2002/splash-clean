package io.github.kunkai2002.splashclean.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.capture.SnapshotTaker
import io.github.kunkai2002.splashclean.data.ActionEntry
import io.github.kunkai2002.splashclean.data.ActionLog
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.engine.ActionResult
import io.github.kunkai2002.splashclean.engine.Actions
import io.github.kunkai2002.splashclean.engine.EngineHost
import io.github.kunkai2002.splashclean.engine.RuleEngine
import io.github.kunkai2002.splashclean.engine.SplashFallback
import io.github.kunkai2002.splashclean.ocr.PaddleOcr
import io.github.kunkai2002.splashclean.rule.EngineClock
import io.github.kunkai2002.splashclean.rule.ResolvedRule
import io.github.kunkai2002.splashclean.rule.RuleRepository
import io.github.kunkai2002.splashclean.rule.ScreenInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CleanAccessibilityService : AccessibilityService(), EngineHost {
    companion object {
        @Volatile
        var instance: CleanAccessibilityService? = null
            private set

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> get() = _running
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private var engine: RuleEngine? = null
    private var fallback: SplashFallback? = null

    val topAppId: String get() = engine?.topAppId ?: ""
    val topActivityId: String? get() = engine?.topActivityId

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) engine?.onUserPresent()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        updateScreenInfo()
        val e = RuleEngine(this)
        engine = e
        fallback = SplashFallback(this, this, { e.topAppId }) { appId, text, since ->
            ActionLog.add(
                ActionEntry(
                    time = System.currentTimeMillis(), appId = appId, activityId = e.topActivityId,
                    source = "ocr", groupName = text, action = "tap",
                    sinceAppEnter = since,
                )
            )
            showActionToast()
        }
        scope.launch { RuleRepository.summary.collect { e.onSummaryChanged(it) } }
        // Load the OCR models now so the first splash of the day is not slowed down by it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Prefs.value.ocrEnabled) {
            scope.launch(Dispatchers.Default) { runCatching { PaddleOcr.get(this@CleanAccessibilityService).warmUp() } }
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        KeepAliveService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        engine?.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    private fun cleanup() {
        if (instance !== this) return
        instance = null
        _running.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        engine?.shutdown()
        fallback?.shutdown()
        engine = null
        fallback = null
        scope.cancel()
        KeepAliveService.sync(this)
    }

    private fun updateScreenInfo() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            ScreenInfo.width = b.width()
            ScreenInfo.height = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            ScreenInfo.width = m.widthPixels
            ScreenInfo.height = m.heightPixels
        }
    }

    fun takeSnapshot(callback: (Result<String>) -> Unit) = SnapshotTaker.take(this, callback)

    // ---- EngineHost -----------------------------------------------------------------------------

    override val selfPackage: String get() = packageName

    override fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Throwable) {
        null
    }

    override fun isInteractive(): Boolean = powerManager.isInteractive

    override fun performRuleAction(rule: ResolvedRule, node: AccessibilityNodeInfo): ActionResult =
        Actions.perform(this, rule.rule.action, node, rule.rule)

    override fun onRuleActed(rule: ResolvedRule, result: ActionResult, appId: String, activityId: String?) {
        fallback?.onRuleActed(appId)
        ActionLog.add(
            ActionEntry(
                time = System.currentTimeMillis(), appId = appId, activityId = activityId, source = "rule",
                groupName = rule.groupName, subscription = rule.subscription.name, action = result.action,
                sinceAppEnter = System.currentTimeMillis() - EngineClock.appChangeTime,
            )
        )
        if (result.action != "none") showActionToast()
    }

    override fun onAppEnter(appId: String, activityId: String?, time: Long) {
        fallback?.onAppEnter(appId, time)
    }

    private fun showActionToast() {
        if (!Prefs.value.showToast) return
        mainHandler.post { Toast.makeText(this, R.string.toast_skipped, Toast.LENGTH_SHORT).show() }
    }

    fun refreshApps() {
        InstalledApps.refresh(this)
        RuleRepository.refreshInstalledApps()
    }
}
