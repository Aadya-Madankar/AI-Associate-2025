package com.example.agent

import com.example.accessibility.ScreenState

/**
 * Result of executing one tool/action. Serialized back to Gemini as a
 * `functionResponse` so the model can plan the next step. See ARCHITECTURE.md §3.4.
 */
sealed interface ToolResult {
    val toolName: String
    val callId: String

    data class Success(
        override val toolName: String,
        override val callId: String,
        val message: String = "",
        val screen: ScreenState? = null,
        val data: Map<String, Any?> = emptyMap()
    ) : ToolResult

    data class Failure(
        override val toolName: String,
        override val callId: String,
        val error: String,
        val screen: ScreenState? = null
    ) : ToolResult

    /** The model declared the whole task finished via `task_complete`. */
    data class Completed(
        override val toolName: String,
        override val callId: String,
        val success: Boolean,
        val summary: String
    ) : ToolResult
}

/**
 * A Gemini function declaration: name, natural-language description, and a JSON-schema
 * (as a raw JSON string) describing its parameters. Advertised in the Live setup request.
 */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

/** A single tool the agent can invoke. Implementations live in `com.example.agent.tools.*`. */
interface AgentTool {
    val declaration: ToolDeclaration
    suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult
}

/**
 * Resolves and runs tool calls coming from the model. A singleton injected into
 * [com.example.viewmodels.XenoViewModel]; survives persona switches / reconnects
 * (it is NOT attached to the rebuilt GeminiLiveClient).
 */
interface PhoneControlExecutor {
    /** All tool declarations to advertise in the Live setup request. */
    fun declarations(): List<ToolDeclaration>

    /** Execute one model tool call. Permission gating happens upstream in the loop. */
    suspend fun execute(callId: String, name: String, args: Map<String, Any?>): ToolResult
}

/** High-level agent progress surfaced to the UI / Nazim while a task runs. */
data class AgentStatus(
    val active: Boolean = false,
    val currentStep: String = "",
    val stepCount: Int = 0
)
