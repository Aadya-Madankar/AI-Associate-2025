package com.example.agent

import com.example.accessibility.ScreenState
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure, side-effect-free guard rails for the plan-act-observe loop (ARCHITECTURE.md §3.4).
 *
 * It owns three independent safety mechanisms and nothing else — no coroutines, no Android
 * types, no I/O — so it is trivially unit-testable:
 *
 *  1. **Step cap** — a hard ceiling (~25) on tool calls per task; the model is buggy or
 *     looping if it exceeds it.
 *  2. **Repeat / stuck detection** — a ring buffer of recent `(toolName + argsHash + screenHash)`
 *     fingerprints. Issuing the *same* action against an *unchanged* screen
 *     [repeatThreshold] times in a row means the model is spinning and should be nudged or
 *     stopped.
 *  3. **Cancellation flag** — a thread-safe one-way latch (set by barge-in /
 *     `toolCallCancellation` / the kill switch) that the loop polls before each step.
 *
 * Usage per step:
 * ```
 * when (val v = loop.onBeforeStep(name, args, lastScreen)) {
 *     is StepVerdict.Proceed   -> execute(...)            // then loop.onAfterStep()
 *     is StepVerdict.Cancelled -> abort()
 *     is StepVerdict.StepCapReached, is StepVerdict.Stuck -> finish(v.reason)
 * }
 * ```
 *
 * @param maxSteps          hard cap on tool calls before the loop is force-terminated.
 * @param repeatThreshold   identical (action,args,screen) fingerprints in a row that count
 *                          as "stuck".
 * @param ringSize          how many recent fingerprints to remember for repeat detection.
 */
class AgentLoopController(
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val repeatThreshold: Int = DEFAULT_REPEAT_THRESHOLD,
    private val ringSize: Int = DEFAULT_RING_SIZE
) {

    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        require(repeatThreshold >= 2) { "repeatThreshold must be at least 2" }
        require(ringSize >= repeatThreshold) { "ringSize must be >= repeatThreshold" }
    }

    private val cancelled = AtomicBoolean(false)
    private val ring = ArrayDeque<String>(ringSize)

    /** Tool calls dispatched so far in the current task. */
    var stepCount: Int = 0
        private set

    /** True once [cancel] has been called for the current task. */
    val isCancelled: Boolean get() = cancelled.get()

    /** Steps remaining before the hard cap is hit (never negative). */
    val remainingSteps: Int get() = (maxSteps - stepCount).coerceAtLeast(0)

    /**
     * Decide whether the loop may dispatch the next tool call. Records the step's
     * fingerprint as a side effect when it returns [StepVerdict.Proceed] or
     * [StepVerdict.Stuck] (so a recovered loop's later identical steps are still tracked).
     *
     * @param toolName the tool about to be called.
     * @param args     its arguments (hashed; never stored raw).
     * @param screen   the screen the model was looking at when it chose this action.
     */
    fun onBeforeStep(toolName: String, args: Map<String, Any?>, screen: ScreenState?): StepVerdict {
        if (cancelled.get()) return StepVerdict.Cancelled
        if (stepCount >= maxSteps) return StepVerdict.StepCapReached(maxSteps)

        val fingerprint = fingerprint(toolName, args, screen)
        val repeats = countTrailing(fingerprint) + 1 // include the step we're about to take

        stepCount++
        pushRing(fingerprint)

        return if (repeats >= repeatThreshold) {
            StepVerdict.Stuck(toolName, repeats)
        } else {
            StepVerdict.Proceed(stepCount)
        }
    }

    /**
     * Latch cancellation. Idempotent and thread-safe; safe to call from the WebSocket
     * reader thread on a `toolCallCancellation` or from the kill switch.
     */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Reset all state for a brand-new task (clears the cancellation latch, step counter,
     * and the fingerprint ring). Call when a new hands-free task begins.
     */
    fun reset() {
        cancelled.set(false)
        stepCount = 0
        ring.clear()
    }

    // --- internals -----------------------------------------------------------

    private fun pushRing(fingerprint: String) {
        if (ring.size == ringSize) ring.removeFirst()
        ring.addLast(fingerprint)
    }

    /** How many of the most recent fingerprints (from the tail) equal [fingerprint]. */
    private fun countTrailing(fingerprint: String): Int {
        var n = 0
        val it = ring.descendingIterator()
        while (it.hasNext()) {
            if (it.next() == fingerprint) n++ else break
        }
        return n
    }

    private fun fingerprint(toolName: String, args: Map<String, Any?>, screen: ScreenState?): String =
        "$toolName#${argsHash(args)}#${screenHash(screen)}"

    private companion object {
        const val DEFAULT_MAX_STEPS = 25
        const val DEFAULT_REPEAT_THRESHOLD = 3
        const val DEFAULT_RING_SIZE = 8

        /** Order-independent hash of args; tolerant of the loosely-typed decoded map. */
        fun argsHash(args: Map<String, Any?>): Int =
            args.entries
                .sortedBy { it.key }
                .fold(7) { acc, (k, v) -> acc * 31 + k.hashCode() * 31 + (v?.toString()?.hashCode() ?: 0) }

        /**
         * Stable hash of the *meaningful* screen content so an unchanged screen yields the
         * same value across reads (indices/timestamps deliberately excluded). A null screen
         * hashes to 0 so "no observation" repeats are still detected.
         */
        fun screenHash(screen: ScreenState?): Int {
            if (screen == null) return 0
            var h = screen.packageName?.hashCode() ?: 0
            h = h * 31 + (screen.activity?.hashCode() ?: 0)
            for (e in screen.elements) {
                h = h * 31 + e.role.hashCode()
                h = h * 31 + (e.text?.hashCode() ?: 0)
                h = h * 31 + (e.contentDescription?.hashCode() ?: 0)
                h = h * 31 + (e.resourceId?.hashCode() ?: 0)
            }
            return h
        }
    }
}

/**
 * The decision [AgentLoopController.onBeforeStep] returns for one prospective tool call.
 */
sealed interface StepVerdict {
    /** A short human-readable reason, for narration / audit. */
    val reason: String

    /** Dispatch the tool. [step] is the 1-based index of this step. */
    data class Proceed(val step: Int) : StepVerdict {
        override val reason: String get() = "proceeding with step $step"
    }

    /** The cancellation latch is set (barge-in / kill switch) — abort without dispatching. */
    data object Cancelled : StepVerdict {
        override val reason: String get() = "cancelled"
    }

    /** The hard step cap was reached — terminate the task. */
    data class StepCapReached(val maxSteps: Int) : StepVerdict {
        override val reason: String get() = "reached the $maxSteps-step limit"
    }

    /**
     * The same action on an unchanged screen repeated [repeats] times — the model is
     * spinning. Terminate or inject a corrective hint.
     */
    data class Stuck(val toolName: String, val repeats: Int) : StepVerdict {
        override val reason: String get() = "stuck: '$toolName' repeated $repeats times on an unchanged screen"
    }
}
