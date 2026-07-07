package com.example.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `dial` — opens the phone dialer pre-filled with a number via [Intent.ACTION_DIAL]
 * (`tel:`). It deliberately does NOT place the call: the user must press the call
 * button themselves. SAFE by design — using ACTION_CALL would be GUARDED. See
 * ARCHITECTURE.md §5.
 *
 * @param context context used to resolve the dialer and start the activity.
 */
class DialPrefillTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "dial",
        description = "Open the phone dialer pre-filled with a number. Does NOT place the call; " +
            "the user taps call. Use this instead of placing a call directly.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "number": { "type": "string", "description": "Phone number to pre-fill, e.g. +14155550100" }
              },
              "required": ["number"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val number = (args["number"] as? String)?.trim()
        if (number.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'number'.")
        }

        // tel: requires the raw number be URL-encoded so '+', '#', '*' survive.
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Failure(declaration.name, callId, "No dialer app available.")
        }

        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Dialer ready with $number. Press call to connect.",
                data = mapOf("number" to number)
            )
        }.getOrElse {
            ToolResult.Failure(declaration.name, callId, "Could not open dialer: ${it.message}")
        }
    }
}
