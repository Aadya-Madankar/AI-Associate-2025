package com.example.memory.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.memory.MemoryStore

/**
 * `forget` — remove a fact or cancel an intention when the user corrects XENO or asks it to
 * forget something. Facts are soft-deleted (recoverable), so a mistaken forget is not data loss.
 * SAFE: local DB only.
 */
class ForgetMemoryTool(private val store: MemoryStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "forget",
        description = "Forget a stored memory when the user corrects you or asks you to. Pass the " +
            "fact/intention id you got from recall, or \"subject:key\" (e.g. \"user:wife_name\").",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "ref": { "type": "string", "description": "The memory id, or \"subject:key\" of the fact to forget." }
              },
              "required": ["ref"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val ref = (args["ref"] as? String)?.trim()
        if (ref.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'ref'.")
        }
        val ok = store.forget(ref)
        return if (ok) {
            ToolResult.Success(declaration.name, callId, "Forgotten.")
        } else {
            ToolResult.Failure(declaration.name, callId, "Nothing matched \"$ref\".")
        }
    }
}
