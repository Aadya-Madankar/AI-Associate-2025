package com.example.agent.tools

import android.content.Context
import android.media.AudioManager
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Sets the media (music) volume via [AudioManager.setStreamVolume] on
 * [AudioManager.STREAM_MUSIC], showing the system volume UI ([AudioManager.FLAG_SHOW_UI]).
 *
 * Exposes the `set_volume` tool taking a single integer `level` argument in the
 * inclusive range 0..100, which is mapped onto the device's actual stream-volume index
 * range. This is a SAFE, reversible action (ARCHITECTURE.md §5): no special permission
 * is required for STREAM_MUSIC.
 *
 * @param context any Context; the application context is derived internally.
 */
class MediaVolumeTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "set_volume",
        description = "Set the media (music) volume to a percentage from 0 (mute) to 100 (max).",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "level": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 100,
                  "description": "Target media volume as a percentage, 0..100."
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

            val clamped = level.coerceIn(0, 100)

            val manager = audioManager
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Audio service is unavailable on this device."
                )

            val maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxIndex <= 0) {
                return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Media volume cannot be controlled on this device."
                )
            }

            // Map 0..100 percent onto the device's 0..maxIndex stream-volume range.
            val targetIndex = ((clamped / 100.0) * maxIndex).roundToInt().coerceIn(0, maxIndex)

            try {
                manager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    targetIndex,
                    AudioManager.FLAG_SHOW_UI
                )
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Media volume set to $clamped%.",
                    data = mapOf("level" to clamped, "index" to targetIndex, "maxIndex" to maxIndex)
                )
            } catch (e: SecurityException) {
                // STREAM_MUSIC is normally unrestricted, but some OEM/DND states can reject it.
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Volume change was blocked by the system: ${e.message ?: "policy restriction"}."
                )
            }
        }
}

/** Coerce a JSON-decoded map value to an Int, tolerating Double/string forms. */
internal fun Map<String, Any?>.readInt(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Long -> v.toInt()
    is Number -> v.toDouble().roundToInt()
    is String -> v.trim().toDoubleOrNull()?.roundToInt()
    else -> null
}
