package com.example.agent.tools

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Toggles media playback by dispatching a media key event via
 * [AudioManager.dispatchMediaKeyEvent].
 *
 * Exposes the `media_play_pause` tool. By default it sends
 * [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE], which the currently active media session
 * interprets as a toggle. Optional `action` ("play" / "pause") sends the dedicated
 * play/pause keycodes when the model wants a specific direction rather than a toggle.
 *
 * This is a SAFE, reversible action (ARCHITECTURE.md §5): it targets whatever media
 * session currently holds audio focus and requires no special permission. If nothing is
 * playing, the event is simply ignored by the system.
 *
 * @param context any Context; the application context is derived internally.
 */
class MediaPlayPauseTool(context: Context) : AgentTool {

    private val appContext: Context = context.applicationContext

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "media_play_pause",
        description = "Play, pause, or toggle the currently active media playback " +
            "(music, podcast, video). Omit 'action' to toggle.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["toggle", "play", "pause"],
                  "description": "Optional. 'play', 'pause', or 'toggle' (default)."
                }
              },
              "required": []
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.Default) {
            val manager = audioManager
                ?: return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Audio service is unavailable on this device."
                )

            val action = (args["action"] as? String)?.trim()?.lowercase() ?: "toggle"
            val keyCode = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                else -> return@withContext ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Unknown 'action' '$action'; expected play, pause, or toggle."
                )
            }

            try {
                // A media key is one DOWN followed by one UP event.
                val eventTime = SystemClock.uptimeMillis()
                manager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                manager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = when (action) {
                        "play" -> "Sent play to active media."
                        "pause" -> "Sent pause to active media."
                        else -> "Toggled media playback."
                    },
                    data = mapOf("action" to action)
                )
            } catch (e: SecurityException) {
                ToolResult.Failure(
                    toolName = declaration.name,
                    callId = callId,
                    error = "Media key dispatch was blocked: ${e.message ?: "policy restriction"}."
                )
            }
        }
}
