package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `set_alarm` — schedules an alarm via [AlarmClock.ACTION_SET_ALARM]. `EXTRA_SKIP_UI`
 * is left false so the clock app stays visible and the user can review/cancel; this
 * keeps the action transparent and reversible (SAFE). See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve a clock app and start the activity.
 */
class SetAlarmTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "set_alarm",
        description = "Set an alarm at a given hour and minute (24-hour clock).",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "hour": { "type": "integer", "description": "Hour of day, 0-23." },
                "minute": { "type": "integer", "description": "Minute, 0-59." },
                "message": { "type": "string", "description": "Optional alarm label." }
              },
              "required": ["hour", "minute"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val hour = asInt(args["hour"])
        val minute = asInt(args["minute"])
        if (hour == null || hour !in 0..23) {
            return ToolResult.Failure(declaration.name, callId, "'hour' must be an integer 0-23.")
        }
        if (minute == null || minute !in 0..59) {
            return ToolResult.Failure(declaration.name, callId, "'minute' must be an integer 0-59.")
        }
        val message = (args["message"] as? String)?.trim()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hh = hour.toString().padStart(2, '0')
        val mm = minute.toString().padStart(2, '0')
        val label = message?.let { " ($it)" } ?: ""
        // Start the activity directly rather than pre-checking with resolveActivity(),
        // which returns null under Android 11+ package-visibility filtering even when a
        // clock app is installed. A missing handler surfaces as ActivityNotFoundException.
        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Alarm set for $hh:$mm$label.",
                data = mapOf("hour" to hour, "minute" to minute)
            )
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No clock app supports setting alarms.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not set alarm: ${it.message}")
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
