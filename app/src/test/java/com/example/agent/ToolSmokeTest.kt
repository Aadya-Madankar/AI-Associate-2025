package com.example.agent

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolSmokeTest {

    private val accessibilityBacked = setOf(
        "get_screen", "tap", "input_text", "scroll", "swipe", "long_press",
        "press_back", "press_home", "press_recents", "open_notifications", "take_screenshot"
    )

    private fun representativeArgs(name: String): Map<String, Any?> = when (name) {
        "tap" -> mapOf("index" to 0)
        "input_text" -> mapOf("index" to 0, "text" to "hello")
        "scroll" -> mapOf("index" to 0, "forward" to true)
        "swipe" -> mapOf("x1" to 0, "y1" to 0, "x2" to 100, "y2" to 100)
        "long_press" -> mapOf("index" to 0)
        "open_app" -> mapOf("package" to "com.android.settings")
        "open_url" -> mapOf("url" to "https://example.com")
        "web_search" -> mapOf("query" to "test query")
        "torch" -> mapOf("on" to true)
        "set_brightness" -> mapOf("level" to 50)
        "set_dnd" -> mapOf("on" to true)
        "set_volume" -> mapOf("level" to 5)
        "navigate" -> mapOf("destination" to "1600 Amphitheatre Parkway")
        "open_settings" -> mapOf("page" to "wifi")
        "email_draft" -> mapOf("to" to "smoke@example.com")
        "sms_draft" -> mapOf("number" to "+14155550100", "body" to "hi")
        "dial" -> mapOf("number" to "+14155550100")
        "add_event" -> mapOf("title" to "Smoke event", "beginMs" to System.currentTimeMillis())
        "set_alarm" -> mapOf("hour" to 7, "minute" to 30)
        "set_timer" -> mapOf("seconds" to 60)
        "share" -> mapOf("text" to "hello")
        "save_skill" -> mapOf(
            "name" to "smoke skill",
            "description" to "a smoke-tested skill",
            "steps" to listOf(mapOf("tool" to "press_back", "args" to emptyMap<String, Any?>()))
        )
        "recall_skill" -> mapOf("name" to "smoke skill")
        "update_self_prompt" -> mapOf("directive" to "be concise")
        // Long-term memory tools.
        "remember" -> mapOf("text" to "the user likes tea")
        "recall" -> mapOf("query" to "tea")
        "note_intention" -> mapOf("text" to "remind me to call mom")
        "forget" -> mapOf("ref" to "1")
        // get_screen, press_back, press_home, press_recents, open_notifications,
        // take_screenshot, media_play_pause, open_wifi_panel, open_bluetooth_settings,
        // list_skills all take no required arguments.
        else -> emptyMap()
    }

    @Test
    fun every_registered_tool_executes_without_throwing() = runTest {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        val names = registry.declarations().map { it.name }.filter { it != TASK_COMPLETE }
        assertEquals(38, names.size) // 34 base tools + 4 long-term-memory tools

        for (name in names) {
            val tool = registry.byName(name)
            assertNotNull("no tool registered for '$name'", tool)

            val result = tool!!.execute("smoke", representativeArgs(name))
            assertNotNull("execute('$name') returned null", result)

            if (name in accessibilityBacked) {
                assertTrue(
                    "expected '$name' to fail cleanly with no AccessibilityService bound, got $result",
                    result is ToolResult.Failure
                )
            }
        }
    }
}
