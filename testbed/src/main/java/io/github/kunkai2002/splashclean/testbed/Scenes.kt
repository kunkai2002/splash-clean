package io.github.kunkai2002.splashclean.testbed

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "TESTBED"

/** Every scene logs "SHOWN <scene>" on create and "SKIP <scene> <ms>" when the skip control is used. */
abstract class Scene : Activity() {
    abstract val scene: String
    private var shownAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownAt = SystemClock.uptimeMillis()
        Log.i(TAG, "SHOWN $scene")
    }

    private var done = false

    protected fun skipped(what: String = "SKIP") {
        Log.i(TAG, "$what $scene ${SystemClock.uptimeMillis() - shownAt}")
        done = true
        showContent()
    }

    protected fun showContent() {
        setContentView(TextView(this).apply {
            text = "Main content of $scene"
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }

    /** Ad closes by itself after [ms] like a real splash; logs TIMEOUT when nobody skipped. */
    protected fun autoClose(ms: Long) {
        handler.postDelayed({
            if (!isFinishing && !done) {
                done = true
                Log.i(TAG, "TIMEOUT $scene")
                showContent()
            }
        }, ms)
    }

    protected fun adBackground(context: Context) = FrameLayout(context).apply {
        setBackgroundColor(Color.rgb(200, 60, 40))
        addView(TextView(context).apply {
            text = "年终大促 全场五折"
            textSize = 34f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        addView(TextView(context).apply {
            text = "广告"
            textSize = 12f
            setTextColor(Color.WHITE)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(40, 0, 0, 60) })
    }

    protected fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/** Pangle (穿山甲) style: TextView with the SDK's view id. */
class PangleScene : Scene() {
    override val scene = "pangle"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = adBackground(this)
        val skip = TextView(this).apply {
            id = R.id.tt_splash_skip_btn
            text = "跳过 5"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp())
            setOnClickListener { skipped() }
        }
        root.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48.dp(), 16.dp(), 0) })
        setContentView(root)
        autoClose(5000)
    }
}

/** Generic "5s | 跳过" label inside a clickable container, no special id. */
class TextScene : Scene() {
    override val scene = "text"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = adBackground(this)
        val box = LinearLayout(this).apply {
            isClickable = true
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(20.dp(), 8.dp(), 20.dp(), 8.dp())
            setOnClickListener { skipped() }
            addView(TextView(context).apply { text = "5s | 跳过"; setTextColor(Color.WHITE) })
        }
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 16.dp(), 64.dp()) })
        setContentView(root)
        autoClose(5000)
    }
}

/** Skip button drawn on a canvas and hidden from accessibility: only OCR can find it. */
class CanvasScene : Scene() {
    override val scene = "canvas"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = object : View(this) {
            val bg = Paint().apply { color = Color.rgb(30, 90, 170) }
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * resources.displayMetrics.scaledDensity }
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 30 * resources.displayMetrics.scaledDensity }
            var pillRect = RectF()

            override fun onDraw(canvas: Canvas) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
                canvas.drawText("新品首发 限时抢购", width * 0.12f, height * 0.45f, big)
                val w = 84 * resources.displayMetrics.density
                val h = 34 * resources.displayMetrics.density
                pillRect = RectF(width - w - 16 * resources.displayMetrics.density, 48 * resources.displayMetrics.density,
                    width - 16 * resources.displayMetrics.density, 48 * resources.displayMetrics.density + h)
                canvas.drawRoundRect(pillRect, h / 2, h / 2, pill)
                canvas.drawText("跳过 5", pillRect.left + w * 0.2f, pillRect.centerY() + text.textSize * 0.35f, text)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP && pillRect.contains(event.x, event.y)) {
                    skipped()
                    return true
                }
                return true
            }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setContentView(view)
        autoClose(6000)
    }
}

/** "发现新版本" dialog: the rule should press "以后再说", never "立即更新". */
class UpdateScene : Scene() {
    override val scene = "update"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showContent()
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage("v9.9.9 修复了一些问题")
            .setPositiveButton("立即更新") { _, _ -> skipped("WRONG") }
            .setNegativeButton("以后再说") { _, _ -> skipped() }
            .setCancelable(false)
            .show()
    }
}

/** Close button is an unlabeled icon with an app-specific id: nothing built in can find it; the user must teach it. */
class CustomScene : Scene() {
    override val scene = "custom"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = adBackground(this)
        val close = View(this).apply {
            id = R.id.close_x
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            setOnClickListener { skipped() }
        }
        root.addView(close, FrameLayout.LayoutParams(44.dp(), 44.dp(), Gravity.TOP or Gravity.END).apply { setMargins(0, 48.dp(), 16.dp(), 0) })
        setContentView(root)
        autoClose(15000)
    }
}

/** A "close" button that comes back 0.6 s after every tap: a rule that matches it would loop. */
class LoopScene : Scene() {
    override val scene = "loop"
    private var taps = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(TextView(this).apply { text = "个人中心"; textSize = 24f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val handler = Handler(Looper.getMainLooper())
        lateinit var btn: View
        btn = TextView(this).apply {
            id = R.id.close_loop
            text = "关闭"
            textSize = 16f
            setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
            setBackgroundColor(Color.LTGRAY)
            setOnClickListener {
                taps++
                Log.i(TAG, "TAP loop $taps")
                visibility = View.GONE
                handler.postDelayed({ btn.visibility = View.VISIBLE }, 600)
            }
        }
        root.addView(btn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48.dp(), 16.dp(), 0) })
        setContentView(root)
    }
}

/** Counts accelerometer events per second, like a "shake to open" splash ad would listen. */
class ShakeScene : Scene() {
    override val scene = "shake"
    private var count = 0
    private val listener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent?) { count++ }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Log.i(TAG, "SENSOR shake events/s=$count")
            count = 0
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showContent()
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        sm.registerListener(listener, sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER), android.hardware.SensorManager.SENSOR_DELAY_GAME)
        handler.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        getSystemService(android.hardware.SensorManager::class.java).unregisterListener(listener)
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}

/** Video player with "跳过片头": must NOT be tapped (false-positive check). */
class IntroScene : Scene() {
    override val scene = "intro"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(Button(this).apply {
            text = "跳过片头"
            setOnClickListener { skipped("WRONG") }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48.dp(), 16.dp(), 0) })
        setContentView(root)
        Handler(Looper.getMainLooper()).postDelayed({ Log.i(TAG, "UNTOUCHED intro") }, 8000)
    }
}
