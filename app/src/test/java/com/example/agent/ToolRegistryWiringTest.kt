package com.example.agent

import androidx.test.core.app.ApplicationProvider
import com.example.permission.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration check that [ToolRegistry] and [GeminiToolMapper] agree on the full tool set:
 * every tool the registry advertises to Gemini Live must resolve to a known [ActionType],
 * and the registry must actually contain all 38 tools (34 base + 4 long-term-memory tools) +
 * the synthetic `task_complete` declaration.
 *
 * Robolectric is required because [ToolRegistry] takes a real [android.content.Context].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRegistryWiringTest {

    @Test
    fun `every registered tool maps to a known action type`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        val mapper = GeminiToolMapper()
        val unknown = registry.declarations().map { it.name }
            .filter { mapper.actionTypeFor(it) == ActionType.UNKNOWN }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `registry advertises all 38 tools plus task_complete`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        assertEquals(39, registry.declarations().size)
    }
}
