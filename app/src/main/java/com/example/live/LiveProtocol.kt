package com.example.live

import com.squareup.moshi.JsonClass

// ============================================================================
// OUTGOING — setup
// ============================================================================

@JsonClass(generateAdapter = true)
data class LiveSetupRequest(
    val setup: LiveSetup
)

@JsonClass(generateAdapter = true)
data class LiveSetup(
    val model: String,
    val generationConfig: LiveGenerationConfig,
    val systemInstruction: LiveContent?,
    // Phone-control tools advertised to the model. Null/omitted for plain conversation.
    val tools: List<LiveTool>? = null
)

@JsonClass(generateAdapter = true)
data class LiveGenerationConfig(
    val responseModalities: List<String>,
    val speechConfig: LiveSpeechConfig?
)

@JsonClass(generateAdapter = true)
data class LiveSpeechConfig(
    val voiceConfig: LiveVoiceConfig
)

@JsonClass(generateAdapter = true)
data class LiveVoiceConfig(
    val prebuiltVoiceConfig: LivePrebuiltVoice
)

@JsonClass(generateAdapter = true)
data class LivePrebuiltVoice(
    val voiceName: String
)

@JsonClass(generateAdapter = true)
data class LiveContent(
    val parts: List<LivePart>,
    val role: String? = null
)

@JsonClass(generateAdapter = true)
data class LivePart(
    val text: String? = null,
    val inlineData: LiveInlineData? = null
)

@JsonClass(generateAdapter = true)
data class LiveInlineData(
    val mimeType: String,
    val data: String
)

// ============================================================================
// OUTGOING — realtime audio
// ============================================================================

@JsonClass(generateAdapter = true)
data class LiveRealtimeInputRequest(
    val realtimeInput: LiveRealtimeInput
)

@JsonClass(generateAdapter = true)
data class LiveRealtimeInput(
    // Mic PCM16 audio, and/or a single image frame (camera / screen-share) as JPEG.
    val audio: LiveBlob? = null,
    val video: LiveBlob? = null
)

@JsonClass(generateAdapter = true)
data class LiveBlob(
    val data: String,
    val mimeType: String
)

// ============================================================================
// OUTGOING — client text turn
// ============================================================================

@JsonClass(generateAdapter = true)
data class LiveClientContentRequest(
    val clientContent: LiveClientContent
)

@JsonClass(generateAdapter = true)
data class LiveClientContent(
    val turns: List<LiveContent>,
    val turnComplete: Boolean = true
)

// ============================================================================
// INCOMING
// ============================================================================

@JsonClass(generateAdapter = true)
data class LiveServerMessage(
    val setupComplete: LiveSetupComplete? = null,
    val serverContent: LiveServerContent? = null,
    // Tool-calling frames (DTOs defined in ToolProtocol.kt).
    val toolCall: LiveToolCall? = null,
    val toolCallCancellation: LiveToolCallCancellation? = null
)

@JsonClass(generateAdapter = true)
data class LiveSetupComplete(
    val unused: String? = null
)

@JsonClass(generateAdapter = true)
data class LiveServerContent(
    val modelTurn: LiveModelTurn? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val generationComplete: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class LiveModelTurn(
    val parts: List<LivePart>? = null
)
