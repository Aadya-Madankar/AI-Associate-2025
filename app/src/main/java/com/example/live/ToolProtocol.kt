package com.example.live

import com.squareup.moshi.JsonClass

// ============================================================================
// Gemini Live function-calling protocol DTOs
//
// Companion to LiveProtocol.kt — this file adds the tool / function-calling
// message shapes for the gemini-3.1-flash-live-preview BidiGenerateContent API.
// It does NOT modify LiveProtocol.kt; the wiring stream is responsible for
// adding `tools` to LiveSetup, `toolCall`/`toolCallCancellation` to
// LiveServerMessage, and a sendToolResponse() path in GeminiLiveClient.
//
// All classes use @JsonClass(generateAdapter = true) to match the codegen
// style of LiveProtocol.kt.
//
// ---------------------------------------------------------------------------
// MOSHI ADAPTER NOTE (read before wiring these into GeminiLiveClient):
// The `parameters`, `args`, and `response` fields are raw, open-ended JSON
// objects typed as `Map<String, Any?>`. These MUST stay (de)serializable by the
// peer's bare `Moshi.Builder().build()` instances (GeminiLiveClient and
// GeminiToolMapper both build Moshi with nothing added and construct DTO
// adapters EAGERLY at field-init time via `moshi.adapter(...)`).
//
// Moshi's built-in `StandardJsonAdapters.FACTORY` (present in every bare Moshi)
// DOES supply an `Object`/`Any` adapter (its `ObjectJsonAdapter`, which handles
// String/Boolean/Double/List/Map values) — but it only matches the *raw*
// `Object.class`. The hazard with `@JsonClass(generateAdapter = true)` over a
// Kotlin `Map<String, Any?>` is that codegen reifies the map's value type with a
// Java *wildcard* (`Map<String, ? extends Object>`). A wildcard does NOT equal
// the raw `Object.class`, so the generated adapter's eager
// `moshi.adapter(Map<String, ? extends Object>)` lookup fails to resolve a value
// adapter and Moshi throws IllegalArgumentException at adapter-construction time
// (a hard crash the moment GeminiLiveClient does `moshi.adapter(LiveToolCall…)`
// / `LiveFunctionResponse…` / `LiveToolResponseRequest…`), NOT a deserialize-only
// edge case. (Note `@JvmSuppressWildcards` placed *inside* the generic — i.e.
// `Map<String, @JvmSuppressWildcards Any?>` — is not enough on its own.)
//
// FIX (applied below, no other file touched): each open-map property is annotated
// with `@JvmSuppressWildcards` at the *property* position. This leaves the
// Kotlin-visible type exactly `Map<String, Any?>` (so the public contract and all
// callers in GeminiToolMapper — `args["package"] as? String`, etc. — are
// unchanged), but makes codegen emit a wildcard-free `Map<String, Object>` value
// type, which resolves cleanly against the built-in `MapJsonAdapter` +
// `ObjectJsonAdapter` already present in a bare Moshi. This mirrors the working
// explicit lookup GeminiToolMapper does with
// `Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)`,
// which is likewise wildcard-free.
//
// Value-shape reminder for the executor: JSON numbers deserialize to `Double`
// (e.g. an "index" arg arrives as `Double`, not `Int`); nested JSON objects/arrays
// arrive as `Map<String, Any?>` / `List<Any?>`.
// ============================================================================

// ============================================================================
// OUTGOING — tool declarations (advertised inside the setup request)
// ============================================================================

/**
 * A bundle of function declarations advertised to the model. In the Live setup
 * request this is carried as `setup.tools = [ LiveTool(functionDeclarations=[…]) ]`.
 *
 * The wiring stream adds `tools: List<LiveTool>?` to [LiveSetup] (in LiveProtocol.kt)
 * and populates it with one [LiveTool] grouping every available
 * [LiveFunctionDeclaration].
 */
/**
 * One entry in the Live setup `tools` array. The array may MIX our custom
 * [functionDeclarations] with Gemini's built-in tools — pass each as its own [LiveTool]:
 * `tools = [ {functionDeclarations:[…]}, {googleSearch:{}}, {codeExecution:{}}, {urlContext:{}} ]`.
 * Null fields are omitted by Moshi, so an entry carries exactly one tool kind.
 *
 *  - [googleSearch]  — live web grounding (model answers with current facts + citations).
 *  - [codeExecution] — the model can run Python in Gemini's sandbox for math/logic.
 *  - [urlContext]    — the model can fetch + read a URL the user/website references.
 */
@JsonClass(generateAdapter = true)
data class LiveTool(
    val functionDeclarations: List<LiveFunctionDeclaration>? = null,
    val googleSearch: LiveGoogleSearch? = null,
    val codeExecution: LiveCodeExecution? = null,
    val urlContext: LiveUrlContext? = null
)

