package com.example.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for [RateLimiter] safety guards.
 *
 * Pure-logic target (atomic counters + a token bucket + a kotlinx StateFlow latch);
 * no android.* dependencies, so no Robolectric runner is required.
 *
 * The clock is pinned so the token bucket never refills between calls — that isolates
 * the consecutive-irreversible cap and block counters from rate-limiting noise.
 */
class RateLimiterTest {

    /** A limiter with a frozen clock and a roomy bucket, so only the targeted guard fires. */
    private fun limiter(
        capacity: Int = 100,
        maxConsecutiveIrreversible: Int = 2,
        maxConsecutiveBlocks: Int = 3,
        maxBlocksPerSession: Int = 20
    ) = RateLimiter(
        capacity = capacity,
        refillPerSec = 1.5,
        maxConsecutiveIrreversible = maxConsecutiveIrreversible,
        maxConsecutiveBlocks = maxConsecutiveBlocks,
        maxBlocksPerSession = maxBlocksPerSession,
        nowMs = { 0L } // frozen clock: no refill between calls
    )

    // ---- 1. Consecutive irreversible actions hit the cap -------------------------------

    @Test
    fun consecutiveIrreversibleActions_hitTheCap() {
        val rl = limiter(maxConsecutiveIrreversible = 2)

        // The first two back-to-back irreversible actions are allowed.
        val first = rl.tryConsume(irreversible = true)
        assertTrue("1st irreversible action should be allowed", first.allowed)
        assertEquals(RateLimiter.Rejection.NONE, first.rejection)

        val second = rl.tryConsume(irreversible = true)
        assertTrue("2nd irreversible action should be allowed", second.allowed)
        assertEquals(RateLimiter.Rejection.NONE, second.rejection)

        // The third hits the consecutive-irreversible cap.
        val third = rl.tryConsume(irreversible = true)
        assertFalse("3rd back-to-back irreversible action must be blocked", third.allowed)
        assertEquals(
            RateLimiter.Rejection.CONSECUTIVE_IRREVERSIBLE_CAP,
            third.rejection
        )
    }

    @Test
    fun reversibleAction_resetsConsecutiveIrreversibleStreak() {
        val rl = limiter(maxConsecutiveIrreversible = 2)

        assertTrue(rl.tryConsume(irreversible = true).allowed)
        assertTrue(rl.tryConsume(irreversible = true).allowed)

        // A reversible action breaks the streak.
        assertTrue("reversible action should be allowed", rl.tryConsume(irreversible = false).allowed)
        assertEquals(0, rl.snapshot().consecutiveIrreversible)

        // After the break, irreversible actions are permitted again up to the cap.
        assertTrue(rl.tryConsume(irreversible = true).allowed)
        assertTrue(rl.tryConsume(irreversible = true).allowed)
        assertEquals(
            RateLimiter.Rejection.CONSECUTIVE_IRREVERSIBLE_CAP,
            rl.tryConsume(irreversible = true).rejection
        )
    }

    // ---- 2. 3-consecutive-block auto-fallback fires ------------------------------------

    @Test
    fun threeConsecutiveBlocks_tripsFallback_andOnBlockReturnsTrueAtThreshold() {
        val rl = limiter(maxConsecutiveBlocks = 3, maxBlocksPerSession = 20)

        // First two blocks do not yet trip the fallback.
        assertFalse("1st block must not trip fallback", rl.onBlock())
        assertFalse("2nd block must not trip fallback", rl.onBlock())
        assertFalse(rl.shouldFallbackToAsk())
        assertFalse(rl.fallbackTriggered.value)

        // The 3rd consecutive block hits the threshold -> onBlock() returns true.
        assertTrue("3rd consecutive block must trip fallback", rl.onBlock())

        // The latch is set and observable via both the method and the StateFlow.
        assertTrue(rl.shouldFallbackToAsk())
        assertTrue(rl.fallbackTriggered.value)
    }

    @Test
    fun allowedAction_resetsConsecutiveBlockStreak() {
        val rl = limiter(maxConsecutiveBlocks = 3, maxBlocksPerSession = 20)

        assertFalse(rl.onBlock())
        assertFalse(rl.onBlock())

        // A successful/allowed action breaks the run of blocks...
        rl.onActionAllowed()
        assertEquals(0, rl.snapshot().consecutiveBlocks)

        // ...so it now takes a fresh run of 3 to trip the fallback again.
        assertFalse("block after reset streak must not trip", rl.onBlock())
        assertFalse("2nd block in fresh streak must not trip", rl.onBlock())
        assertFalse(rl.shouldFallbackToAsk())
        assertTrue("3rd block in fresh streak must trip", rl.onBlock())
        assertTrue(rl.shouldFallbackToAsk())
    }

    @Test
    fun perSessionBlockCap_tripsFallback_evenWithoutConsecutiveStreak() {
        // Consecutive cap high enough that only the per-session cap can fire.
        val rl = limiter(maxConsecutiveBlocks = 1000, maxBlocksPerSession = 5)

        repeat(4) { i ->
            assertFalse("block ${i + 1} must not yet trip per-session cap", rl.onBlock())
            // Break the consecutive streak so only the session counter accumulates.
            rl.onActionAllowed()
        }

        // The 5th total block in the session trips the per-session fallback.
        assertTrue("5th per-session block must trip fallback", rl.onBlock())
        assertTrue(rl.shouldFallbackToAsk())
    }

    // ---- 3. resetSession clears counters ----------------------------------------------

    @Test
    fun resetSession_clearsAllCountersAndLowersFallbackLatch() {
        val rl = limiter(
            capacity = 4,
            maxConsecutiveIrreversible = 2,
            maxConsecutiveBlocks = 3,
            maxBlocksPerSession = 20
        )

        // Build up state across every guard.
        rl.tryConsume(irreversible = true) // consumes a token + bumps irreversible streak
        rl.tryConsume(irreversible = true)
        rl.onBlock()
        rl.onBlock()
        rl.onBlock() // trips fallback

        val before = rl.snapshot()
        assertTrue("precondition: fallback should be latched", before.fallbackTriggered)
        assertEquals(2, before.consecutiveIrreversible)
        assertEquals(3, before.consecutiveBlocks)
        assertEquals(3, before.sessionBlocks)
        assertTrue(rl.shouldFallbackToAsk())

        rl.resetSession()

        // Every counter is cleared, the latch is lowered, and the bucket is refilled.
        val after = rl.snapshot()
        assertEquals(0, after.consecutiveIrreversible)
        assertEquals(0, after.consecutiveBlocks)
        assertEquals(0, after.sessionBlocks)
        assertFalse("fallback latch must be lowered after reset", after.fallbackTriggered)
        assertFalse(rl.shouldFallbackToAsk())
        assertFalse(rl.fallbackTriggered.value)
        assertEquals(4.0, after.tokens, 1e-9) // bucket refilled to capacity

        // The reset is functional: irreversible actions are allowed again from scratch.
        assertTrue("irreversible action allowed after reset", rl.tryConsume(irreversible = true).allowed)
    }
}
