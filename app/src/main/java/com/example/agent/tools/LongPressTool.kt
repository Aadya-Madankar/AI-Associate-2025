package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.permission.DefaultSecureContextDetector
import com.example.permission.SecureReason
import com.example.security.ScreenRedactor

/**
 * `long_press` — long-presses the element at `index` from the most recent
 * `get_screen` read (ARCHITECTURE.md §3.2), e.g. to open a context menu or enter
 * selection mode.
 *
 * Hard safety boundary (ARCHITECTURE.md §4.3, §5): a long-press on a text field is the
 * canonical trigger for Android's text-selection toolbar / "Show password" affordance,
 * a known secret-exfiltration vector. So, mirroring [InputTextTool] and
 * `NodeActionExecutor.setText`, this tool self-gates against the *live* screen rather
 * than relying on any upstream permission gate: it re-reads, refuses when the target is
 * a password / secure field, and hard-blocks in any secure context flagged by
 * [DefaultSecureContextDetector]. After acting it re-inspects the resulting screen and
 * withholds (redacts) the element tree before returning so sensitive text revealed by a
 * selection/menu never leaves the device.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] so the model can observe
 * any menu/selection UI that appears (redacted when the screen is secure).
 */
class LongPressTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "long_press",
        description = "Long-press the on-screen element at the given index from the most recent " +
            "get_screen result, e.g. to open a context menu. Returns the screen afterward.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "The element index from the latest get_screen result."
    }
  },
  "required": [
    "index"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot long-press."
            )

        val index = ToolArgs.intArg(args, "index")
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'index' argument."
            )

        // Re-read so the secure-context decision reflects the live screen, then refuse
        // before touching anything. This makes correctness independent of integration
        // order (no upstream gate is guaranteed to run before us).
        //
        // The gate is run on the RAW (redact = false) read so the detector's value
        // backstop can match an unlabeled displayed OTP/PAN/IBAN on verbatim text; once
        // redacted that value is the placeholder and the backstop can no longer see it.
        // The raw screen is NEVER returned to the model — every returned screen below is
        // either element-stripped (secure paths) or a redacted copy.
        val detector = DefaultSecureContextDetector()
        val before = controller.readScreen(redact = false)
        val target = before.elements.firstOrNull { it.index == index }
        if (target == null) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "No element at index $index on the current screen.",
                screen = ScreenRedactor.INSTANCE.redact(before)
            )
        }
        if (target.password) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Refusing to long-press element $index: it is a password / secure field.",
                screen = ScreenRedactor.INSTANCE.redact(before)
            )
        }
        val beforeReason = detector.inspect(before)
        if (beforeReason != SecureReason.NONE) {
            // Denylisted app, FLAG_SECURE window, OTP/card field, etc. — long-press in a
            // secure context is hard-blocked (it can trigger selection / "Show password").
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Refusing to long-press element $index: secure context ($beforeReason).",
                screen = before.copy(secure = true, elements = emptyList())
            )
        }

        val ok = controller.longPress(index)

        // The post-action screen may now contain selected/revealed sensitive text or a
        // selection toolbar. Re-inspect and never return a raw secure tree off-device.
        val after = controller.readScreen()
        val afterReason = detector.inspect(after)
        val screen = if (afterReason != SecureReason.NONE) {
            after.copy(secure = true, elements = emptyList())
        } else {
            after
        }
        val withheldNote = if (afterReason != SecureReason.NONE) {
            " The resulting screen was withheld for security ($afterReason)."
        } else {
            ""
        }
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Long-pressed element $index.$withheldNote",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Long-press on element $index failed (element gone or not long-clickable).$withheldNote",
                screen = screen
            )
        }
    }
}
