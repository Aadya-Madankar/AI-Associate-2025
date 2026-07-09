package com.example.skill

import androidx.test.core.app.ApplicationProvider
import com.example.agent.ToolResult
import com.example.skill.tools.ListSkillsTool
import com.example.skill.tools.RecallSkillTool
import com.example.skill.tools.SaveSkillTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillSaveRecallTest {
    @Test
    fun save_persists_and_recall_returns_steps() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val saveStore = JsonFileSkillStore(ctx)
        val save = SaveSkillTool(saveStore).execute(
            "c1",
            mapOf(
                "name" to "morning routine",
                "description" to "open maps then play music",
                "steps" to listOf(
                    mapOf("tool" to "open_app", "args" to mapOf("package" to "com.google.android.apps.maps")),
                    mapOf("tool" to "media_play_pause", "args" to emptyMap<String, Any?>())
                )
            )
        )
        assertTrue(save is ToolResult.Success)
        val recallStore = JsonFileSkillStore(ctx)
        val list = ListSkillsTool(recallStore).execute("c2", emptyMap())
        list as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val skills = list.data["skills"] as List<Map<String, Any?>>
        assertEquals(1, skills.size)
        assertEquals("morning routine", skills[0]["name"])
        val recall = RecallSkillTool(recallStore).execute("c3", mapOf("name" to "morning routine"))
        recall as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val steps = recall.data["steps"] as List<Map<String, Any?>>
        assertEquals(2, steps.size)
        assertEquals("open_app", steps[0]["tool"])
        @Suppress("UNCHECKED_CAST")
        val args0 = steps[0]["args"] as Map<String, Any?>
        assertEquals("com.google.android.apps.maps", args0["package"])
    }
}
