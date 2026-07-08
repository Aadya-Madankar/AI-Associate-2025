package com.example.permission

/**
 * Throttles the agent and arms the auto-fallback to the safe [AutonomyMode.ASK] mode.
 * Combines three independent guards (ARCHITECTURE.md §4.4), all reset per hands-free
 * session via [resetSession]:
 *
 *  1. **Token bucket** — a refilling bucket caps the sustained action rate so a
 *     runaway loop can't fire hundreds of actions per second. Refills at [refillPerSec]
 *     tokens/second up to [capacity]; each action consumes one token.
 *  2. **Consecutive-irreversible cap** — at most [maxConsecutiveIrreversible]
 *     irreversible/outbound actions may run back-to-back without an intervening
 *     reversible action. Stops "send, send, send, send…" chains.
 *  3. **Auto-fallback counters** — [maxConsecutiveBlocks] consecutive blocks
 *     (default 3) OR [maxBlocksPerSession] total blocks per session (default 20) trips
 *     the fallback: [onBlock] returns true, and the caller reverts the mode to ASK
 *     (via `AutonomyModeStore.resetToSafeDefault()`).
 *
 * Thread-safe: all counters are atomic / synchronized so the executor and the loop
 * controller can call from different coroutines.
 *
 * @param capacity                   max tokens (burst size).
 * @param refillPerSec               tokens added per second.
 * @param maxConsecutiveIrreversible back-to-back irreversible action cap.
 * @param maxConsecutiveBlocks       consecutive blocks that force a fallback (default 3).
 * @param maxBlocksPerSession        total blocks per session that force a fallback (default 20).
 * @param nowMs                      injectable clock for testing.
 */
class RateLimiter(
    private val capacity: Int = 8,
    private val refillPerSec: Double = 1.5,
    private val maxConsecutiveIrreversible: Int = 2,
    private val maxConsecutiveBlocks: Int = 3,
    private val maxBlocksPerSession: Int = 20,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** Why a [tryConsume] call was refused. */
    enum class Rejection { NONE, RATE_LIMITED, CONSECUTIVE_IRREVERSIBLE_CAP }

    /** Outcome of attempting to consume budget for an action. */
    data class Verdict(val allowed: Boolean, val rejection: Rejection = Rejection.NONE)

    private val lock = Any()
    private var tokens: Double = capacity.toDouble()
    private var lastRefillMs: Long = nowMs()

    private var consecutiveIrreversible: Int = 0

    private var consecutiveBlocks: Int = 0
    private var sessionBlocks: Int = 0

    /**
     * Attempts to reserve budget for one action. Call this only after the
     * [PermissionEngine] has decided to Allow/Confirm-then-allow. Refunds nothing on
     * failure — a rejected action simply doesn't run.
     *
     * @param irreversible whether the action is irreversible/outbound (affects the
     *                     consecutive-irreversible cap and resets it when reversible).
     */
    fun tryConsume(irreversible: Boolean): Verdict = synchronized(lock) {
        refill()

        if (irreversible && consecutiveIrreversible >= maxConsecutiveIrreversible) {
            return Verdict(allowed = false, rejection = Rejection.CONSECUTIVE_IRREVERSIBLE_CAP)
        }
        if (tokens < 1.0) {
            return Verdict(allowed = false, rejection = Rejection.RATE_LIMITED)
        }

        tokens -= 1.0
        consecutiveIrreversible = if (irreversible) consecutiveIrreversible + 1 else 0
        return Verdict(allowed = true)
    }

    /**
     * Records that an action ran successfully (or at least was permitted). Resets the
     * consecutive-block streak — a successful action breaks a run of blocks. Does not
     * touch the token bucket (already consumed in [tryConsume]).
     */
    fun onActionAllowed() = synchronized(lock) {
        consecutiveBlocks = 0
    }

    /**
     * Records a block (secure-context block, deny rule, or user denial). Bumps both the
     * consecutive and per-session counters. Returns true if either threshold was
     * reached, meaning this block triggered the fallback.
     */
    fun onBlock(): Boolean = synchronized(lock) {
        val consec = ++consecutiveBlocks
        val session = ++sessionBlocks
        return consec >= maxConsecutiveBlocks || session >= maxBlocksPerSession
    }

    /** Current counter snapshot, for surfacing in the audit/status UI. */
    fun snapshot(): Snapshot = synchronized(lock) {
        refill()
        Snapshot(
            tokens = tokens,
            consecutiveIrreversible = consecutiveIrreversible,
            consecutiveBlocks = consecutiveBlocks,
            sessionBlocks = sessionBlocks
        )
    }

    /**
     * Resets every per-session guard: refills the bucket and clears the irreversible
     * streak and both block counters. Call at the start of each hands-free session and
     * after acting on a fallback (ARCHITECTURE.md §4.4).
     */
    fun resetSession() = synchronized(lock) {
        tokens = capacity.toDouble()
        lastRefillMs = nowMs()
        consecutiveIrreversible = 0
        consecutiveBlocks = 0
        sessionBlocks = 0
    }

    private fun refill() {
        val now = nowMs()
        val elapsedMs = now - lastRefillMs
        if (elapsedMs <= 0) return
        val added = (elapsedMs / 1000.0) * refillPerSec
        if (added > 0) {
            tokens = (tokens + added).coerceAtMost(capacity.toDouble())
            lastRefillMs = now
        }
    }

    /** Immutable counter snapshot. */
    data class Snapshot(
        val tokens: Double,
        val consecutiveIrreversible: Int,
        val consecutiveBlocks: Int,
        val sessionBlocks: Int
    )
}
