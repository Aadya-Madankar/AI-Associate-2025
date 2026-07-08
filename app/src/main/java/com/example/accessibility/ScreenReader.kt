package com.example.accessibility

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.example.permission.DenyLists

/**
 * Reads the live accessibility tree into the model-facing [ScreenState].
 *
 * Strategy (ARCHITECTURE.md §3.2/§3.3):
 *  - DFS over `getRootInActiveWindow()`.
 *  - Keep only nodes that are visible to the user AND interactive
 *    (clickable / editable / scrollable / long-clickable) OR carry text.
 *  - Assign a fresh 0-based [UiElement.index] to each kept node and cache
 *    `index → bounds Rect` so the executor can resolve taps without re-walking.
 *  - Cap the result at [MAX_ELEMENTS] (~30) to fit the model's context budget.
 *  - On API < 33, every visited [AccessibilityNodeInfo] is `recycle()`d; on 33+
 *    recycling is a no-op and is skipped (guarded by version).
 *
 * The reader keeps no node references after [read] returns: it copies out the
 * primitive fields and bounds, then releases the nodes. Each call rebuilds the
 * index→Rect cache from scratch, mirroring the "indices regenerate every read"
 * contract.
 */
class ScreenReader(
    private val maxElements: Int = MAX_ELEMENTS
) {

    /** Snapshot of the last read: model index → absolute screen bounds. */
    @Volatile
    private var indexBounds: Map<Int, Rect> = emptyMap()

    /**
     * Out-of-band secure signals observed during the last [read]'s DFS, computed over
     * EVERY visited node — independently of the inclusion filter ([keep]) and the
     * [MAX_ELEMENTS] cap. The [com.example.permission.DefaultSecureContextDetector] gate
     * keys off [ScreenState.elements], which only carries nodes that survived the trim;
     * a password / OTP / Android-14 sensitive node that was dropped (off-screen for a
     * frame, zero-area, or past the element budget) would otherwise never reach the gate
     * and the screen would be mis-classified as non-secure. These flags let the service
     * feed those signals to the detector even when the field itself was not emitted.
     */
    @Volatile
    private var lastSawPasswordField: Boolean = false

    @Volatile
    private var lastSawSensitiveLabel: Boolean = false

    @Volatile
    private var lastHasHiddenSensitiveNode: Boolean = false

    /**
     * Walk [root] and produce the elements plus a fresh index→Rect cache.
     *
     * @param root the active-window root, typically `getRootInActiveWindow()`. May be
     *             null when no window is focused or the surface has no a11y tree.
     * @param packageName foreground package to stamp onto the [ScreenState].
     * @param activity optional resolved activity name.
     * @param secure whether the [com.example.permission.SecureContextDetector] flagged
     *               this screen; passed straight through to [ScreenState.secure].
     * @param capturedAtElapsedMs `SystemClock.elapsedRealtime()` at capture time.
     */
    fun read(
        root: AccessibilityNodeInfo?,
        packageName: String?,
        activity: String? = null,
        secure: Boolean = false,
        capturedAtElapsedMs: Long = 0L
    ): ScreenState {
        if (root == null) {
            indexBounds = emptyMap()
            lastSawPasswordField = false
            lastSawSensitiveLabel = false
            lastHasHiddenSensitiveNode = false
            return ScreenState(
                packageName = packageName,
                activity = activity,
                elements = emptyList(),
                secure = secure,
                capturedAtElapsedMs = capturedAtElapsedMs,
                sawPasswordField = false,
                sawSensitiveLabel = false,
                hasHiddenSensitiveNode = false
            )
        }

        val elements = ArrayList<UiElement>(maxElements)
        val bounds = LinkedHashMap<Int, Rect>(maxElements)
        // Secure signals are accumulated across EVERY visited node, independent of the
        // inclusion filter and the element cap (see [SecureScan]).
        val scan = SecureScan()
        // DFS. We do NOT recycle the caller-owned root; the service owns its lifecycle.
        dfs(root, elements, bounds, scan)

        indexBounds = bounds
        lastSawPasswordField = scan.sawPasswordField
        lastSawSensitiveLabel = scan.sawSensitiveLabel
        lastHasHiddenSensitiveNode = scan.hasHiddenSensitiveNode
        return ScreenState(
            packageName = packageName,
            activity = activity,
            elements = elements,
            secure = secure,
            capturedAtElapsedMs = capturedAtElapsedMs,
            // Out-of-band secure signals observed over EVERY node (see [SecureScan]),
            // carried on the screen so the detector still sees a trimmed/hidden
            // password / OTP / Android-14 sensitive node — and so they survive the
            // on-device redaction copy on the cloud-bound path.
            sawPasswordField = scan.sawPasswordField,
            sawSensitiveLabel = scan.sawSensitiveLabel,
            hasHiddenSensitiveNode = scan.hasHiddenSensitiveNode
        )
    }

    /** Absolute screen bounds for [index] from the most recent [read], or null. */
    fun boundsFor(index: Int): Rect? = indexBounds[index]

    /** True while there is at least one indexed element from the last [read]. */
    fun hasElements(): Boolean = indexBounds.isNotEmpty()

    /**
     * True if the last [read] saw a password field ([AccessibilityNodeInfo.isPassword])
     * ANYWHERE in the tree — including nodes the inclusion filter dropped or the
     * [MAX_ELEMENTS] cap never reached. The service feeds this to the secure-context
     * gate so a late/invisible/zero-area password input still yields
     * [com.example.permission.SecureReason.PASSWORD_FIELD] even when it was not emitted
     * as a [UiElement].
     */
    fun sawPasswordField(): Boolean = lastSawPasswordField

    /**
     * True if the last [read] saw a node whose hint / content-description /
     * resource-id matched [DenyLists.matchesSensitiveField] (OTP / CVV / PIN / card /
     * IBAN …) anywhere in the tree, regardless of the inclusion filter or element cap.
     * Lets the service raise [com.example.permission.SecureReason.OTP_OR_CARD_FIELD]
     * for a sensitive field that was trimmed out of the element list.
     */
    fun sawSensitiveLabel(): Boolean = lastSawSensitiveLabel

    /**
     * True if the last [read], on API >= 34, encountered a node whose
     * `isAccessibilityDataSensitive` made it unreadable. Fed into [ScreenState] so
     * [com.example.permission.DefaultSecureContextDetector.inspect] can raise
     * [com.example.permission.SecureReason.SENSITIVE_HIDDEN_NODE], avoiding the
     * SAFE-because-empty trap where a screen that hides sensitive content from
     * accessibility looks merely empty. Always false on API < 34.
     */
    fun hasHiddenSensitiveNode(): Boolean = lastHasHiddenSensitiveNode

    private fun dfs(
        node: AccessibilityNodeInfo,
        out: MutableList<UiElement>,
        bounds: MutableMap<Int, Rect>,
        scan: SecureScan
    ) {
        // SECURITY: observe secure signals on EVERY node first — before keep(), before
        // the bounds/visibility checks, and even after the element budget is exhausted.
        // Filtering decisions must never become security decisions: a dropped password /
        // OTP / sensitive-hidden node must still register on the gate's side channel.
        scan.observe(node)

        // The element cap only stops EMITTING elements; it must not stop the walk from
        // observing the secure signal on the remaining subtree.
        val budgetLeft = out.size < maxElements
        if (budgetLeft && keep(node)) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val index = out.size
            out += node.toUiElement(index, rect)
            bounds[index] = rect
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dfs(child, out, bounds, scan)
            } finally {
                // Each child is recycled by its parent (pre-33 only). The root is
                // owned by the caller (the service) and is never recycled here.
                recycleIfNeeded(child)
            }
        }
    }

    /**
     * Mutable accumulator for the out-of-band secure signals scanned across the full
     * tree. [observe] is called for every visited node, regardless of [keep] / cap /
     * visibility / area, so the secure-context gate is never defeated by the trim that
     * sits upstream of it.
     */
    private class SecureScan {
        var sawPasswordField: Boolean = false
        var sawSensitiveLabel: Boolean = false
        var hasHiddenSensitiveNode: Boolean = false

        fun observe(node: AccessibilityNodeInfo) {
            // SECURITY (ARCHITECTURE.md §4.3): node.isPassword is NOT set for
            // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (designed to be shown) and is
            // inconsistent for numberPassword across OEMs, so also inspect the raw
            // inputType variation. A field with a password/visible-password/web-password/
            // number-password variation is a secret-entry field regardless of isPassword
            // or how innocuously it is labeled.
            sawPasswordField = sawPasswordField || node.isPassword || node.hasPasswordInputType()

            if (!sawSensitiveLabel) {
                val hint =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()
                    } else {
                        null
                    }
                sawSensitiveLabel = DenyLists.matchesSensitiveField(
                    hint,
                    node.contentDescription?.toString(),
                    node.viewIdResourceName
                )
            }

            // isAccessibilityDataSensitive is API 34+ (UPSIDE_DOWN_CAKE) only.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                node.isAccessibilityDataSensitive
            ) {
                hasHiddenSensitiveNode = true
            }
        }
    }

    /**
     * Inclusion filter: must be visible to the user AND (interactive OR have text).
     * Off-screen / zero-area / invisible nodes are dropped so the model only sees
     * what it can actually act on.
     */
    private fun keep(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val interactive = node.isClickable ||
            node.isEditable ||
            node.isScrollable ||
            node.isLongClickable
        val hasText = !node.text.isNullOrBlank()
        if (!interactive && !hasText) return false

        // Reject degenerate / off-screen bounds.
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.width() > 0 && r.height() > 0
    }

    private fun AccessibilityNodeInfo.toUiElement(index: Int, rect: Rect): UiElement {
        val rawClass = className?.toString()
        return UiElement(
            index = index,
            role = simplifyRole(rawClass),
            text = text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = contentDescription?.toString()?.takeIf { it.isNotBlank() },
            hint = hintTextCompat()?.takeIf { it.isNotBlank() },
            resourceId = viewIdResourceName?.takeIf { it.isNotBlank() },
            bounds = rect,
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
            longClickable = isLongClickable,
            checkable = isCheckable,
            checked = isChecked,
            // Treat password AND password-variation inputTypes (incl. visible/number/web
            // password, which do NOT set isPassword) as a secret field. See §4.3.
            password = isPassword || hasPasswordInputType()
        )
    }

    /** `getHintText()` is API 26+; null on older platforms. */
    private fun AccessibilityNodeInfo.hintTextCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText?.toString() else null

    /** Collapse a fully-qualified class name to a short role token (e.g. "Button"). */
    private fun simplifyRole(className: String?): String {
        if (className.isNullOrBlank()) return "View"
        val simple = className.substringAfterLast('.')
        return simple.ifBlank { "View" }
    }

    /** `recycle()` is required pre-33 and a deprecated no-op on 33+. */
    private fun recycleIfNeeded(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            try {
                node.recycle()
            } catch (_: IllegalStateException) {
                // Already recycled by the framework; ignore.
            }
        }
    }

    companion object {
        /** Upper bound on elements per snapshot to stay inside the model's context. */
        const val MAX_ELEMENTS = 30
    }
}

/**
 * True when this node's [AccessibilityNodeInfo.getInputType] carries a password
 * variation that must be treated as a secret-entry field per ARCHITECTURE.md §4.3:
 * TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
 * TYPE_TEXT_VARIATION_WEB_PASSWORD, or TYPE_NUMBER_VARIATION_PASSWORD.
 *
 * This is the signal [AccessibilityNodeInfo.isPassword] MISSES: visiblePassword is
 * deliberately shown (so isPassword==false), and numberPassword is inconsistent across
 * OEMs. The variation bits are masked off the class+variation portion of inputType
 * (TYPE_MASK_CLASS | TYPE_MASK_VARIATION) so flags like TYPE_TEXT_FLAG_MULTI_LINE do not
 * perturb the comparison. `getInputType()` is API 19+ (always available here); any
 * framework error fails closed (treated as a password field).
 */
internal fun AccessibilityNodeInfo.hasPasswordInputType(): Boolean = try {
    val it = inputType
    if (it == 0) {
        false
    } else {
        val classAndVariation = it and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        when (classAndVariation) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }
} catch (_: Throwable) {
    // Fail closed: an inputType we cannot read is treated as a potential secret field.
    true
}
