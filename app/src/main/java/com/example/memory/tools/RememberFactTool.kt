package com.example.memory.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.memory.FactSource
import com.example.memory.MemoryStore
import com.example.memory.Sensitivity

/**
 * `remember` — writes a durable fact into XENO's on-device semantic memory ("the user's wife is
 * Priya", "prefers Marathi for jokes", "works at Acme"). Upserts by (subject, key) so a new value
 * for the same key supersedes the old. SAFE: local DB only, reversible via `forget`.
 *
 * PRIVACY: mark anything sensitive so it stays on the device — a PRIVATE/SECRET fact is never
 * embedded to the cloud, never returned by `recall`, and never folded into the prompt. Pass
 * `sensitivity: "secret"` for passwords/OTP/financial and `"private"` for other personal data.
 * (Better still: don't store raw secrets at all.)
 */
class RememberFactTool(private val store: MemoryStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "remember",
        description = "Save a durable fact about the user or their world to your on-device memory " +
            "so you recall it in future sessions (e.g. names, preferences, relationships, routines). " +
            "Use it whenever you learn something worth keeping. Never store passwords/OTPs; if a fact " +
            "is sensitive, set sensitivity so it stays private.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "text": { "type": "string", "description": "The fact in natural language, e.g. \"The user's wife is named Priya\"." },
                "subject": { "type": "string", "description": "Who/what it is about: \"user\" (default), or \"person:priya\", \"app:whatsapp\", \"place:office\"." },
                "key": { "type": "string", "description": "Optional short key so a later value overwrites this one, e.g. \"wife_name\", \"favorite_food\"." },
                "value": { "type": "string", "description": "Optional structured value for the key, e.g. \"Priya\"." },
                "sensitivity": { "type": "string", "enum": ["normal", "private", "secret"], "description": "normal is recalled and folded into future prompts; private/secret stay on-device only (never recalled or injected)." }
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
        val sensitivity = when ((args["sensitivity"] as? String)?.trim()?.lowercase()) {
            "secret" -> Sensitivity.SECRET
            "private" -> Sensitivity.PRIVATE
            else -> Sensitivity.NORMAL
        }
        val ok = store.remember(
            subject = args["subject"] as? String,
            factKey = args["key"] as? String,
            text = text,
            value = args["value"] as? String,
            sensitivity = sensitivity,
            source = FactSource.MODEL_ASSERTED
        )
        return if (ok) {
            ToolResult.Success(declaration.name, callId, "Remembered.")
        } else {
            ToolResult.Failure(declaration.name, callId, "Could not save that memory.")
        }
    }
}
