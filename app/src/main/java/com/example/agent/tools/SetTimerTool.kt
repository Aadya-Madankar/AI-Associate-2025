package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `set_timer` — starts a countdown timer via [AlarmClock.ACTION_SET_TIMER]. `EXTRA_SKIP_UI`
 * is left false so the clock app surfaces the timer for the user to see/cancel (SAFE,
 * reversible). See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve a clock app and start the activity.
 */
class SetTimerTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "set_timer",
        description = "Start a countdown timer for a number of seconds.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "seconds": { "type": "integer", "description": "Timer length in seconds (> 0)." },
                "message": { "type": "string", "description": "Optional timer label." }
              },
              "required": ["seconds"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val seconds = asInt(args["seconds"])
        if (seconds == null || seconds <= 0) {
            return ToolResult.Failure(declaration.name, callId, "'seconds' must be a positive integer.")
        }
        val message = (args["message"] as? String)?.trim()

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = message?.let { " ($it)" } ?: ""
        return runCatching {
            // No resolveActivity() pre-check: under Android 11+ (API 30+) package-visibility
            // filtering it returns null for ACTION_SET_TIMER even when a clock app is installed
            // (the manifest declares no <queries> for it). Start the activity directly and map
            // ActivityNotFoundException to the "no clock app" failure instead.
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Timer started for ${seconds}s$label.",
                data = mapOf("seconds" to seconds)
            )
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No clock app supports timers.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not start timer: ${it.message}")
            }
        }
    }

    /** Tolerate model JSON numbers arriving as Int, Long, Double or numeric String. */
    private fun asInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
