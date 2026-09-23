package io.github.kunkai2002.splashclean.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import io.github.kunkai2002.splashclean.data.Prefs
import io.github.kunkai2002.splashclean.ocr.OcrLine
import io.github.kunkai2002.splashclean.ocr.PaddleOcr
import io.github.kunkai2002.splashclean.rule.ScreenInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Last line of defence for splash ads whose skip button is not in the accessibility tree
 * (drawn on a canvas, video surface, Flutter/Unity...). Only runs in the first seconds after
 * entering an app, only when no rule has acted, and only when the tree has no skip text at all
 * (so rules — and their exclusions such as "跳过片头" — always win).
 */
class SplashFallback(
    private val service: AccessibilityService,
    private val host: EngineHost,
    private val topApp: () -> String,
    private val onTapped: (appId: String, text: String, sinceEnter: Long) -> Unit,
) {
    private class Session(val appId: String, val start: Long) {
        @Volatile
        var acted = false

        @Volatile
        var taps = 0
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sc-ocr") }

    @Volatile
    private var session: Session? = null

    fun shutdown() = executor.shutdownNow()

    fun onAppEnter(appId: String, time: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Prefs.value.ocrEnabled) return
        val s = Session(appId, time)
        session = s
        // Android 11 allows one screenshot per second, Android 12+ one per 333 ms.
        val delays = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            longArrayOf(600, 1700, 2800, 3900, 5000)
        } else {
            longArrayOf(500, 1000, 1600, 2300, 3200, 4300)
        }
        delays.forEach { d -> executor.schedule({ runCatching { tick(s) } }, d, TimeUnit.MILLISECONDS) }
    }

    fun onRuleActed(appId: String) {
        session?.takeIf { it.appId == appId }?.acted = true
    }

    private fun tick(s: Session) {
        if (session !== s || s.acted || s.taps >= 2) return
        if (topApp() != s.appId || !host.isInteractive()) return
        val root = host.activeRoot() ?: return
        if (root.packageName?.toString() != s.appId) return
        if (treeHasSkipText(root)) return
        // Screenshots cost battery: only look when the screen looks like an ad or a splash.
        if (!looksLikeSplashOrAd(root, activity())) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) screenshotAndTap(s)
    }

    private fun looksLikeSplashOrAd(root: AccessibilityNodeInfo, activityId: String?): Boolean {
        if (activityId != null && SPLASH_ACTIVITY.containsMatchIn(activityId.substringAfterLast('.'))) return true
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 400) {
            val n = queue.removeFirst()
            count++
            val cls = n.className?.toString() ?: ""
            if (AD_SDK_PREFIXES.any { cls.startsWith(it) }) return true
            val vid = n.getVid()?.toString()?.lowercase()
            if (vid != null && AD_VID.containsMatchIn(vid)) return true
            val text = (n.text ?: n.contentDescription)?.toString()
            if (text != null && text.length <= 16 && AD_TEXT.containsMatchIn(text)) return true
            for (i in 0 until n.childCount.coerceAtMost(64)) runCatching { n.getChild(i) }.getOrNull()?.let(queue::add)
        }
        // Almost nothing in the tree: a video / canvas / game engine surface.
        return count < 12
    }

    private fun activity(): String? = (host as? io.github.kunkai2002.splashclean.service.CleanAccessibilityService)?.topActivityId

    private fun treeHasSkipText(root: AccessibilityNodeInfo): Boolean {
        for (word in listOf("跳过", "跳過", "skip")) {
            val found = runCatching { root.findAccessibilityNodeInfosByText(word) }.getOrNull()
            if (!found.isNullOrEmpty()) return true
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotAndTap(s: Session) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hw.close()
                } ?: return
                try {
                    handleScreenshot(s, bitmap)
                } catch (t: Throwable) {
                    Log.w("SplashFallback", "ocr failed", t)
                } finally {
                    bitmap.recycle()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.d("SplashFallback", "screenshot failed: $errorCode")
            }
        })
    }

    private fun handleScreenshot(s: Session, bitmap: Bitmap) {
        if (session !== s || s.acted) return
        val w = bitmap.width
        val h = bitmap.height
        val ocr = PaddleOcr.get(service)
        val plausible = { box: Rect ->
            box.height() in (h * 0.012f).toInt()..(h * 0.09f).toInt() && box.width() < w * 0.5f
        }
        val regions = listOf(Rect(0, 0, w, (h * 0.3f).toInt()), Rect(0, (h * 0.72f).toInt(), w, h))
        var hit: OcrLine? = null
        for (region in regions) {
            hit = ocr.recognize(bitmap, region, recFilter = plausible).firstOrNull { isSkipText(it.text) && it.confidence >= 0.7f }
            if (hit != null) break
        }
        val line = hit ?: return
        if (session !== s || s.acted || topApp() != s.appId) return
        // Screenshot pixels map 1:1 to screen coordinates on the default display.
        val sx = ScreenInfo.width.toFloat() / w
        val sy = ScreenInfo.height.toFloat() / h
        val x = line.box.exactCenterX() * sx
        val y = line.box.exactCenterY() * sy
        if (Actions.tap(service, x, y)) {
            s.taps++
            if (s.taps == 1) onTapped(s.appId, line.text, System.currentTimeMillis() - s.start)
            // If the button did not respond (not active yet), the next tick may tap once more.
            if (s.taps >= 1) executor.schedule({ runCatching { if (s.taps >= 1) s.acted = true } }, 1200, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val SPLASH_ACTIVITY = Regex("(?i)(splash|launch|welcome|advert|loading|startup|start)")
        private val AD_VID = Regex("(splash|advert|^ad_|_ad$|_ad_|adview|ad_container|countdown|count_down)")
        private val AD_TEXT = Regex("(广告|廣告|第三方应用|第三方應用|扭动|扭一扭|摇一摇|搖一搖|点击跳转|點擊跳轉|了解详情|立即下载|查看详情|滑动|上滑|^AD$|^Ad$)")
        private val AD_SDK_PREFIXES = listOf(
            "com.bytedance.sdk.openadsdk", "com.qq.e.", "com.kwad.", "com.baidu.mobads", "com.sigmob.",
            "com.jd.ad.", "com.beizi.", "com.huawei.openalliance.ad", "com.miui.zeus", "com.heytap.msp",
            "com.vivo.mobilead", "com.mbridge.msdk", "com.octopus.", "com.ubix.",
        )

        private val SKIP = Regex(
            "^(\\d{1,2}\\s*[sS秒]?\\s*[|｜丨/:：]?)?(跳过|跳過|skip|Skip|SKIP)(广告|廣告|ad|Ad|AD)?([|｜丨/:：]?\\d{1,2}\\s*[sS秒]?)?[>›»〉]?$"
        )

        fun isSkipText(raw: String): Boolean {
            val t = raw.replace(Regex("\\s+"), "")
            if (t.length > 10) return false
            return SKIP.matches(t)
        }
    }
}
