package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `email_draft` — opens an email composer pre-filled with recipient, subject and body
 * via [Intent.ACTION_SENDTO] (`mailto:`). It NEVER sends: the user presses send. Using
 * ACTION_SENDTO with a mailto: URI means only true email apps are offered.
 *
 * The 'to' address is validated to be a single bare email address before it is placed in
 * the mailto: opaque part, so it cannot smuggle extra `?cc=`/`?bcc=`/`?body=` parameters
 * (which Android's mailto: parser would otherwise honour) past the user-review gate.
 * See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve the email app and start the activity.
 */
class EmailDraftTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "email_draft",
        description = "Open an email composer pre-filled with recipient, subject and body. Does NOT " +
            "send; the user presses send.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "to": { "type": "string", "description": "Recipient email address." },
                "subject": { "type": "string", "description": "Email subject." },
                "body": { "type": "string", "description": "Email body." }
              },
              "required": ["to"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val to = (args["to"] as? String)?.trim()
        if (to.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'to' address.")
        }
        // Require a single bare email address. Rejecting '?', '&', commas, whitespace and
        // newlines prevents smuggling hidden cc/bcc/body params through the mailto: opaque
        // part, where Android's mailto: parser would otherwise honour them unseen by the user.
        if (!SINGLE_ADDRESS.matches(to)) {
            return ToolResult.Failure(
                declaration.name, callId,
                "Invalid 'to' address; provide a single email address with no extra parameters."
            )
        }
        val subject = (args["subject"] as? String) ?: ""
        val body = (args["body"] as? String) ?: ""

        // mailto: with the (validated) address as the opaque part; subject/body go in extras.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", to, null)).apply {
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Note: we deliberately do NOT pre-check resolveActivity() here. On targetSdk >= 30
        // (Android 11+ package-visibility filtering) it returns null whenever the manifest
        // lacks a matching <queries> entry even though an email app is installed, which would
        // wrongly short-circuit. Instead we attempt startActivity() and treat
        // ActivityNotFoundException as the genuine 'no email app' case.
        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Drafted an email to $to. The user can review and send it.",
                data = mapOf("to" to to)
            )
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No email app available.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not open email app: ${it.message}")
            }
        }
    }

    private companion object {
        /** A single RFC-5322-ish address: no whitespace, '?', '&' or ',' anywhere. */
        private val SINGLE_ADDRESS = Regex("^[^@\\s,?&]+@[^@\\s,?&]+\\.[^@\\s,?&]+$")
    }
}
