package io.github.kunkai2002.splashclean.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kunkai2002.splashclean.R
import io.github.kunkai2002.splashclean.data.l10n

/**
 * Small card shown over the current app (accessibility overlay, no extra permission) asking whether
 * a rule that keeps firing has mistaken a normal screen for an ad.
 */
object MistakePrompt {
    private const val TIMEOUT_MS = 20_000L
    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun show(
        service: AccessibilityService,
        appLabel: String,
        ruleName: String,
        onYes: () -> Unit,
        onNo: () -> Unit,
        onTimeout: () -> Unit,
    ) = handler.post {
        dismiss(service)
        val ctx = service.l10n()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val wm = service.getSystemService(WindowManager::class.java)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 20f * d
                setColor(Color.rgb(0x22, 0x2B, 0x26))
            }
            elevation = 12f * d
        }
        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.mistake_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.mistake_body, ruleName, appLabel)
            setTextColor(Color.rgb(0xD6, 0xE3, 0xDB))
            textSize = 14f
            setPadding(0, dp(6), dp(8), dp(4))
        })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, color: Int, action: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(color)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                dismiss(service)
                action()
            }
        }
        buttons.addView(button(ctx.getString(R.string.mistake_no), Color.rgb(0xB4, 0xC6, 0xBB), onNo))
        buttons.addView(button(ctx.getString(R.string.mistake_yes), Color.rgb(0x9B, 0xE0, 0xBC), onYes))
        card.addView(buttons)

        val screenW = ctx.resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            minOf(screenW - dp(32), dp(440)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        runCatching { wm.addView(card, lp) }.onFailure { return@post }
        current = card
        handler.postDelayed({
            if (current === card) {
                dismiss(service)
                onTimeout()
            }
        }, TIMEOUT_MS)
    }

    fun dismiss(service: AccessibilityService) {
        val v = current ?: return
        current = null
        runCatching { service.getSystemService(WindowManager::class.java).removeView(v) }
    }
}
