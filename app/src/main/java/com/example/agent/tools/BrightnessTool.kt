package com.example.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.agent.AgentTool
import com.example.agent.AgentToolSchemas
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Sets the system screen brightness via [Settings.System] (`SCREEN_BRIGHTNESS`).
 *
 * Exposes the `set_brightness` tool taking a single integer `level` argument in the
 * inclusive range 0..100, mapped onto the platform's 0..255 brightness range.
 *
 * This is a GUARDED special-access action (ARCHITECTURE.md §5): writing
 * [Settings.System] requires the `WRITE_SETTINGS` app-op, granted one-time via
 * [Settings.ACTION_MANAGE_WRITE_SETTINGS]. If the grant is missing we do NOT throw —
 * we return a [ToolResult.Failure] whose message guides the user (and includes the
 * intent action the host UI can deep-link to) so the agent can narrate next steps.
 *
 * We also switch the brightness mode to manual ([Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL])
 * so the requested level actually sticks; otherwise auto-brightness immediately overrides it.
 *
 * @param context any Context; the application context is derived internally.
 */
class BrightnessTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = AgentToolSchemas.BRIGHTNESS,
        description = "Set the screen brightness to a percentage from 0 (dimmest) to 100 " +
            "(brightest). This also turns OFF adaptive/auto-brightness so the level sticks. " +
            "Requires the one-time 'Modify system settings' permission.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "level": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 100,
                  "description": "Target brightness as a percentage, 0..100."
                }
              },
              "required": ["level"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.Default) {
            val level = args.readInt("level")
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Missing or invalid 'level' argument; expected an integer 0..100."
                )

            // canWrite covers all supported SDKs (it returns true pre-M, where the
            // permission is install-time). Guarding here avoids a SecurityException.
            if (!Settings.System.canWrite(appContext)) {
                return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "I can't change brightness yet: the 'Modify system settings' " +
                        "permission isn't granted. Grant it under Settings > Apps > Special " +
                        "app access > Modify system settings, then ask me again. " +
                        "(Deep-link intent: ${Settings.ACTION_MANAGE_WRITE_SETTINGS})"
                )
            }

            val clamped = level.coerceIn(0, 100)
            // 0..100 percent → platform 0..255. Keep a small floor so the screen never
            // goes fully black (which can look like the device powered off).
            val target = ((clamped / 100.0) * MAX_BRIGHTNESS).roundToInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

            try {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    target
                )
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Screen brightness set to $clamped% (adaptive brightness turned off).",
                    data = mapOf(
                        "level" to clamped,
                        "rawValue" to target,
                        "autoBrightnessDisabled" to true
                    )
                )
            } catch (e: SecurityException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Brightness change was blocked by the system: " +
                        "${e.message ?: "permission revoked"}. " +
                        "Re-grant 'Modify system settings'. " +
                        "(Deep-link intent: ${Settings.ACTION_MANAGE_WRITE_SETTINGS})"
                )
            }
        }

    /**
     * Builds the intent the host UI can fire to let the user grant `WRITE_SETTINGS`.
     * Exposed as a convenience so the agent/UI need not hard-code the action/URI.
     *
     * This tool holds only the application context, so the intent carries
     * [Intent.FLAG_ACTIVITY_NEW_TASK] by default (firing from a non-Activity context
     * otherwise throws). Callers that already have an Activity context may strip the flag.
     */
    fun buildGrantIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val MAX_BRIGHTNESS = 255
        const val MIN_BRIGHTNESS = 1
    }
}
