package com.example.accessibility

import android.graphics.Bitmap

/**
 * The capability surface the agent/tools use to read and drive the screen, WITHOUT
 * depending on the concrete [AgentAccessibilityService]. The service implements this
 * and publishes itself via [Accessibility.controller]; tools and the executor depend
 * only on this interface. This dependency inversion is what lets every tool and the
 * agent loop be built independently/in parallel (see BUILD_PLAN.md).
 *
 * Index-based methods refer to the [UiElement.index] from the most recent
 * [readScreen]; indices are NOT stable across reads, so callers must re-[readScreen]
 * after any screen-changing action (ARCHITECTURE.md §3.3).
 */
interface AccessibilityController {
    /** True once the AccessibilityService is connected and able to read/act. */
    val isReady: Boolean

    /** Read the foreground screen. When [redact] is true, sensitive text is stripped. */
    suspend fun readScreen(redact: Boolean = true): ScreenState

    suspend fun tap(index: Int): Boolean
    suspend fun tapAt(x: Int, y: Int): Boolean
    suspend fun inputText(index: Int, text: String): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean
    suspend fun scroll(index: Int, forward: Boolean): Boolean
    suspend fun longPress(index: Int): Boolean

    fun back(): Boolean
    fun home(): Boolean
    fun recents(): Boolean
    fun openNotifications(): Boolean

    /** API 30+; null on failure / FLAG_SECURE / rate-limit. */
    suspend fun takeScreenshot(): Bitmap?
}

/**
 * Process-wide accessor for the live [AccessibilityController]. The
 * AccessibilityService sets this in onServiceConnected and clears it on unbind.
 * Consumers must null-check (`Accessibility.controller?.takeIf { it.isReady }`).
 */
object Accessibility {
    @Volatile
    var controller: AccessibilityController? = null
}
