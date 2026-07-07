package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `sms_draft` — opens the default SMS app composed to a number with a pre-filled body
 * via [Intent.ACTION_SENDTO] (`smsto:`) + the `sms_body` extra. It NEVER sends: the
 * user presses send. SAFE by design — silent sending (SmsManager) is BLOCKED. See
 * ARCHITECTURE.md §5.
 *
 * @param context context used to resolve the messaging app and start the activity.
 */
class SmsDraftTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "sms_draft",
        description = "Open the SMS app with a recipient and message pre-filled. Does NOT send; " +
            "the user presses send.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "number": { "type": "string", "description": "Recipient phone number." },
                "body": { "type": "string", "description": "Message text to pre-fill." }
              },
              "required": ["number", "body"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val number = (args["number"] as? String)?.trim()
        if (number.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'number'.")
        }
        val body = (args["body"] as? String) ?: ""

        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // NOTE: resolveActivity()/queryIntentActivities() are unreliable as a hard gate on
        // API 30+ (this app targets SDK 36) due to package-visibility filtering — they can
        // return null even when a default SMS app is installed. So we do NOT pre-check; the
        // try/catch around startActivity() is the real guard, converting the resulting
        // ActivityNotFoundException into a Failure result.
        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Drafted a message to $number. The user can review and send it.",
                data = mapOf("number" to number)
            )
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No messaging app available.")
            } else {
                ToolResult.Failure(
                    declaration.name, callId,
                    "Could not open messaging app: ${it.message}"
                )
            }
        }
    }
}
