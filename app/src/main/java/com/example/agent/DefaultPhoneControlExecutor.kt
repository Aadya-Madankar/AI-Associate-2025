package com.example.agent

import android.content.Context
import android.util.Log
import com.example.agent.tools.ToolArgs
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [PhoneControlExecutor]: a thin, stateless dispatcher over a [ToolRegistry].
 *
 * Responsibilities (deliberately small — permission gating and loop control live
 * upstream in [AgentLoopController] / the ViewModel, ARCHITECTURE.md §3.4):
 *  1. [declarations] — surface every advertised [ToolDeclaration] for the Live setup request.
 *  2. [execute] — resolve a model tool call by name, run it, and return its [ToolResult].
 *     - The synthetic `task_complete` call short-circuits to [ToolResult.Completed].
 *     - An unknown tool name returns [ToolResult.Failure] (never throws) so the model can
 *       self-correct from the error text rather than the turn hanging.
 *     - Any exception thrown by a tool is caught and converted to [ToolResult.Failure];
 *       a misbehaving tool must never crash the agent loop or the WebSocket reader.
 *
 * This type is a process-wide singleton injected into the ViewModel; it is NOT attached to
 * the (rebuilt-on-reconnect) `GeminiLiveClient`, so it survives persona switches.
 */
class DefaultPhoneControlExecutor(
    private val registry: ToolRegistry
) : PhoneControlExecutor {

    /** Convenience constructor that builds a [ToolRegistry] from a [Context]. */
    constructor(context: Context) : this(ToolRegistry(context))

    override fun declarations(): List<ToolDeclaration> = registry.declarations()

    override suspend fun execute(callId: String, name: String, args: Map<String, Any?>): ToolResult {
        // task_complete is a control signal, not a side-effecting tool.
        if (name == TASK_COMPLETE) {
            return ToolResult.Completed(
                toolName = name,
                callId = callId,
                success = ToolArgs.boolArg(args, "success") ?: true,
                summary = (args["summary"] as? String).orEmpty()
            )
        }

        val tool = registry.byName(name)
            ?: return ToolResult.Failure(
                toolName = name,
                callId = callId,
                error = "Unknown tool '$name'. Available tools: " +
                    registry.declarations().joinToString(", ") { it.name } + "."
            )

        return try {
            tool.execute(callId, args)
        } catch (c: CancellationException) {
            // Never swallow cancellation: barge-in / kill-switch / toolCallCancellation
            // (AgentLoopController.cancel()) cancels the agent coroutine by throwing this
            // from a suspension point inside tool.execute(). Letting it propagate keeps
            // structured-concurrency cancellation working instead of reporting a cancelled
            // step as an ordinary failed-tool result.
            throw c
        } catch (t: Throwable) {
            // The full throwable message can echo tool/framework-controlled text derived
            // from sensitive args (typed text, OTP, phone number, amount, intent extras).
            // That raw message must never reach the cloud model, so keep it on the local
            // logcat/audit sink only and hand Gemini a generic, non-sensitive error.
            Log.w(TAG, "Tool '$name' threw: ${t.message ?: t::class.java.simpleName}", t)
            ToolResult.Failure(
                toolName = name,
                callId = callId,
                error = "Tool '$name' failed (${t::class.java.simpleName})."
            )
        }
    }

    private companion object {
        private const val TAG = "PhoneControlExecutor"
    }
}
