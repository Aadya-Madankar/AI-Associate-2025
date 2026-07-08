package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `tap` — taps the element at the given `index` from the most recent `get_screen`
 * read. Resolves `index → bounds.center → gesture` inside the controller and falls
 * back to a coordinate tap when `performAction` fails (ARCHITECTURE.md §3.2).
 *
 * Returns a fresh [com.example.accessibility.ScreenState] so the model can observe
 * the result of the tap without a separate `get_screen` call.
 */
class TapTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "tap",
        description = "Tap the on-screen element at the given index from the most recent " +
            "get_screen result. Returns the screen after the tap.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "The element index from the latest get_screen result."
    }
  },
  "required": [
    "index"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot tap."
            )

        val index = ToolArgs.intArg(args, "index")
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'index' argument."
            )

        val ok = controller.tap(index)
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Tapped element $index.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Tap on element $index failed (element gone, off-screen, or not actionable).",
                screen = screen
            )
        }
    }
}
