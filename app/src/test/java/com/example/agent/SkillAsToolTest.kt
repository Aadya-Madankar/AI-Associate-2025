package com.example.agent

import androidx.test.core.app.ApplicationProvider
import com.example.skill.JsonFileSkillStore
import com.example.skill.Skill
import com.example.skill.SkillStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the "XENO authors its own tools" path: a saved skill is advertised as a callable tool and
 * reverses cleanly back to its recipe. Cleans the on-device store before/after each test so it does
 * not perturb [ToolRegistryWiringTest] / [ToolSchemaContractTest], which assert the built-in set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillAsToolTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun store() = JsonFileSkillStore(ctx)

    @Before
    @After
    fun clearSkills() {
        val s = store()
        s.all().forEach { s.delete(it.name) }
    }

    @Test
    fun savedSkillIsAdvertisedAsCallableToolAndReverses() {
        store().save(
            Skill(
                name = "morning routine",
                description = "open maps then play music",
                steps = listOf(
                    SkillStep("open_app", mapOf("package" to "com.google.android.apps.maps")),
                    SkillStep("media_play_pause", emptyMap())
                ),
                createdAtMs = 0L
            )
        )

        val registry = ToolRegistry(ctx)
        val names = registry.declarations().map { it.name }
        assertTrue("saved skill advertised as skill_morning_routine", "skill_morning_routine" in names)

        val skill = registry.skillForToolName("skill_morning_routine")
        assertEquals("morning routine", skill?.name)
        assertEquals(2, skill?.steps?.size)
        assertEquals("open_app", skill?.steps?.get(0)?.tool)

        // A built-in tool name is not a skill.
        assertNull(registry.skillForToolName("open_app"))
    }

    @Test
    fun skillToolNameSanitisesFreeFormNames() {
        assertEquals("skill_morning_routine", ToolRegistry.skillToolName("Morning Routine!"))
        assertEquals("skill_pay_the_rent", ToolRegistry.skillToolName("  pay   the  rent  "))
    }
}
