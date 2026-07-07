package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `add_event` — opens the calendar app's "new event" editor pre-filled via an
 * [Intent.ACTION_INSERT] on [CalendarContract.Events.CONTENT_URI]. The user saves the
 * event themselves, so no calendar write permission is needed and the action is SAFE.
 * See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve a calendar app and start the editor.
 */
class AddCalendarEventTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "add_event",
        description = "Open the calendar app to create an event, pre-filled with a title and time. " +
            "The user reviews and saves it.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "title": { "type": "string", "description": "Event title." },
                "beginMs": { "type": "integer", "description": "Start time as Unix epoch milliseconds." },
                "endMs": { "type": "integer", "description": "Optional end time as Unix epoch milliseconds." },
                "location": { "type": "string", "description": "Optional event location." }
              },
              "required": ["title", "beginMs"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val title = (args["title"] as? String)?.trim()
        if (title.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'title'.")
        }
        val beginMs = asLong(args["beginMs"])
        if (beginMs == null || beginMs <= 0L) {
            return ToolResult.Failure(declaration.name, callId, "'beginMs' must be epoch milliseconds.")
        }
        val endMs = asLong(args["endMs"])?.takeIf { it > beginMs }
        val location = (args["location"] as? String)?.trim()

        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            if (endMs != null) putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            if (!location.isNullOrEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // NOTE: We intentionally do NOT gate on intent.resolveActivity(packageManager).
        // On API 30+ (targetSdk 36) package-visibility filtering hides the calendar app
        // unless an INSERT/event <intent> is declared in the manifest's <queries> block,
        // so resolveActivity would return null even when a capable calendar app exists,
        // permanently short-circuiting this tool. Instead we attempt startActivity and
        // treat ActivityNotFoundException as the "no calendar app" case.
        return try {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name, callId,
                "Opened the calendar to add \"$title\". The user can review and save it.",
                data = mapOf("title" to title, "beginMs" to beginMs)
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.Failure(declaration.name, callId, "No calendar app available.")
        } catch (e: Exception) {
            ToolResult.Failure(declaration.name, callId, "Could not open calendar: ${e.message}")
        }
    }

    /** Tolerate model JSON numbers arriving as Long, Int, Double or numeric String. */
    private fun asLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
