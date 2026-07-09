package com.example.memory.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.memory.IntentionTrigger
import com.example.memory.MemoryStore

/**
 * `note_intention` — prospective memory: "remember to do X (when Y)". Stored on-device and
 * surfaced back to XENO in a later session (pending intentions are folded into the boot context).
 * SAFE: local DB only, reversible via `forget`.
 *
 * ponytail: v1 surfaces pending/due intentions at connect; live APP_OPEN/TOPIC trigger firing
 * mid-session is not wired yet — add an app-foreground hook when it earns its keep.
 */
class NoteIntentionTool(private val store: MemoryStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "note_intention",
        description = "Note something to remember to do later (\"next time, remind me to water the " +
            "plants\", \"follow up on the visa form\"). You'll be reminded of pending intentions when " +
            "a new session starts.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "text": { "type": "string", "description": "What to remember to do, in natural language." },
                "trigger": { "type": "string", "enum": ["time", "app_open", "topic", "manual"], "description": "What should bring it up (default manual: surfaced next session)." },
                "trigger_value": { "type": "string", "description": "For app_open: a package name; for topic: a keyword." },
                "due_at_ms": { "type": "integer", "description": "For time triggers: epoch-millis when it becomes due." }
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
        val trigger = when ((args["trigger"] as? String)?.trim()?.lowercase()) {
            "time" -> IntentionTrigger.TIME
            "app_open" -> IntentionTrigger.APP_OPEN
            "topic" -> IntentionTrigger.TOPIC
            else -> IntentionTrigger.MANUAL
        }
        val ok = store.noteIntention(
            text = text,
            trigger = trigger,
            triggerValue = args["trigger_value"] as? String,
            dueAt = (args["due_at_ms"] as? Number)?.toLong()
        )
        return if (ok) {
            ToolResult.Success(declaration.name, callId, "Noted. I'll remember to do that.")
        } else {
            ToolResult.Failure(declaration.name, callId, "Could not note that.")
        }
    }
}
