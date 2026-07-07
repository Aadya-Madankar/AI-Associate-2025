package com.example.agent.tools

import android.os.Build
import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.permission.DefaultSecureContextDetector
import com.example.permission.SecureReason
import com.example.security.ScreenRedactor

/**
 * `take_screenshot` — captures the screen via the AccessibilityService
 * `takeScreenshot()` API (available on API 30+, ARCHITECTURE.md §3.2).
 *
 * Returns only a success/failure note: the raw image is not handed back through this
 * tool result; the capture exists so an on-device check or upstream vision path can use
 * the frame.
 *
 * SECURE-CONTEXT GUARD (ARCHITECTURE.md §4.3, §5 — defense-in-depth, mirrors
 * [com.example.agent.tools.InputTextTool]): the framework `takeScreenshot()` only fails
 * on FLAG_SECURE windows, which are opt-in by the foreground app and therefore do NOT
 * cover the secure contexts the permission model treats as hard blocks. So BEFORE
 * capturing, this tool reads the live screen and runs [DefaultSecureContextDetector].
 * It refuses ([ToolResult.Failure]) whenever the verdict is any hard-block
 * [SecureReason] (DENYLISTED_APP, FLAG_SECURE_WINDOW, PASSWORD_FIELD, OTP_OR_CARD_FIELD,
 * KEYGUARD) or the screen is otherwise [ScreenState.secure]. A screenshot only succeeds
 * on non-secure, non-sensitive content; the raw pixels of a banking/wallet/authenticator
 * app, a password/OTP/CVV/PIN screen, or the lockscreen are never captured here. The
 * framework FLAG_SECURE-null behaviour is kept only as a last-resort backstop.
 */
class TakeScreenshotTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "take_screenshot",
        description = "Capture a screenshot of the current screen (Android 11+ only). Returns " +
            "only whether the capture succeeded — the image is not sent back. Refuses to " +
            "capture secure or sensitive screens (banking/wallet/authenticator apps, " +
            "password/OTP/CVV/PIN fields, FLAG_SECURE windows, or the lockscreen); succeeds " +
            "only on non-sensitive content. Use sparingly; it is also rate-limited.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {}
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Screenshots require Android 11 (API 30) or newer; this device is " +
                    "API ${Build.VERSION.SDK_INT}."
            )
        }

        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot take a screenshot."
            )

        // Defense-in-depth secure-context guard (ARCHITECTURE.md §4.3, §5), mirroring
        // InputTextTool: the framework only nulls out FLAG_SECURE windows, so read the
        // LIVE screen and run the detector before capturing any pixels. Refuse on any
        // hard-block reason (denylisted app, FLAG_SECURE window, password / OTP / card
        // field, keyguard) or any otherwise-secure screen.
        //
        // The gate is run on the RAW (redact = false) read so the detector's value
        // backstop can match an unlabeled displayed OTP/PAN/IBAN on verbatim text — once
        // redacted, the value is the placeholder and the backstop can no longer see it.
        // The raw screen is NEVER returned to the model: on refusal we hand back the
        // redacted copy.
        val before = controller.readScreen(redact = false)
        val reason = DefaultSecureContextDetector().inspect(before)
        if (before.secure || reason in HARD_BLOCK_REASONS) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Refusing to screenshot a secure/sensitive screen.",
                screen = ScreenRedactor.INSTANCE.redact(before)
            )
        }

        val bitmap = controller.takeScreenshot()
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Screenshot failed (secure screen, rate-limited, or unavailable)."
            )

        // The image is intentionally not forwarded to the model; recycle and report only.
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        return ToolResult.Success(
            toolName = declaration.name,
            callId = callId,
            message = "Captured a ${width}x${height} screenshot of a non-sensitive screen; " +
                "the image is not returned through this result. Secure/denylisted/password/" +
                "OTP/keyguard screens are refused, not captured.",
            data = mapOf("captured" to true, "width" to width, "height" to height)
        )
    }

    private companion object {
        /**
         * Hard-block [SecureReason]s a screenshot must refuse, matching the BLOCKED tier in
         * RiskClassifier.contextTier. KEYGUARD is included for completeness even though the
         * tool-layer detector cannot observe the keyguard flag through the controller
         * contract — a locked screen surfaces here as [ScreenState.secure] / a denylisted or
         * secure window. [SecureReason.NONE] (and any non-hard-block reason) is allowed.
         */
        private val HARD_BLOCK_REASONS = setOf(
            SecureReason.DENYLISTED_APP,
            SecureReason.FLAG_SECURE_WINDOW,
            SecureReason.PASSWORD_FIELD,
            SecureReason.OTP_OR_CARD_FIELD,
            SecureReason.KEYGUARD
        )
    }
}
