package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `get_screen` — reads the current foreground screen and returns a fresh
 * [com.example.accessibility.ScreenState] for the model to ground against.
 *
 * This is the observation primitive of the plan-act-observe loop (ARCHITECTURE.md
 * §3.3/§3.4): the model never sees the screen directly, so it must call this after
 * any screen-changing action to refresh the (non-stable) element indices.
 */
class GetScreenTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "get_screen",
        description = "Read the current foreground screen and return its interactive " +
            "elements as an indexed list. Indices are regenerated on every read and are " +
            "NOT stable across renders, so call this again after any action that changes " +
            "the screen before referencing element indices.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {}
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot read the screen."
            )

        val screen = controller.readScreen()
        return ToolResult.Success(
            toolName = declaration.name,
            callId = callId,
            message = "Read ${screen.elements.size} elements from " +
                "${screen.packageName ?: "unknown app"}.",
            screen = screen
        )
    }
}
