package com.example.skill.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.skill.SkillStore

/**
 * `list_skills` — lists the skills Nazim has saved on THIS phone (on-device JSON memory).
 *
 * Takes no arguments. Returns a lightweight summary of every saved skill (name, description,
 * and step count) in `data["skills"]` so the model can tell the user what it can replay
 * without dumping every step. To actually replay one, the model should call `recall_skill`.
 *
 * SAFE: read-only access to local memory; nothing is changed and no side effects occur.
 *
 * @param store on-device skill memory (see [com.example.skill.JsonFileSkillStore]).
 */
class ListSkillsTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "list_skills",
        description = "List the skills saved on this phone. Returns each skill's name, " +
            "description, and number of steps. Takes no arguments.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val skills = store.all().map { skill ->
            mapOf(
                "name" to skill.name,
                "description" to skill.description,
                "steps" to skill.steps.size
            )
        }
        val message = if (skills.isEmpty()) {
            "No skills saved on this phone yet."
        } else {
            "Found ${skills.size} saved skill${if (skills.size == 1) "" else "s"}."
        }
        return ToolResult.Success(
            toolName = declaration.name,
            callId = callId,
            message = message,
            data = mapOf("skills" to skills)
        )
    }
}
