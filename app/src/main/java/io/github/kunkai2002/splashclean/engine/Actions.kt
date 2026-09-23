// Rule actions, adapted from gkd-kit/gkd `GkdAction.kt` (GPL-3.0). Privileged (Shizuku) paths removed.
package io.github.kunkai2002.splashclean.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import io.github.kunkai2002.splashclean.rule.RawSubscription
import io.github.kunkai2002.splashclean.rule.ScreenInfo

data class ActionResult(
    val action: String,
    val result: Boolean,
    val position: Pair<Float, Float>? = null,
)

object Actions {
    fun tap(service: AccessibilityService, x: Float, y: Float, duration: Long = ViewConfiguration.getTapTimeout().toLong()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!ScreenInfo.inScreen(x, y)) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun swipe(service: AccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo) =
        ActionResult("clickNode", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))

    private fun clickCenter(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        props: RawSubscription.LocationProps,
        long: Boolean = false,
    ): ActionResult {
        val rect = node.boundsInScreen()
        val p = props.position?.calc(rect)
        val x = p?.first ?: ((rect.right + rect.left) / 2f)
        val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
        val ok = tap(service, x, y, if (long) 500L else ViewConfiguration.getTapTimeout().toLong())
        return ActionResult(if (long) "longClickCenter" else "clickCenter", ok, x to y)
    }

    fun perform(
        service: AccessibilityService,
        action: String?,
        node: AccessibilityNodeInfo,
        props: RawSubscription.LocationProps,
    ): ActionResult {
        val name = action ?: when {
            props.position != null -> "clickCenter"
            props.swipeArg != null -> "swipe"
            else -> "click"
        }
        return when (name) {
            "clickNode" -> clickNode(node)
            "clickCenter" -> clickCenter(service, node, props)
            "click" -> {
                if (node.isClickable) {
                    val r = clickNode(node)
                    if (r.result) return r.copy(action = "click")
                }
                clickCenter(service, node, props).copy(action = "click")
            }

            "longClickNode" -> ActionResult(name, node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            "longClickCenter" -> clickCenter(service, node, props, long = true)
            "longClick" -> {
                if (node.isLongClickable) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (ok) return ActionResult(name, true)
                }
                clickCenter(service, node, props, long = true).copy(action = name)
            }

            "back" -> ActionResult(name, service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            "none" -> ActionResult(name, true)
            "swipe" -> {
                val rect = node.boundsInScreen()
                val arg = props.swipeArg ?: return ActionResult(name, false)
                val start = arg.start.calc(rect) ?: return ActionResult(name, false)
                val end = arg.end?.calc(rect) ?: start
                ActionResult(name, swipe(service, start.first, start.second, end.first, end.second, arg.duration), end)
            }

            else -> clickCenter(service, node, props).copy(action = name)
        }
    }
}
