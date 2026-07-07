package com.example.agent

import android.graphics.Rect
import com.example.accessibility.ScreenState
import com.example.accessibility.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AgentLoopController]'s three safety guard rails: the hard step cap,
 * repeat/stuck detection on an unchanged screen, cancellation, and [AgentLoopController.reset].
 *
 * Robolectric is required only because the fixtures build real [ScreenState]/[UiElement]
 * values, whose [UiElement.bounds] is an `android.graphics.Rect`. The controller itself is
 * pure logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLoopControllerTest {

    private fun screen(
        pkg: String = "com.example.app",
        elements: List<UiElement> = listOf(element(text = "Hello"))
    ): ScreenState = ScreenState(packageName = pkg, activity = "MainActivity", elements = elements)

    private fun element(
        index: Int = 0,
        role: String = "Button",
        text: String? = null
    ): UiElement = UiElement(
        index = index,
        role = role,
        text = text,
        bounds = Rect(0, 0, 100, 100)
    )

    // 1. Step cap is enforced: StepCapReached once stepCount hits maxSteps.

    @Test
    fun `step cap is enforced after maxSteps proceeds`() {
        // Large ring + high repeatThreshold so repeats never trip Stuck and obscure the cap.
        val loop = AgentLoopController(maxSteps = 4, repeatThreshold = 50, ringSize = 50)

        // Vary args each call so no Stuck verdict can occur; exactly maxSteps Proceeds.
        for (i in 1..4) {
            val v = loop.onBeforeStep("tap", mapOf("index" to i), screen())
            assertTrue("step $i should Proceed, was $v", v is StepVerdict.Proceed)
            assertEquals(i, (v as StepVerdict.Proceed).step)
        }
        assertEquals(4, loop.stepCount)
        assertEquals(0, loop.remainingSteps)

        // The (maxSteps + 1)-th attempt is rejected by the cap and does not advance the counter.
        val capped = loop.onBeforeStep("tap", mapOf("index" to 99), screen())
        assertTrue("expected StepCapReached, was $capped", capped is StepVerdict.StepCapReached)
        assertEquals(4, (capped as StepVerdict.StepCapReached).maxSteps)
        assertEquals(4, loop.stepCount)
    }

    // 2. Repeating the same action on an unchanged screen trips Stuck.

    @Test
    fun `repeating identical action on unchanged screen trips Stuck`() {
        val loop = AgentLoopController(maxSteps = 25, repeatThreshold = 3, ringSize = 8)
        val s = screen()
        val args = mapOf("index" to 7)

        // First two identical steps are below the threshold -> Proceed.
        assertTrue(loop.onBeforeStep("tap", args, s) is StepVerdict.Proceed)
        assertTrue(loop.onBeforeStep("tap", args, s) is StepVerdict.Proceed)

        // Third identical (action, args, screen) in a row -> Stuck.
        val stuck = loop.onBeforeStep("tap", args, s)
        assertTrue("expected Stuck, was $stuck", stuck is StepVerdict.Stuck)
        stuck as StepVerdict.Stuck
        assertEquals("tap", stuck.toolName)
        assertEquals(3, stuck.repeats)
    }

    @Test
    fun `a changed screen breaks the repeat streak and avoids Stuck`() {
        val loop = AgentLoopController(maxSteps = 25, repeatThreshold = 3, ringSize = 8)
        val args = mapOf("index" to 7)
        val first = screen(elements = listOf(element(text = "Hello")))
        // Meaningfully different content (different element text) -> different screen hash.
        val changed = screen(elements = listOf(element(text = "World")))

        assertTrue(loop.onBeforeStep("tap", args, first) is StepVerdict.Proceed)
        assertTrue(loop.onBeforeStep("tap", args, first) is StepVerdict.Proceed)
        // Screen changed between the 2nd and 3rd identical action -> streak resets, no Stuck.
        assertTrue(loop.onBeforeStep("tap", args, changed) is StepVerdict.Proceed)
    }

    // 3. reset() clears counters (and the cancellation latch and fingerprint ring).

    @Test
    fun `reset clears step counter cancellation and stuck history`() {
        val loop = AgentLoopController(maxSteps = 25, repeatThreshold = 3, ringSize = 8)
        val s = screen()
        val args = mapOf("index" to 1)

        // Build up some state: two steps and a cancellation.
        loop.onBeforeStep("tap", args, s)
        loop.onBeforeStep("tap", args, s)
        loop.cancel()
        assertTrue(loop.isCancelled)
        assertEquals(2, loop.stepCount)

        loop.reset()

        assertEquals(0, loop.stepCount)
        assertEquals(25, loop.remainingSteps)
        assertFalse(loop.isCancelled)

        // The fingerprint ring was cleared: the next identical run starts fresh and Proceeds,
        // not Stuck (which would require 3 prior identical entries).
        assertTrue(loop.onBeforeStep("tap", args, s) is StepVerdict.Proceed)
        assertEquals(1, loop.stepCount)
    }

    // 4. cancel() yields Cancelled on the next step decision.

    @Test
    fun `cancel yields Cancelled and does not consume a step`() {
        val loop = AgentLoopController(maxSteps = 25, repeatThreshold = 3, ringSize = 8)

        loop.cancel()
        assertTrue(loop.isCancelled)

        val v = loop.onBeforeStep("tap", mapOf("index" to 0), screen())
        assertTrue("expected Cancelled, was $v", v is StepVerdict.Cancelled)
        // Cancellation short-circuits before the counter is touched.
        assertEquals(0, loop.stepCount)
    }

    @Test
    fun `cancellation takes precedence over the step cap`() {
        val loop = AgentLoopController(maxSteps = 1, repeatThreshold = 3, ringSize = 8)
        loop.onBeforeStep("tap", mapOf("index" to 0), screen()) // consume the only step
        assertEquals(0, loop.remainingSteps)

        loop.cancel()
        // Even though the cap is hit, Cancelled is returned first.
        val v = loop.onBeforeStep("tap", mapOf("index" to 0), screen())
        assertTrue("expected Cancelled, was $v", v is StepVerdict.Cancelled)
    }
}
