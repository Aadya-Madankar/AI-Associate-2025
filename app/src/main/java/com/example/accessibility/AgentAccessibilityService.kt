package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.example.permission.DenyLists
import com.example.security.ScreenRedactor
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single read + act engine for the phone-control agent.
 *
 * Responsibilities (ARCHITECTURE.md §3.2):
 *  - Implements [AccessibilityController] and publishes itself to
 *    [Accessibility.controller] in [onServiceConnected]; clears it in [onUnbind].
 *  - Tracks the foreground package from `TYPE_WINDOW_STATE_CHANGED` events so reads
 *    can stamp the active app.
 *  - Reads the active-window tree via [ScreenReader] (index→Rect cache).
 *  - Acts via [NodeActionExecutor] (performAction + gesture fallback),
 *    [GestureDispatcher] (raw taps/swipes) and [GlobalActions] (Back/Home/...).
 *  - Captures a software [Bitmap] via [takeScreenshot] on API 30+, rate-limited to
 *    one shot per [SCREENSHOT_MIN_INTERVAL_MS] to respect the platform throttle.
 *
 * This class only needs to compile and behave; its manifest `<service>` registration
 * and `@xml/agent_a11y_config` wiring are added later by the integration stream.
 */
class AgentAccessibilityService : AccessibilityService(), AccessibilityController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val screenReader = ScreenReader()
    private lateinit var gestures: GestureDispatcher
    private lateinit var nodeActions: NodeActionExecutor
    private lateinit var globalActions: GlobalActions

    /** Serializes screenshot requests so the rate-limit check is race-free. */
    private val screenshotMutex = Mutex()

    @Volatile
    private var foregroundPackage: String? = null

    @Volatile
    private var lastScreenshotElapsedMs: Long = 0L

    @Volatile
    private var connected: Boolean = false

    // --- Lifecycle -----------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        gestures = GestureDispatcher(this, mainHandler)
        nodeActions = NodeActionExecutor(screenReader, gestures)
        globalActions = GlobalActions(this)
        connected = true
        Accessibility.controller = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() {
        // Required override; no queued feedback to flush.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Only relinquish the global accessor if it still points at us.
        if (Accessibility.controller === this) {
            Accessibility.controller = null
        }
        connected = false
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (Accessibility.controller === this) {
            Accessibility.controller = null
        }
        connected = false
        super.onDestroy()
    }

    // --- AccessibilityController --------------------------------------------

    override val isReady: Boolean
        get() = connected

    override suspend fun readScreen(redact: Boolean): ScreenState {
        val root = safeRoot()
        // The accessibility service is the ONLY component that can observe the live
        // window's FLAG_SECURE state and the keyguard, so we compute the `secure` flag
        // here (fail-secure on error) and feed it to the reader. The secure-context
        // detector, permission engine, redactor and serializer all key off this flag.
        val raw = screenReader.read(
            root = root,
            packageName = foregroundPackage,
            activity = null,
            secure = isSecureContext(),
            capturedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        // SECURITY (fail-closed): the reader's inclusion filter (keep) and MAX_ELEMENTS
        // cap can drop a password / OTP / card field (off-screen for the frame, zero-area
        // during a keyguard/dialog animation, or past the 30-element budget). Those nodes
        // never appear in `raw.elements`, so DefaultSecureContextDetector.inspect — which
        // only sees elements — would mis-classify the screen as non-secure and the engine
        // would auto-act on a credentials/OTP screen with zero confirmation. ScreenReader
        // accumulates these signals over EVERY visited node (independent of keep/cap) for
        // exactly this reason; fold them back in here so a dropped/over-cap sensitive node
        // still marks the screen secure (FLAG_SECURE_WINDOW => hard block) and the redactor
        // blanks element text wholesale before anything is serialized to the cloud.
        val secured =
            if (!raw.secure &&
                (screenReader.sawPasswordField() ||
                    screenReader.sawSensitiveLabel() ||
                    screenReader.hasHiddenSensitiveNode())
            ) {
                raw.copy(secure = true)
            } else {
                raw
            }
        // Honor the contract: when `redact` is true, strip sensitive text on-device
        // BEFORE this screen is serialized and sent to the cloud. The redactor also
        // blanks element text wholesale when `secured.secure` is true.
        return if (redact) ScreenRedactor.INSTANCE.redact(secured) else secured
    }

    /**
     * Live secure-context flag for the active window. True when the foreground package is
     * a denylisted banking/wallet/authenticator app, OR the foreground window carries
     * [WindowManager.LayoutParams.FLAG_SECURE], OR the device is locked / showing the
     * keyguard. Fail-secure: any framework error yields `true` so a screen we cannot
     * reliably inspect is treated as secure rather than leaked.
     *
     * SECURITY: the denylist check is the read-boundary defense for the post-action
     * readScreen() done by Tap/Scroll/Swipe/LongPress/OpenNotifications tools. A tap that
     * opens a banking app is gated against the PRE-tap (non-denylisted) screen, so the
     * post-tap read lands on the banking account overview. Banking apps typically set no
     * FLAG_SECURE (which only blocks screenshots, not a11y node text) and the device is
     * unlocked, so without this gate the balances / payees / account numbers would be
     * serialized to the cloud verbatim. Marking the package secure here makes
     * ScreenRedactor blank all element text wholesale before serialization.
     */
    private fun isSecureContext(): Boolean = try {
        DenyLists.isDenylistedPackage(foregroundPackage) ||
            activeWindowHasSecureFlag() ||
            isKeyguardActive()
    } catch (t: Throwable) {
        true
    }

    /**
     * Inspect the live windows for [WindowManager.LayoutParams.FLAG_SECURE] on the
     * active/focused window.
     *
     * The public [android.view.accessibility.AccessibilityWindowInfo] surface does not
     * expose the window's layout flags, so we read them off-band via the framework's
     * `getLayoutParamFlags()` (hidden) on the window info. We probe it defensively:
     *  - flags read AND FLAG_SECURE set  -> secure (true).
     *  - flags read AND FLAG_SECURE clear -> not secure (false).
     *  - flags genuinely unreadable on this device (hidden API blocked) -> we cannot
     *    use this signal; return false here and let the other secure-context signals
     *    (keyguard, password field, denylist, sensitive labels) gate the screen.
     *  - a window enumerated but throws while inspecting -> fail secure for THAT window.
     *
     * Returns true if any active/focused window is observed to be secure.
     */
    private fun activeWindowHasSecureFlag(): Boolean {
        val activeWindows = try {
            windows
        } catch (t: Throwable) {
            // We could not even enumerate the windows; fail secure.
            return true
        } ?: return false

        for (window in activeWindows) {
            if (window == null) continue
            if (window.isActive || window.isFocused) {
                when (inspectWindowSecureFlag(window)) {
                    SecureFlag.SECURE -> return true
                    SecureFlag.NOT_SECURE, SecureFlag.UNKNOWN -> Unit
                }
            }
        }
        return false
    }

    /** Result of probing a single window's FLAG_SECURE bit. */
    private enum class SecureFlag { SECURE, NOT_SECURE, UNKNOWN }

    /**
     * Read [window]'s layout flags reflectively and report whether FLAG_SECURE is set.
     * [SecureFlag.UNKNOWN] means the hidden accessor is unavailable/blocked on this
     * device (a steady-state condition we do NOT treat as secure, to avoid blanking
     * every normal screen); a window that throws while being inspected fails secure.
     */
    private fun inspectWindowSecureFlag(
        window: android.view.accessibility.AccessibilityWindowInfo
    ): SecureFlag {
        val accessor = layoutParamFlagsAccessor ?: return SecureFlag.UNKNOWN
        return try {
            val flags = accessor.invoke(window) as? Int ?: return SecureFlag.UNKNOWN
            if ((flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                SecureFlag.SECURE
            } else {
                SecureFlag.NOT_SECURE
            }
        } catch (t: Throwable) {
            // The accessor exists but failed on this specific window: fail secure.
            SecureFlag.SECURE
        }
    }

    /**
     * Cached reflective handle to `AccessibilityWindowInfo.getLayoutParamFlags()` (hidden
     * API). Resolved once; null when the method is absent/blocked on this device.
     */
    private val layoutParamFlagsAccessor: java.lang.reflect.Method? by lazy {
        try {
            android.view.accessibility.AccessibilityWindowInfo::class.java
                .getMethod("getLayoutParamFlags")
        } catch (t: Throwable) {
            null
        }
    }

    /** True when the device is locked / keyguard is showing. Fail-secure on error. */
    private fun isKeyguardActive(): Boolean = try {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        when {
            km == null -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> km.isDeviceLocked
            else -> km.isKeyguardLocked
        }
    } catch (t: Throwable) {
        true
    }

    override suspend fun tap(index: Int): Boolean =
        nodeActions.click(safeRoot(), index)

    override suspend fun tapAt(x: Int, y: Int): Boolean =
        gestures.tap(x, y)

    override suspend fun inputText(index: Int, text: String): Boolean =
        nodeActions.setText(safeRoot(), index, text)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        gestures.swipe(x1, y1, x2, y2, durationMs)

    override suspend fun scroll(index: Int, forward: Boolean): Boolean =
        nodeActions.scroll(safeRoot(), index, forward)

    override suspend fun longPress(index: Int): Boolean =
        nodeActions.longPress(safeRoot(), index)

    override fun back(): Boolean = globalActions.back()
    override fun home(): Boolean = globalActions.home()
    override fun recents(): Boolean = globalActions.recents()
    override fun openNotifications(): Boolean = globalActions.openNotifications()

    override suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return screenshotMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val sinceLast = now - lastScreenshotElapsedMs
            if (lastScreenshotElapsedMs != 0L && sinceLast < SCREENSHOT_MIN_INTERVAL_MS) {
                // Honor the platform's ~1s throttle without provoking an error result.
                return@withLock null
            }
            val bitmap = captureScreenshot()
            if (bitmap != null) lastScreenshotElapsedMs = SystemClock.elapsedRealtime()
            bitmap
        }
    }

    // --- Internals -----------------------------------------------------------

    /** `getRootInActiveWindow()` guarded against the framework throwing. */
    private fun safeRoot() = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    /**
     * API 30+ screenshot. Copies the [android.hardware.HardwareBuffer] into a software
     * [Bitmap] (so callers can read pixels and the hardware buffer is released) and
     * returns null on any failure / FLAG_SECURE / unsupported display.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshot(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = try {
                                val hardware = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                // Copy to a mutable software bitmap so pixels are readable
                                // and the hardware buffer can be released immediately.
                                val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                software
                            } catch (t: Throwable) {
                                null
                            } finally {
                                try {
                                    screenshot.hardwareBuffer.close()
                                } catch (_: Throwable) {
                                }
                            }
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }

    companion object {
        /** Platform throttles `takeScreenshot` to roughly one per second. */
        const val SCREENSHOT_MIN_INTERVAL_MS = 1000L
    }
}
