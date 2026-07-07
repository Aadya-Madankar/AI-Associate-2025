package com.example.accessibility

import android.graphics.Rect

/**
 * One visible/interactive element in a screen snapshot (the "set-of-marks" the model
 * grounds against). The model references elements by [index]; our code resolves
 * `index → bounds.center → dispatchGesture`. Indices are regenerated on every read
 * and are NOT stable across renders. See ARCHITECTURE.md §3.3.
 */
data class UiElement(
    val index: Int,
    val role: String,                       // simplified className: Button, EditText, TextView…
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val resourceId: String? = null,
    val bounds: Rect,                        // absolute screen pixels; center is the tap target
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false
)

/**
 * A full observation of the foreground screen, handed back to the model as a tool
 * result. [secure] is true when the [com.example.permission.SecureContextDetector]
 * flagged the screen (FLAG_SECURE, password field, denylisted app…), in which case
 * element text is redacted before this ever leaves the device.
 *
 * The out-of-band secure side channels ([sawPasswordField], [sawSensitiveLabel],
 * [hasHiddenSensitiveNode]) are observed by the reader over EVERY visited node —
 * independently of the visibility filter and the element cap — so a sensitive node that
 * was trimmed from [elements] (off-screen for a frame, zero-area, past the budget, or
 * hidden by Android-14 `isAccessibilityDataSensitive`) still reaches the secure-context
 * gate. They are carried on the screen so they survive the on-device redaction copy and
 * remain available to the detector after redaction.
 */
data class ScreenState(
    val packageName: String?,
    val activity: String? = null,
    val elements: List<UiElement>,
    val secure: Boolean = false,
    val capturedAtElapsedMs: Long = 0L,
    /** Reader saw a password field anywhere in the tree, even if it was trimmed. */
    val sawPasswordField: Boolean = false,
    /** Reader saw an OTP/CVV/PIN/card/IBAN-labeled node anywhere, even if trimmed. */
    val sawSensitiveLabel: Boolean = false,
    /** API 34+: reader hit a node whose `isAccessibilityDataSensitive` hid it. */
    val hasHiddenSensitiveNode: Boolean = false
) {
    companion object {
        val EMPTY = ScreenState(packageName = null, elements = emptyList())
    }
}
