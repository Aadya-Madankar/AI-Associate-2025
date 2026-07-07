package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.accessibility.UiElement
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.permission.DefaultSecureContextDetector
import com.example.permission.DenyLists
import com.example.permission.SecureReason
import com.example.security.ScreenRedactor

/**
 * `input_text` — types `text` into the editable element at `index`.
 *
 * Hard safety boundary (ARCHITECTURE.md §4.3, §5): refuses if the target element is a
 * password / OTP / secure field (`UiElement.password`). We never type into, nor leak
 * the contents of, secure fields. The check is performed against a fresh screen read
 * so the decision reflects the live UI rather than a stale snapshot.
 */
class InputTextTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "input_text",
        description = "Type text into the editable element at the given index from the most " +
            "recent get_screen result. Refuses to type into password / OTP / secure fields. " +
            "Returns the screen after typing.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "The editable element index from the latest get_screen result."
    },
    "text": {
      "type": "string",
      "description": "The text to type into the field."
    }
  },
  "required": [
    "index",
    "text"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot input text."
            )

        val index = args.intArg("index")
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'index' argument."
            )
        val text = args["text"] as? String
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'text' argument."
            )

        // Re-read so the secure-context decision reflects the live screen, then refuse on
        // any hard-block reason (denylisted app, FLAG_SECURE window, password field,
        // OTP/CVV/PIN/card field, keyguard) before any text could reach the field.
        //
        // The gate is run on the RAW (redact = false) read so the detector's value
        // backstop can match an unlabeled displayed OTP/PAN/IBAN on verbatim text; once
        // redacted that value is the placeholder and the backstop can no longer see it.
        // The raw screen is NEVER returned to the model — we hand back [redactedBefore].
        val before = controller.readScreen(redact = false)
        val redactedBefore = ScreenRedactor.INSTANCE.redact(before)
        val target = before.elements.firstOrNull { it.index == index }
        if (target == null) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "No element at index $index on the current screen.",
                screen = redactedBefore
            )
        }
        val reason = DefaultSecureContextDetector().inspect(before)
        when (reason) {
            SecureReason.DENYLISTED_APP,
            SecureReason.FLAG_SECURE_WINDOW,
            SecureReason.PASSWORD_FIELD,
            SecureReason.OTP_OR_CARD_FIELD,
            SecureReason.KEYGUARD ->
                return ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Refusing to type into element $index: secure context ($reason).",
                    screen = redactedBefore
                )
            else -> Unit
        }

        // Defense-in-depth (fail-closed, ARCHITECTURE.md §4.3): the screen-wide detector
        // can pass when the *target* field is the secret one and the rest of the screen is
        // benign. Refuse if the resolved target shows any secret evidence — it is a
        // password/visible-password/number-password field (UiElement.password, now derived
        // from inputType as well as isPassword) or its own text/hint/desc/resource-id
        // matches the sensitive-field pattern (OTP/CVV/PIN/card/IBAN). We never type into
        // an editable input that looks secret-shaped, even if it dodged the keyword regex
        // on the rest of the screen.
        if (isSecretShaped(target)) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Refusing to type into element $index: target looks like a secret field.",
                screen = redactedBefore
            )
        }

        val ok = controller.inputText(index, text)
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Entered text into element $index.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Failed to set text on element $index.",
                screen = screen
            )
        }
    }
}

/**
 * True if [el] is the kind of editable input we must never type into: a password /
 * visible-password / number-password field (carried on [UiElement.password], which the
 * reader now derives from the node inputType in addition to isPassword), or an editable
 * field whose own text / hint / content-description / resource-id matches the
 * sensitive-field pattern (OTP / CVV / PIN / card-number / IBAN). Fail-closed for the
 * input_text path: a secret-shaped target is refused even when the screen-wide gate
 * passed because every other element on screen was benign.
 */
private fun isSecretShaped(el: UiElement): Boolean {
    if (el.password) return true
    if (!el.editable) return false
    return DenyLists.matchesSensitiveField(
        el.text,
        el.hint,
        el.contentDescription,
        el.resourceId
    )
}

/** Coerce a loosely-typed function-call argument to [Int] (Gemini may send Long/Double/String). */
private fun Map<String, Any?>.intArg(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}
