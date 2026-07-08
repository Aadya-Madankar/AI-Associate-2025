package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `scroll` — scrolls the scrollable element at `index` forward or backward
 * (ARCHITECTURE.md §3.2). Prefer this over [SwipeTool] when the target exposes a
 * scrollable accessibility node, as it is more robust than a coordinate fling.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] so the model can observe
 * newly revealed elements.
 */
class ScrollTool : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "scroll",
        description = "Scroll the scrollable element at the given index forward (down/next) " +
            "or backward (up/previous). Returns the screen after scrolling.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "The scrollable element index from the latest get_screen result."
    },
    "forward": {
      "type": "boolean",
      "description": "true to scroll forward (down/next), false to scroll backward (up/previous)."
    }
  },
  "required": [
    "index",
    "forward"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot scroll."
            )

        val index = ToolArgs.intArg(args, "index")
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'index' argument."
            )
        val forward = ToolArgs.boolArg(args, "forward")
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid 'forward' argument (expected boolean)."
            )

        val ok = controller.scroll(index, forward)
        val screen = controller.readScreen()
        val dir = if (forward) "forward" else "backward"
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Scrolled element $index $dir.",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Scroll $dir on element $index failed (not scrollable or at edge).",
                screen = screen
            )
        }
    }
}
