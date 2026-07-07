package com.example.agent.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns Do-Not-Disturb on or off via [NotificationManager.setInterruptionFilter].
 *
 * Exposes the `set_dnd` tool taking a single boolean `on` argument:
 * - `on = true`  → [NotificationManager.INTERRUPTION_FILTER_PRIORITY] (priority-only DND;
 *   the standard "Do Not Disturb" that still lets alarms through).
 * - `on = false` → [NotificationManager.INTERRUPTION_FILTER_ALL] (DND off, all interruptions).
 *
 * This is a GUARDED special-access action (ARCHITECTURE.md §5): changing the
 * interruption filter requires `ACCESS_NOTIFICATION_POLICY`, granted one-time via
 * [Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS]. If the grant is missing we do
 * NOT throw — we return a [ToolResult.Failure] whose message guides the user (and
 * includes the deep-link intent action) so the agent can narrate next steps.
 *
 * @param context any Context; the application context is derived internally.
 */
class DndTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    private val notificationManager: NotificationManager? =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "set_dnd",
        description = "Turn Do-Not-Disturb (silence notifications and calls) on or off. " +
            "Requires the one-time notification-policy access permission.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "on": {
                  "type": "boolean",
                  "description": "true to enable Do-Not-Disturb, false to disable it."
                }
              },
              "required": ["on"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.Default) {
            val on = args.readBoolean("on")
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Missing or invalid 'on' argument; expected a boolean."
                )

            val manager = notificationManager
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Notification service is unavailable on this device."
                )

            if (!manager.isNotificationPolicyAccessGranted) {
                return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "I can't change Do-Not-Disturb yet: notification-policy access " +
                        "isn't granted. Grant it under Settings > Apps > Special app access > " +
                        "Do Not Disturb access, then ask me again. " +
                        "(Deep-link intent: ${Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS})"
                )
            }

            val filter = if (on) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }

            try {
                manager.setInterruptionFilter(filter)
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = if (on) "Do-Not-Disturb turned on." else "Do-Not-Disturb turned off.",
                    data = mapOf("on" to on)
                )
            } catch (e: SecurityException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Do-Not-Disturb change was blocked: ${e.message ?: "permission revoked"}. " +
                        "Re-grant Do Not Disturb access. " +
                        "(Deep-link intent: ${Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS})"
                )
            }
        }

    /**
     * Builds the intent the host UI can fire to let the user grant notification-policy
     * access. Exposed so the agent/UI need not hard-code the action.
     */
    fun buildGrantIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
