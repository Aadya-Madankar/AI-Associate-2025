package com.example.agent.tools

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Toggles the device flashlight (torch) via [CameraManager.setTorchMode].
 *
 * Exposes the `torch` tool taking a single boolean `on` argument. This is a SAFE,
 * reversible action (ARCHITECTURE.md §5): no special permission is required and the
 * previous state can always be restored by calling the tool again.
 *
 * Implementation notes:
 * - We locate the first camera that reports [CameraCharacteristics.FLASH_INFO_AVAILABLE].
 * - [CameraManager.setTorchMode] is available from API 23 (minSdk here is 24), so no
 *   version guard is needed, but devices without a flash unit are handled gracefully.
 *
 * @param context any Context; the application context is derived internally so the tool
 *                can outlive the Activity that created it.
 */
class TorchTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "torch",
        description = "Turn the device flashlight (torch) on or off. Reversible and safe.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "on": {
                  "type": "boolean",
                  "description": "true to switch the flashlight on, false to switch it off."
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

            val manager = cameraManager
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Camera service is unavailable on this device."
                )

            val flashCameraId = manager.findFlashCameraId()
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "No camera with a flash unit was found on this device."
                )

            try {
                manager.setTorchMode(flashCameraId, on)
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = if (on) "Flashlight turned on." else "Flashlight turned off.",
                    data = mapOf("on" to on)
                )
            } catch (e: CameraAccessException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Could not toggle the flashlight: ${e.message ?: "camera in use"}."
                )
            } catch (e: IllegalArgumentException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Could not toggle the flashlight: ${e.message ?: "invalid camera"}."
                )
            }
        }

    /** Returns the id of the first camera exposing a flash unit, or null if none. */
    private fun CameraManager.findFlashCameraId(): String? = try {
        cameraIdList.firstOrNull { id ->
            getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        null
    }
}

/** Coerce a JSON-decoded map value to a Boolean, tolerating string forms. */
internal fun Map<String, Any?>.readBoolean(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull()
    is Number -> v.toInt() != 0
    else -> null
}