/** Built-in Google Search grounding tool. Serializes to an empty object `{}`. */
@JsonClass(generateAdapter = true)
class LiveGoogleSearch

/** Built-in code-execution tool (Python sandbox). Serializes to an empty object `{}`. */
@JsonClass(generateAdapter = true)
class LiveCodeExecution

/** Built-in URL-context tool (fetch + read a referenced page). Serializes to `{}`. */
@JsonClass(generateAdapter = true)
class LiveUrlContext

/**
 * One callable function exposed to the model: its [name], a natural-language
 * [description] of when/why to call it, and a [parameters] JSON-Schema object
 * describing its arguments.
 *
 * [parameters] is a raw JSON-Schema object (e.g. `{"type":"object","properties":…}`)
 * represented as an open `Map<String, Any?>`. It is nullable so parameter-less tools
 * (such as `get_screen` / `press_back`) can omit it entirely. See the MOSHI ADAPTER
 * NOTE at the top of this file regarding serialization of the `Map` value type.
 */
@JsonClass(generateAdapter = true)
data class LiveFunctionDeclaration(
    val name: String,
    val description: String,
    @JvmSuppressWildcards val parameters: Map<String, Any?>? = null
)

// ============================================================================
// INCOMING — tool call from the model
//
// The server sends `{"toolCall":{"functionCalls":[{id,name,args}]}}`. The wiring
// stream adds `toolCall: LiveToolCall?` to [LiveServerMessage] (LiveProtocol.kt)
// and dispatches it to a new Listener callback.
// ============================================================================

/**
 * A batch of function calls the model wants executed this turn. Each call's `id`
 * MUST be echoed verbatim in the matching [LiveFunctionResponse], or the Live turn
 * will hang (see ARCHITECTURE.md §3.4). gemini-3.1-flash-live-preview is
 * sequential/blocking, so treat the list in order.
 */
@JsonClass(generateAdapter = true)
data class LiveToolCall(
    val functionCalls: List<LiveFunctionCall>
)

/**
 * A single function the model asked to invoke.
 *
 * @property id   Opaque call id — echo it back unchanged in [LiveFunctionResponse.id].
 * @property name The declared tool name (matches a [LiveFunctionDeclaration.name]).
 * @property args The arguments object as an open `Map<String, Any?>`, nullable for
 *                parameter-less tools. JSON numbers arrive as `Double`; see the MOSHI
 *                ADAPTER NOTE at the top of this file.
 */
@JsonClass(generateAdapter = true)
data class LiveFunctionCall(
    val id: String? = null,
    val name: String,
    @JvmSuppressWildcards val args: Map<String, Any?>? = null
)

/**
 * Sent by the server on barge-in / cancellation:
 * `{"toolCallCancellation":{"ids":[…]}}`. The listed [ids] correspond to
 * previously delivered [LiveFunctionCall.id]s whose execution should be aborted.
 * The wiring stream adds `toolCallCancellation: LiveToolCallCancellation?` to
 * [LiveServerMessage] and routes it through the existing `onInterrupted` path.
 */
@JsonClass(generateAdapter = true)
data class LiveToolCallCancellation(
    val ids: List<String>? = null
)

// ============================================================================
// OUTGOING — tool response back to the model
//
// Reply to a toolCall with `{"toolResponse":{"functionResponses":[{id,name,response}]}}`.
// NEVER answer a function call via clientContent / a text turn — only via this
// shape (ARCHITECTURE.md §3.4).
// ============================================================================

/**
 * Top-level outgoing frame carrying tool results, mirroring the structure of
 * [LiveClientContentRequest] / [LiveRealtimeInputRequest] in LiveProtocol.kt.
 * Serialize with a dedicated Moshi adapter in `GeminiLiveClient.sendToolResponse(...)`.
 */
@JsonClass(generateAdapter = true)
data class LiveToolResponseRequest(
    val toolResponse: LiveToolResponse
)

/**
 * The batch of function responses for the function calls received this turn.
 * One [LiveFunctionResponse] per [LiveFunctionCall] handled.
 */
@JsonClass(generateAdapter = true)
data class LiveToolResponse(
    val functionResponses: List<LiveFunctionResponse>
)

/**
 * The result of executing one tool call.
 *
 * @property id       MUST equal the originating [LiveFunctionCall.id] exactly, or the
 *                    Live turn hangs.
 * @property name     The tool name that was executed (matches the originating call).
 * @property response The result object the model will reason over — typically
 *                    `{result, screen:[…]}` (see ARCHITECTURE.md §3.4), as an open
 *                    `Map<String, Any?>`. See the MOSHI ADAPTER NOTE at the top of
 *                    this file regarding serialization of the value type.
 */
@JsonClass(generateAdapter = true)
data class LiveFunctionResponse(
    val id: String,
    val name: String,
    @JvmSuppressWildcards val response: Map<String, Any?>
)
