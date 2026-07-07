package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `open_notifications` — performs the global OPEN_NOTIFICATIONS action
 * (`performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)`, ARCHITECTURE.md §3.2),
 * pulling down the notification shade so its contents become readable elements.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] for the model to observe.
 */
class OpenNotificationsTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_notifications",
        description = "Pull down the notification shade so its notifications can be read and " +
            "acted on. Returns the screen afterward.",
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
                error = "Accessibility service is not ready; cannot open notifications."
            )

        val ok = controller.openNotifications()
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Opened the notification shade.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Open-notifications action was rejected by the system.",
                screen = screen
            )
        }
    }
}
