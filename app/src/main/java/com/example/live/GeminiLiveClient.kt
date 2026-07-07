package com.example.live

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.squareup.moshi.Moshi
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket client for the Gemini Live BidiGenerateContent API.
 *
 * Streams microphone PCM16 audio to the server and surfaces server audio/text/turn
 * events through [Listener]. All protocol messages are (de)serialized with Moshi using
 * the @JsonClass(generateAdapter=true) data classes in LiveProtocol.kt.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = "gemini-3.1-flash-live-preview",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
) {

    interface Listener {
        fun onOpen()
        fun onSetupComplete()
        fun onAudio(pcm: ByteArray)
        fun onText(text: String)
        fun onTurnComplete()
        fun onInterrupted()
        /** The model requested one or more tool/function calls this turn. */
        fun onToolCall(calls: List<LiveFunctionCall>)
        /** The model cancelled previously-issued tool calls (barge-in). */
        fun onToolCallCancellation(ids: List<String>)
        fun onError(t: Throwable)
        fun onClosed()
    }

    private val moshi: Moshi = Moshi.Builder().build()

    private val setupRequestAdapter = moshi.adapter(LiveSetupRequest::class.java)
    private val realtimeInputAdapter = moshi.adapter(LiveRealtimeInputRequest::class.java)
    private val clientContentAdapter = moshi.adapter(LiveClientContentRequest::class.java)
    private val serverMessageAdapter = moshi.adapter(LiveServerMessage::class.java)
    private val toolResponseAdapter = moshi.adapter(LiveToolResponseRequest::class.java)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var closed: Boolean = false

    /**
     * Opens the WebSocket connection. On open, sends the [LiveSetupRequest] and invokes
     * [Listener.onOpen]. Subsequent server messages are routed to [listener].
     */
    fun connect(
        systemInstruction: String,
        voiceName: String,
        tools: List<LiveTool>? = null,
        listener: Listener
    ) {
        this.listener = listener
        this.closed = false

        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        val setupRequest = LiveSetupRequest(
            setup = LiveSetup(
                model = "models/$model",
                generationConfig = LiveGenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = LiveSpeechConfig(
                        voiceConfig = LiveVoiceConfig(
                            prebuiltVoiceConfig = LivePrebuiltVoice(voiceName = voiceName)
                        )
                    )
                ),
                systemInstruction = LiveContent(
                    parts = listOf(LivePart(text = systemInstruction))
                ),
                tools = tools
            )
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    val json = setupRequestAdapter.toJson(setupRequest)
                    webSocket.send(json)
                    this@GeminiLiveClient.listener?.onOpen()
                } catch (t: Throwable) {
                    this@GeminiLiveClient.listener?.onError(t)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) {
                    closed = true
                    this@GeminiLiveClient.listener?.onClosed()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) {
                    closed = true
                    this@GeminiLiveClient.listener?.onClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@GeminiLiveClient.listener?.onError(t)
            }
        })
    }

    /**
     * Parses a single server message envelope and dispatches its contents to [listener].
     */
    private fun handleServerMessage(payload: String) {
        val l = listener ?: return
        val message: LiveServerMessage? = try {
            serverMessageAdapter.fromJson(payload)
        } catch (t: Throwable) {
            l.onError(t)
            return
        }
        if (message == null) return

        if (message.setupComplete != null) {
            l.onSetupComplete()
        }

        message.toolCall?.let { l.onToolCall(it.functionCalls) }
        message.toolCallCancellation?.let { l.onToolCallCancellation(it.ids ?: emptyList()) }

        val serverContent = message.serverContent ?: return

        serverContent.modelTurn?.parts?.forEach { part ->
            val inlineData = part.inlineData
            if (inlineData != null && inlineData.mimeType.startsWith("audio")) {
                try {
                    val pcm = Base64.decode(inlineData.data, Base64.NO_WRAP)
                    l.onAudio(pcm)
                } catch (t: Throwable) {
                    l.onError(t)
                }
            } else {
                val text = part.text
                if (text != null) {
                    l.onText(text)
                }
            }
        }

        if (serverContent.interrupted == true) {
            l.onInterrupted()
        }

        if (serverContent.turnComplete == true) {
            l.onTurnComplete()
        }
    }

    /**
     * Sends a chunk of captured microphone audio (PCM16 mono 16kHz) as a realtime input.
     */
    fun sendAudioChunk(pcm16: ByteArray) {
        val ws = webSocket ?: return
        val base64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val request = LiveRealtimeInputRequest(
            realtimeInput = LiveRealtimeInput(
                audio = LiveBlob(
                    data = base64,
                    mimeType = "audio/pcm;rate=16000"
                )
            )
        )
        try {
            ws.send(realtimeInputAdapter.toJson(request))
        } catch (t: Throwable) {
            listener?.onError(t)
        }
    }

    /**
     * Sends a single image frame (camera or screen-share) as realtime VIDEO input so the
     * model can SEE the world / the screen alongside the audio it hears. Callers throttle
     * to ~1 fps; [jpeg] is a JPEG-encoded frame.
     */
    fun sendVideoFrame(jpeg: ByteArray) {
        val ws = webSocket ?: return
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val request = LiveRealtimeInputRequest(
            realtimeInput = LiveRealtimeInput(
                video = LiveBlob(data = base64, mimeType = "image/jpeg")
            )
        )
        try {
            ws.send(realtimeInputAdapter.toJson(request))
        } catch (t: Throwable) {
            listener?.onError(t)
        }
    }

    /**
     * Sends a complete user text turn via clientContent.
     */
    fun sendTextTurn(text: String) {
        val ws = webSocket ?: return
        val request = LiveClientContentRequest(
            clientContent = LiveClientContent(
                turns = listOf(
                    LiveContent(
                        parts = listOf(LivePart(text = text)),
                        role = "user"
                    )
                ),
                turnComplete = true
            )
        )
        try {
            ws.send(clientContentAdapter.toJson(request))
        } catch (t: Throwable) {
            listener?.onError(t)
        }
    }

    /**
     * Sends tool/function results back to the model so it can continue the turn. Each
     * [LiveFunctionResponse.id] MUST match the originating [LiveFunctionCall.id] exactly,
     * or the Live turn hangs (ARCHITECTURE.md §3.4). Answer tool calls ONLY via this path
     * — never via [sendTextTurn].
     */
    fun sendToolResponse(responses: List<LiveFunctionResponse>) {
        val ws = webSocket ?: return
        val request = LiveToolResponseRequest(
            toolResponse = LiveToolResponse(functionResponses = responses)
        )
        try {
            ws.send(toolResponseAdapter.toJson(request))
        } catch (t: Throwable) {
            listener?.onError(t)
        }
    }

    /**
     * Closes the WebSocket and releases the listener.
     */
    fun close() {
        closed = true
        try {
            webSocket?.close(1000, null)
        } catch (_: Throwable) {
            // ignore
        }
        webSocket = null
        listener = null
    }
}
