package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the system Wi-Fi panel so the user can toggle Wi-Fi themselves.
 *
 * Exposes the `open_wifi_panel` tool (no arguments). Programmatic Wi-Fi toggling is
 * a no-op for apps targeting SDK 29+, so per ARCHITECTURE.md §5 we deep-link to the
 * settings surface instead of attempting to flip the radio:
 * - API 29+ (Q): the inline [Settings.Panel.ACTION_WIFI] slide-up panel.
 * - API < 29: fall back to [Settings.ACTION_WIFI_SETTINGS] (full settings screen).
 *
 * This is a SAFE "handoff" action: it only surfaces UI; the user remains in control of
 * the actual toggle.
 *
 * @param context any Context; the application context is derived internally.
 */
class WifiPanelTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_wifi_panel",
        description = "Open the Wi-Fi settings panel so the user can connect, disconnect, " +
            "or toggle Wi-Fi. (Apps can no longer toggle Wi-Fi directly on modern Android.)",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.Default) {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }

            val intent = Intent(action).apply {
                // Started from outside an Activity context.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                appContext.startActivity(intent)
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Opened the Wi-Fi panel. Toggle Wi-Fi from there.",
                    data = mapOf("action" to action)
                )
            } catch (e: ActivityNotFoundException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "No Wi-Fi settings screen is available on this device."
                )
            }
        }
}
