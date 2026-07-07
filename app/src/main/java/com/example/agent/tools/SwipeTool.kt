package com.example.agent.tools

import com.example.accessibility.Accessibility
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `swipe` — dispatches a coordinate swipe gesture from (x1,y1) to (x2,y2) over an
 * optional duration (ARCHITECTURE.md §3.2). Useful for fling/drag interactions and
 * for scrolling regions that expose no scrollable accessibility node.
 *
 * Returns a fresh [com.example.accessibility.ScreenState] so the model can observe
 * the resulting screen.
 */
class SwipeTool : AgentTool {

    private companion object {
        const val DEFAULT_DURATION_MS = 300L
    }

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "swipe",
        description = "Swipe from one screen coordinate to another in absolute screen pixels. " +
            "Use for flings or dragging when no scrollable element is available. " +
            "Returns the screen after the swipe.",
        parametersJsonSchema = """
{
  "type": "object",
  "properties": {
    "x1": {
      "type": "integer",
      "description": "Start X in screen pixels."
    },
    "y1": {
      "type": "integer",
      "description": "Start Y in screen pixels."
    },
    "x2": {
      "type": "integer",
      "description": "End X in screen pixels."
    },
    "y2": {
      "type": "integer",
      "description": "End Y in screen pixels."
    },
    "durationMs": {
      "type": "integer",
      "description": "Gesture duration in milliseconds (default 300)."
    }
  },
  "required": [
    "x1",
    "y1",
    "x2",
    "y2"
  ]
}
""".trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val controller = Accessibility.controller?.takeIf { it.isReady }
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Accessibility service is not ready; cannot swipe."
            )

        val x1 = args.intArg("x1")
        val y1 = args.intArg("y1")
        val x2 = args.intArg("x2")
        val y2 = args.intArg("y2")
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Missing or invalid coordinate arguments; require integers x1,y1,x2,y2."
            )
        }
        val duration = (args.longArg("durationMs") ?: DEFAULT_DURATION_MS).coerceAtLeast(1L)

        val ok = controller.swipe(x1, y1, x2, y2, duration)
        val screen = controller.readScreen()
        return if (ok) {
            ToolResult.Success(
                toolName = declaration.name,
                callId = callId,
                message = "Swiped from ($x1,$y1) to ($x2,$y2).",
                screen = screen
            )
        } else {
            ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Swipe gesture failed.",
                screen = screen
            )
        }
    }
}

/** Coerce a loosely-typed function-call argument to [Int] (Gemini may send Long/Double/String). */
private fun Map<String, Any?>.intArg(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

/** Coerce a loosely-typed function-call argument to [Long]. */
private fun Map<String, Any?>.longArg(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}
