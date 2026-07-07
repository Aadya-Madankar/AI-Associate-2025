package com.example.agent.tools

import android.content.Context
import android.content.Intent
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `share` — shares plain text through the system chooser ([Intent.ACTION_SEND] wrapped
 * in [Intent.createChooser]). The user picks the target app and confirms the send there,
 * so this draft-style action is SAFE. See ARCHITECTURE.md §5.
 *
 * @param context context used to start the chooser activity.
 */
class ShareTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "share",
        description = "Share some text via the Android share sheet (the user picks the destination app).",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "text": { "type": "string", "description": "The text to share." },
                "subject": { "type": "string", "description": "Optional subject/title for the share." }
              },
              "required": ["text"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val text = (args["text"] as? String)?.trim()
        if (text.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'text'.")
        }
        val subject = (args["subject"] as? String)?.trim()

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!subject.isNullOrEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(send, subject ?: "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // The system chooser is always launchable; if no SEND target exists it surfaces an
        // empty state, and a genuine failure throws ActivityNotFoundException (caught below).
        return runCatching {
            context.startActivity(chooser)
            ToolResult.Success(declaration.name, callId, "Opened the share sheet.")
        }.getOrElse {
            if (it is android.content.ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No app available to share text.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not open share sheet: ${it.message}")
            }
        }
    }
}
