package com.example.agent.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `task_complete` — the explicit terminator of the plan-act-observe loop
 * (ARCHITECTURE.md §3.4). The model calls this once the user's request is fully
 * handled (or it has determined it cannot proceed), supplying a `success` flag and a
 * short natural-language `summary` the persona can narrate back.
 *
 * Returns [ToolResult.Completed], which the agent loop treats as the stop signal.
 * This tool touches no UI and therefore needs no AccessibilityController.
 */
class TaskCompleteTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "task_complete",
        description = "Call this exactly once when the user's task is finished (or cannot be " +
            "completed) to end the agent loop. Provide whether it succeeded and a short " +
            "summary of what happened.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "success": {
      "type": "boolean",
      "description": "true if the task was completed successfully, false otherwise."
    },
    "summary": {
      "type": "string",
      "description": "A short, user-facing summary of the outcome."
    }
  },
  "required": [
    "success",
    "summary"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val success = args.boolArg("success") ?: false
        val summary = (args["summary"] as? String)?.takeIf { it.isNotBlank() }
            ?: if (success) "Task completed." else "Task could not be completed."

        return ToolResult.Completed(
            toolName = declaration.name,
            callId = callId,
            success = success,
            summary = summary
        )
    }
}

/** Coerce a loosely-typed function-call argument to [Boolean] (tolerates "true"/"false"). */
private fun Map<String, Any?>.boolArg(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> when (v.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}
