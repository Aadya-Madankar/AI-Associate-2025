package com.example.skill.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.skill.Skill
import com.example.skill.SkillStep
import com.example.skill.SkillStore

/**
 * `save_skill` — teaches Nazim a reusable, named "skill": an ordered list of tool
 * calls (each a tool name plus its argument map) that the model can later recall and
 * replay. Everything is persisted on-device by the injected [SkillStore]
 * (a [com.example.skill.JsonFileSkillStore] writing `nazim_skills.json` in the app's
 * private files dir) — no database, no cloud. See ARCHITECTURE.md §5.
 *
 * SAFE: this tool only records intent. Recalling a skill re-issues each step as a
 * normal, permission-gated tool call, so saving one grants no extra authority.
 *
 * Argument shape (model JSON, numbers arrive as Double — handled defensively):
 * ```
 * {
 *   "name": "morning routine",
 *   "description": "Open maps then start my commute playlist",
 *   "steps": [
 *     { "tool": "open_app", "args": { "package": "com.google.android.apps.maps" } },
 *     { "tool": "media_play_pause", "args": {} }
 *   ]
 * }
 * ```
 *
 * @param store on-device skill memory the saved [Skill] is written to.
 */
class SaveSkillTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "save_skill",
        description = "Save a named, reusable skill: an ordered list of tool calls (each with a " +
            "tool name and its arguments) so it can be recalled and replayed later. " +
            "Use after performing a multi-step task the user may want to repeat.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string", "description": "Short unique name for the skill. Saving with an existing name overwrites it." },
                "description": { "type": "string", "description": "What the skill does, in one sentence." },
                "steps": {
                  "type": "array",
                  "description": "Ordered tool calls that make up the skill.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": { "type": "string", "description": "Name of the tool to invoke for this step." },
                      "args": { "type": "object", "description": "Arguments for the tool, as a JSON object." }
                    },
                    "required": ["tool"]
                  }
                }
              },
              "required": ["name", "description", "steps"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val name = (args["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'name'.")
        }
        val description = (args["description"] as? String)?.trim().orEmpty()

        val rawSteps = args["steps"] as? List<*>
        if (rawSteps == null) {
            return ToolResult.Failure(declaration.name, callId, "'steps' must be a list of { tool, args } objects.")
        }

        val steps = ArrayList<SkillStep>(rawSteps.size)
        for ((index, raw) in rawSteps.withIndex()) {
            val stepMap = raw as? Map<*, *>
                ?: return ToolResult.Failure(
                    declaration.name, callId,
                    "Step ${index + 1} must be an object with a 'tool' field."
                )
            val tool = (stepMap["tool"] as? String)?.trim()
            if (tool.isNullOrEmpty()) {
                return ToolResult.Failure(
                    declaration.name, callId,
                    "Step ${index + 1} is missing 'tool'."
                )
            }
            steps += SkillStep(tool = tool, args = asArgsMap(stepMap["args"]))
        }

        if (steps.isEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "A skill needs at least one step.")
        }

        return try {
            store.save(
                Skill(
                    name = name,
                    description = description,
                    steps = steps,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            ToolResult.Success(
                declaration.name, callId,
                "Saved skill '$name'.",
                data = mapOf("name" to name, "steps" to steps.size)
            )
        } catch (e: Exception) {
            ToolResult.Failure(declaration.name, callId, "Could not save skill: ${e.message}")
        }
    }

    /**
     * Coerce a step's `args` payload into a [Map] keyed by String. The model sends a JSON
     * object (decoded to `Map<*, *>`); anything else (null, a stray scalar/array) yields an
     * empty map so a malformed arg block never aborts the whole save. Values are passed
     * through untouched — JSON numbers stay Double, which downstream tools already tolerate.
     */
    private fun asArgsMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(map.size)
        for ((k, v) in map) {
            val key = k as? String ?: continue
            out[key] = v
        }
        return out
    }
}
