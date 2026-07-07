package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `press_home` — performs the global HOME navigation action
 * (`performGlobalAction(GLOBAL_ACTION_HOME)`, ARCHITECTURE.md §3.2), returning to the
 * launcher.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] for the model to observe.
 */
class PressHomeTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "press_home",
        description = "Press the system Home button to return to the launcher/home screen. " +
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
                error = "Accessibility service is not ready; cannot press Home."
            )

        val ok = controller.home()
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Pressed Home.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Home action was rejected by the system.",
                screen = screen
            )
        }
    }
}
