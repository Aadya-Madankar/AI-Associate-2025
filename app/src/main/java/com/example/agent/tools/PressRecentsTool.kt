package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `press_recents` — performs the global RECENTS navigation action
 * (`performGlobalAction(GLOBAL_ACTION_RECENTS)`, ARCHITECTURE.md §3.2), opening the
 * recent-apps overview for switching tasks.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] for the model to observe.
 */
class PressRecentsTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "press_recents",
        description = "Open the Recents / recent-apps overview to switch between tasks. " +
            "Returns the screen afterward.",
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
                error = "Accessibility service is not ready; cannot open Recents."
            )

        val ok = controller.recents()
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Opened Recents.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Recents action was rejected by the system.",
                screen = screen
            )
        }
    }
}
