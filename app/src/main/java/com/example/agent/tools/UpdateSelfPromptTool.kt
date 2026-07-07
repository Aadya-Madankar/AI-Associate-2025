package com.example.agent.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.config.PersonaStore

/**
 * `update_self_prompt` — lets XENO **edit its own prompt**. XENO writes a short directive to
 * itself ("always greet in Hindi", "be more concise with this user", "prefer Marathi for jokes")
 * which is persisted on-device by [PersonaStore] and folded into XENO's system instruction on the
 * next session. This is how XENO grows: it can revise how it behaves whenever it decides to.
 *
 * SAFE: it writes a single local DataStore string, reversible (overwrite or clear), and nothing
 * leaves the device. It grants no new authority — it only shapes XENO's own future wording.
 *
 * Because the Live API fixes the system instruction at connect, a note written now takes full
 * effect when the session next starts; the success message says so.
 *
 * Args (model JSON):
 * ```
 * { "directive": "Always open the conversation in Hindi.", "mode": "append" }   // mode optional
 * ```
 */
class UpdateSelfPromptTool(private val store: PersonaStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "update_self_prompt",
        description = "Edit your OWN persistent prompt. Save a short directive to yourself about " +
            "how you should behave or speak (e.g. 'always greet in Hindi', 'be more concise'). It " +
            "is stored only on this phone and shapes how you act from the next session on. Use " +
            "mode 'append' to add a note (default) or 'replace' to rewrite your whole note.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "directive": { "type": "string", "description": "The instruction to yourself, in one or two sentences." },
                "mode": { "type": "string", "enum": ["append", "replace"], "description": "append (default) adds a note; replace rewrites your whole self-note." }
              },
              "required": ["directive"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val directive = (args["directive"] as? String)?.trim()
        if (directive.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'directive'.")
        }
        val mode = (args["mode"] as? String)?.trim()?.lowercase()

        return try {
            if (mode == "replace") store.replace(directive) else store.append(directive)
            ToolResult.Success(
                declaration.name, callId,
                "Saved that to my own prompt — it'll shape how I act from our next session.",
                data = mapOf("mode" to (mode ?: "append"))
            )
        } catch (e: Exception) {
            ToolResult.Failure(declaration.name, callId, "Could not update self-prompt: ${e.message}")
        }
    }
}
