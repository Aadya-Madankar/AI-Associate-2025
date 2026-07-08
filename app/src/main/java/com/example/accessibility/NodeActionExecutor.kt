package com.example.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.security.SensitivePatterns

/**
 * Performs an index-targeted UI action (click / set-text / scroll / long-press)
 * against the live accessibility tree, preferring the semantic
 * [AccessibilityNodeInfo.performAction] path and falling back to a coordinate
 * gesture at the element's bounds center via [GestureDispatcher] when the node
 * refuses the action (returns false) — the common case on Compose, WebView and
 * canvas surfaces. See ARCHITECTURE.md §3.2.
 *
 * The executor does not own the tree: callers pass a fresh root for every action so
 * indices match the most recent [ScreenReader.read]. The matching node is located by
 * its absolute screen bounds (from [ScreenReader.boundsFor]) since node identity is
 * not stable across reads.
 */
class NodeActionExecutor(
    private val screenReader: ScreenReader,
    private val gestures: GestureDispatcher
) {

    /**
     * Click the element at [index]: try `ACTION_CLICK`, else tap the bounds center.
     * Returns false if the index is unknown or both paths fail.
     */
    suspend fun click(root: AccessibilityNodeInfo?, index: Int): Boolean {
        val rect = screenReader.boundsFor(index) ?: return false
        val node = findByBounds(root, rect)
        if (node != null) {
            try {
                if (node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
            } catch (_: Throwable) {
                // Stale node (window changed between read and act): fall through to gesture.
            } finally {
                if (node !== root) recycleIfNeeded(node)
            }
        }
        return tapCenter(rect)
    }

    /**
     * Long-press the element at [index]: try `ACTION_LONG_CLICK`, else a long gesture
     * at the bounds center.
     */
    suspend fun longPress(root: AccessibilityNodeInfo?, index: Int): Boolean {
        val rect = screenReader.boundsFor(index) ?: return false
        val node = findByBounds(root, rect)
        if (node != null) {
            try {
                if (node.isLongClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ) {
                    return true
                }
            } catch (_: Throwable) {
                // Stale node (window changed between read and act): fall through to gesture.
            } finally {
                if (node !== root) recycleIfNeeded(node)
            }
        }
        return gestures.longPress(rect.centerX(), rect.centerY())
    }

    /**
     * Set [text] into the editable element at [index] via `ACTION_SET_TEXT`. Refuses
     * password fields (never type secrets through the agent). Falls back to tapping
     * the field to focus it if the set-text action is rejected — callers should then
     * re-read and retry, since blind coordinate typing is not possible.
     */
    suspend fun setText(root: AccessibilityNodeInfo?, index: Int, text: String): Boolean {
        // SECURITY (fail-closed, ARCHITECTURE.md §4.3 / §5): the executor is the last gate
        // and it sees the WHOLE tree's secure scan, not the trimmed+capped element list the
        // upstream detector keys off. If the last read saw ANY password / visible-password /
        // number-password field anywhere on the active window — even one dropped off-screen,
        // zero-area, or past the MAX_ELEMENTS budget — refuse to type regardless of which
        // index was targeted. This closes the over-cap / off-screen password side channel
        // that ScreenState.elements alone cannot carry to the index-based gate.
        if (screenReader.sawPasswordField()) return false
        val rect = screenReader.boundsFor(index) ?: return false
        val node = findByBounds(root, rect) ?: return false
        // Hard secrets boundary: refuse password fields AND OTP/CVV/PIN/card/IBAN-shaped
        // fields (which are usually NOT isPassword — plain number inputType). Mirrors the
        // SecureReason.OTP_OR_CARD_FIELD contract enforced by DefaultSecureContextDetector.
        // node.isPassword is NOT set for visiblePassword/numberPassword fields, so also
        // inspect the raw inputType variation (hasPasswordInputType) — the same check the
        // reader applies when populating UiElement.password / sawPasswordField.
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else {
            null
        }
        if (node.isPassword ||
            node.hasPasswordInputType() ||
            SensitivePatterns.matchesSensitiveLabel(
                node.viewIdResourceName,
                hint,
                node.contentDescription?.toString(),
                node.text?.toString()
            )
        ) {
            if (node !== root) recycleIfNeeded(node)
            return false
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val applied = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            // Stale node (window changed between read and act): treat as not applied.
            false
        } finally {
            if (node !== root) recycleIfNeeded(node)
        }
        if (applied) {
            return true
        }
        // Could not set text directly. Focus the field as a side-effect so a later
        // re-read + retry may succeed, but report failure: focusing is not typing.
        tapCenter(rect)
        return false
    }

    /**
     * Scroll the element at [index] forward or backward. Tries the scroll actions and,
     * when unsupported, falls back to a swipe gesture across the element's bounds.
     */
    suspend fun scroll(root: AccessibilityNodeInfo?, index: Int, forward: Boolean): Boolean {
        val rect = screenReader.boundsFor(index) ?: return false
        val node = findByBounds(root, rect)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (node != null) {
            try {
                if (node.isScrollable && node.performAction(action)) {
                    return true
                }
            } catch (_: Throwable) {
                // Stale node (window changed between read and act): fall through to swipe.
            } finally {
                if (node !== root) recycleIfNeeded(node)
            }
        }
        return swipeWithin(rect, forward)
    }

    /** Tap an absolute coordinate via gesture; used for raw `tapAt` and as fallback. */
    suspend fun tapCenter(rect: Rect): Boolean =
        gestures.tap(rect.centerX(), rect.centerY())

    /** Vertical swipe contained within [rect]; forward = swipe up (content scrolls up). */
    private suspend fun swipeWithin(rect: Rect, forward: Boolean): Boolean {
        val x = rect.centerX()
        val top = rect.top + (rect.height() * 0.2f).toInt()
        val bottom = rect.bottom - (rect.height() * 0.2f).toInt()
        return if (forward) {
            gestures.swipe(x, bottom, x, top)
        } else {
            gestures.swipe(x, top, x, bottom)
        }
    }

    /**
     * Locate the node whose on-screen bounds exactly match [target]. Bounds are the
     * only stable key across a re-walk because node objects are not reusable across
     * reads. Returns null if no exact match is found (caller falls back to gesture).
     */
    private fun findByBounds(root: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        root ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val tmp = Rect()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val matched = try {
                node.getBoundsInScreen(tmp)
                tmp == target
            } catch (_: Throwable) {
                // Stale node: skip it (and don't enqueue its children).
                if (node !== root) recycleIfNeeded(node)
                continue
            }
            if (matched) {
                // The match is returned to the caller, which recycles it after acting.
                // Drain and recycle everything still pending in the stack.
                while (stack.isNotEmpty()) recycleIfNeeded(stack.removeLast())
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
            // Done with this node; recycle it (never the caller-owned root).
            if (node !== root) recycleIfNeeded(node)
        }
        return null
    }

    /**
     * `recycle()` is required pre-33 to return the [AccessibilityNodeInfo] to the
     * framework pool and is a deprecated no-op on 33+. Mirrors [ScreenReader]'s helper.
     */
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
}
