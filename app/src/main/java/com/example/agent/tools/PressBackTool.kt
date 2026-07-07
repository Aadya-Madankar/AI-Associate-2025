package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `press_back` — performs the global BACK navigation action
 * (`performGlobalAction(GLOBAL_ACTION_BACK)`, ARCHITECTURE.md §3.2). Dismisses
 * dialogs, closes keyboards, or navigates up one screen.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] for the model to observe.
 */
class PressBackTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "press_back",
        description = "Press the system Back button to dismiss a dialog/keyboard or navigate " +
            "up one screen. Returns the screen afterward.",
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
                error = "Accessibility service is not ready; cannot press Back."
            )

        val ok = controller.back()
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Pressed Back.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Back action was rejected by the system.",
                screen = screen
            )
        }
    }
}
