package io.github.kunkai2002.splashclean.capture

import io.github.kunkai2002.splashclean.data.l10n
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.TeachActivity
import io.github.kunkai2002.splashclean.data.InstalledApps
import io.github.kunkai2002.splashclean.engine.boundsInScreen
import io.github.kunkai2002.splashclean.engine.getVid
import io.github.kunkai2002.splashclean.rule.ScreenInfo
import io.github.kunkai2002.splashclean.service.CleanAccessibilityService
import io.github.kunkai2002.splashclean.service.Notifications
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

@Serializable
data class SnapNode(
    val id: Int,
    val pid: Int,
    val index: Int,
    val depth: Int,
    val name: String? = null,
    val vid: String? = null,
    val idName: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

@Serializable
data class Snapshot(
    val time: Long,
    val appId: String,
    val appName: String,
    val activityId: String? = null,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<SnapNode>,
    val hasScreenshot: Boolean,
)

object SnapshotStore {
    val json = Json { ignoreUnknownKeys = true }
    fun dir(context: Context) = File(context.filesDir, "snapshots").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { File(it, "snapshot.json").exists() }?.sortedByDescending { it.name } ?: emptyList()

    fun load(folder: File): Snapshot? = runCatching {
        json.decodeFromString(Snapshot.serializer(), File(folder, "snapshot.json").readText())
    }.getOrNull()

    fun screenshot(folder: File): File = File(folder, "screen.png")

    fun prune(context: Context, keep: Int = 20) {
        list(context).drop(keep).forEach { it.deleteRecursively() }
    }
}

object SnapshotTaker {
    private val io = Executors.newSingleThreadExecutor()
    private const val MAX_NODES = 3000

    fun take(service: CleanAccessibilityService, callback: (Result<String>) -> Unit) {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root == null) {
            callback(Result.failure(IllegalStateException("no window")))
            return
        }
        val appId = root.packageName?.toString() ?: ""
        val nodes = dump(root)
        val snapshot = Snapshot(
            time = System.currentTimeMillis(),
            appId = appId,
            appName = InstalledApps.label(service, appId),
            activityId = service.topActivityId.takeIf { service.topAppId == appId },
            screenWidth = ScreenInfo.width,
            screenHeight = ScreenInfo.height,
            nodes = nodes,
            hasScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        )
        val folder = File(SnapshotStore.dir(service), snapshot.time.toString())
        fun save(bitmap: Bitmap?) = io.execute {
            val r = runCatching {
                folder.mkdirs()
                bitmap?.let { b ->
                    SnapshotStore.screenshot(folder).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
                File(folder, "snapshot.json").writeText(
                    SnapshotStore.json.encodeToString(Snapshot.serializer(), snapshot.copy(hasScreenshot = bitmap != null))
                )
                SnapshotStore.prune(service)
                folder.absolutePath
            }
            callback(r)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenshot(service, attempt = 0) { save(it) }
        } else {
            save(null)
        }
    }

    /** Retries when the OCR fallback has just taken a screenshot (Android allows ~3 per second). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun screenshot(service: CleanAccessibilityService, attempt: Int, done: (Bitmap?) -> Unit) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, io, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = try {
                    Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hw.close()
                }
                done(bmp)
            }

            override fun onFailure(errorCode: Int) {
                if (attempt < 4) {
                    retryHandler.postDelayed({ screenshot(service, attempt + 1, done) }, 400)
                } else {
                    done(null)
                }
            }
        })
    }

    private val retryHandler = Handler(Looper.getMainLooper())

    private fun dump(root: AccessibilityNodeInfo): List<SnapNode> {
        val out = ArrayList<SnapNode>()
        data class Item(val node: AccessibilityNodeInfo, val pid: Int, val index: Int, val depth: Int)
        val queue = ArrayDeque<Item>()
        queue.add(Item(root, -1, 0, 0))
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val (n, pid, index, depth) = queue.removeFirst()
            val id = out.size
            val b = n.boundsInScreen()
            out.add(
                SnapNode(
                    id = id, pid = pid, index = index, depth = depth,
                    name = n.className?.toString(), vid = n.getVid()?.toString(), idName = n.viewIdResourceName,
                    text = n.text?.toString(), desc = n.contentDescription?.toString(),
                    clickable = n.isClickable, visible = n.isVisibleToUser,
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                )
            )
            for (i in 0 until n.childCount.coerceAtMost(512)) {
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                queue.add(Item(c, id, i, depth + 1))
            }
        }
        return out
    }
}

/** Collapses the shade, waits for the app underneath to settle, then captures it. */
object CaptureFlow {
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        val service = CleanAccessibilityService.instance
        if (service == null) {
            Toast.makeText(context, context.l10n().getString(R.string.capture_need_service), Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        handler.postDelayed({
            service.takeSnapshot { result ->
                handler.post {
                    result.onSuccess { path -> notifyCaptured(service, path) }
                        .onFailure { Toast.makeText(service, service.l10n().getString(R.string.capture_failed), Toast.LENGTH_LONG).show() }
                }
            }
        }, 450)
    }

    private fun notifyCaptured(context: Context, path: String) {
        Notifications.ensureChannels(context)
        val intent = Intent(context, TeachActivity::class.java)
            .putExtra(TeachActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, path.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.l10n().getString(R.string.capture_done_title))
            .setContentText(context.l10n().getString(R.string.capture_done_text))
            .setAutoCancel(true)
            .setGroup("capture")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_CAPTURE, n) }
    }
}

class CaptureTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CaptureFlow.start(context)
}

class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        CaptureFlow.start(this)
    }
}
