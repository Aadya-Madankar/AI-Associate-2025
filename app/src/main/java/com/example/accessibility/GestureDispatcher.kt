package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Builds and dispatches synthetic gestures (tap / long-press / swipe) through the
 * [AccessibilityService.dispatchGesture] path. This is the coordinate-based fallback
 * for [NodeActionExecutor] when `performAction` returns false (common on Compose,
 * WebView and canvas surfaces), and the only way to drive arbitrary swipes.
 *
 * Requires `canPerformGestures="true"` in the service config and API 24+ (the
 * project's minSdk), so no version guard is needed here. Callbacks are delivered on
 * a caller-supplied [Handler] (the service main looper by default) and bridged into
 * coroutines via [suspendCancellableCoroutine]. See ARCHITECTURE.md §3.2.
 */
class GestureDispatcher(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    /**
     * Single-finger tap at ([x], [y]). [durationMs] is the press-down time; the
     * platform default (~50 ms) reads as a normal click. Returns true once the
     * gesture completes, false if it was cancelled or rejected.
     */
    suspend fun tap(x: Int, y: Int, durationMs: Long = TAP_DURATION_MS): Boolean {
        // Tell the roaming XENO avatar (if on screen) to walk/run to this spot and "press" it.
        // Fire-and-forget: the real tap dispatches immediately below and never waits on the
        // overlay's walk animation, which just plays out late/concurrently instead.
        com.example.overlay.OverlayBus.emitTap(x, y)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // Clamp duration to the platform's positive, sane range.
        val safeDuration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Long-press at ([x], [y]) by holding the down stroke for [durationMs]
     * (default ~600 ms, the platform long-press threshold).
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = LONG_PRESS_DURATION_MS): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // Clamp duration to the platform's positive, sane range.
        val safeDuration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Swipe from ([x1], [y1]) to ([x2], [y2]) over [durationMs]. A longer duration
     * produces a slow drag; ~200-400 ms reads as a fling/scroll.
     */
    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = SWIPE_DURATION_MS
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        // Clamp duration to the platform's positive, sane range.
        val safeDuration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Dispatch a pre-built [GestureDescription] and suspend until the platform
     * reports completion or cancellation. Guards against the service throwing if
     * gesture dispatch is unavailable.
     */
    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = try {
                service.dispatchGesture(gesture, callback, handler)
            } catch (t: Throwable) {
                false
            }
            // If the platform rejected the gesture synchronously, no callback fires.
            if (!accepted && cont.isActive) cont.resume(false)
        }

    companion object {
        const val TAP_DURATION_MS = 50L
        const val LONG_PRESS_DURATION_MS = 600L
        const val SWIPE_DURATION_MS = 300L
    }
}
