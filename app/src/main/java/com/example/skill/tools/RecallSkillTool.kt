package com.example.skill.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.skill.SkillStore

/**
 * `recall_skill` — looks up a previously saved [com.example.skill.Skill] by name and returns
 * its ordered steps so the MODEL can re-issue each one as a normal, permission-gated tool call.
 *
 * This tool intentionally does NOT execute anything itself: replaying through the model keeps
 * every replayed action subject to the same permission gating as a fresh request. On success the
 * returned [ToolResult.Success.data] carries:
 * ```
 * {
 *   "name": "<skill name>",
 *   "description": "<skill description>",
 *   "createdAtMs": <epoch ms>,
 *   "steps": [ { "tool": "<tool>", "args": { ... } }, ... ]
 * }
 * ```
 * If no skill matches the requested name, a [ToolResult.Failure] is returned.
 *
 * Storage is entirely on-device via the injected [SkillStore] (see [com.example.skill.JsonFileSkillStore]).
 *
 * @param store on-device skill memory to recall from.
 */
class RecallSkillTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "recall_skill",
        description = "Recall a previously saved skill by name and return its ordered tool steps " +
            "so you can re-issue each one yourself as a normal tool call. Returns the step list; " +
            "it does not run anything. Fails if no skill with that name exists. Use list_skills first " +
            "if you are unsure of the exact name.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Exact name of the skill to recall."
                }
              },
              "required": ["name"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val name = (args["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'name'.")
        }

        val skill = runCatching { store.get(name) }.getOrNull()
            ?: return ToolResult.Failure(
                declaration.name,
                callId,
                "No skill named \"$name\". Use list_skills to see what is available."
            )

        val steps = skill.steps.map { step ->
            mapOf(
                "tool" to step.tool,
                "args" to step.args
            )
        }

        return ToolResult.Success(
            toolName = declaration.name,
            callId = callId,
            message = "Recalled skill \"${skill.name}\" with ${steps.size} step(s). " +
                "Re-issue each step as its own tool call, in order.",
            data = mapOf(
                "name" to skill.name,
                "description" to skill.description,
                "createdAtMs" to skill.createdAtMs,
                "steps" to steps
            )
        )
    }
}
