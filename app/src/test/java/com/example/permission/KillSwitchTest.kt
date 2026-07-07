package com.example.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for [KillSwitch] — the global panic stop (ARCHITECTURE.md §4.4).
 *
 * Pure-logic target (StateFlow only, no android.*), so no Robolectric runner is needed.
 * Asserts the real safety behavior: trigger() flips the latch and fires listeners exactly
 * once with the reason, reset() re-arms, and a double-trigger never double-invokes.
 */
class KillSwitchTest {

    @Test
    fun trigger_flipsTrippedAndInvokesListenerOnceWithReason() {
        val killSwitch = KillSwitch()

        val received = mutableListOf<KillReason>()
        killSwitch.addListener { received.add(it) }

        // Cold-start: armed but not tripped.
        assertFalse(killSwitch.isTripped)
        assertFalse(killSwitch.tripped.value)

        killSwitch.trigger(KillReason.HARDWARE_TRIGGER)

        // Latch flipped via both the hot-path read and the StateFlow.
        assertTrue(killSwitch.isTripped)
        assertTrue(killSwitch.tripped.value)
        assertEquals(KillReason.HARDWARE_TRIGGER, killSwitch.lastReason.value)

        // Listener invoked exactly once, with the supplied reason.
        assertEquals(listOf(KillReason.HARDWARE_TRIGGER), received)
    }

    @Test
    fun trigger_defaultReasonIsUserStop() {
        val killSwitch = KillSwitch()
        val received = mutableListOf<KillReason>()
        killSwitch.addListener { received.add(it) }

        killSwitch.trigger()

        assertTrue(killSwitch.isTripped)
        assertEquals(KillReason.USER_STOP, killSwitch.lastReason.value)
        assertEquals(listOf(KillReason.USER_STOP), received)
    }

    @Test
    fun trigger_invokesOnTriggerCallbackOnceWithReason() {
        val callbackReasons = mutableListOf<KillReason>()
        val killSwitch = KillSwitch(onTrigger = { callbackReasons.add(it) })

        killSwitch.trigger(KillReason.SAFETY_FALLBACK)

        assertEquals(listOf(KillReason.SAFETY_FALLBACK), callbackReasons)
    }

    @Test
    fun reset_clearsTrippedAndReason() {
        val killSwitch = KillSwitch()
        killSwitch.trigger(KillReason.ERROR)
        assertTrue(killSwitch.isTripped)

        killSwitch.reset()

        assertFalse(killSwitch.isTripped)
        assertFalse(killSwitch.tripped.value)
        assertEquals(KillReason.NONE, killSwitch.lastReason.value)
    }

    @Test
    fun doubleTrigger_doesNotDoubleInvokeListeners() {
        val callbackReasons = mutableListOf<KillReason>()
        val killSwitch = KillSwitch(onTrigger = { callbackReasons.add(it) })

        val received = mutableListOf<KillReason>()
        killSwitch.addListener { received.add(it) }

        killSwitch.trigger(KillReason.USER_STOP)
        // Idempotent: second trigger on an already-tripped switch is a no-op.
        killSwitch.trigger(KillReason.HARDWARE_TRIGGER)

        // Listener and callback fired exactly once; the first reason wins and is retained.
        assertEquals(listOf(KillReason.USER_STOP), received)
        assertEquals(listOf(KillReason.USER_STOP), callbackReasons)
        assertEquals(KillReason.USER_STOP, killSwitch.lastReason.value)
        assertTrue(killSwitch.isTripped)
    }

    @Test
    fun reset_thenTrigger_reArmsAndFiresListenerAgain() {
        val killSwitch = KillSwitch()
        val received = mutableListOf<KillReason>()
        killSwitch.addListener { received.add(it) }

        killSwitch.trigger(KillReason.USER_STOP)
        killSwitch.reset()
        killSwitch.trigger(KillReason.SCREEN_OFF_OR_APP_LEFT)

        // Re-arming after a handled stop lets the latch fire (and notify) again.
        assertTrue(killSwitch.isTripped)
        assertEquals(
            listOf(KillReason.USER_STOP, KillReason.SCREEN_OFF_OR_APP_LEFT),
            received
        )
    }
}
