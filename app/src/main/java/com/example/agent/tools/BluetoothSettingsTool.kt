package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the system Bluetooth settings so the user can toggle or pair Bluetooth themselves.
 *
 * Exposes the `open_bluetooth_settings` tool (no arguments). Programmatic Bluetooth
 * enable/disable is deprecated for apps targeting SDK 33+, so per ARCHITECTURE.md §5 we
 * deep-link to [Settings.ACTION_BLUETOOTH_SETTINGS] instead of attempting to flip the
 * radio. Unlike Wi-Fi there is no inline `Settings.Panel` for Bluetooth, so we always
 * open the full settings screen.
 *
 * This is a SAFE "handoff" action: it only surfaces UI; the user remains in control of
 * the actual toggle/pairing.
 *
 * @param context any Context; the application context is derived internally.
 */
class BluetoothSettingsTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_bluetooth_settings",
        description = "Open the Bluetooth settings screen so the user can toggle Bluetooth or " +
            "pair a device. (Apps can no longer toggle Bluetooth directly on modern Android.)",
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
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                // Started from outside an Activity context.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(appContext.packageManager) == null) {
                return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "No Bluetooth settings screen is available on this device."
                )
            }

            try {
                appContext.startActivity(intent)
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Opened Bluetooth settings. Toggle or pair from there.",
                    data = mapOf("action" to Settings.ACTION_BLUETOOTH_SETTINGS)
                )
            } catch (e: ActivityNotFoundException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Could not open Bluetooth settings: ${e.message ?: "no handler"}."
                )
            }
        }
}
