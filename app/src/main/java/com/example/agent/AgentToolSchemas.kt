package com.example.agent

/**
 * Canonical tool-name constants shared by every agent tool, plus [TASK_COMPLETE_SCHEMA] for
 * the one synthetic tool that has no [AgentTool] instance of its own.
 *
 * The concrete [AgentTool] implementations in `com.example.agent.tools.*` (and
 * `com.example.skill.tools.*`) each embed their own parameter schema inline; this object is
 * the central, dependency-free reference used by:
 *  - [ToolRegistry] for the synthetic `task_complete` declaration, and
 *  - the gemini-tool-protocol stream / [GeminiToolMapper] when mapping a tool name to an
 *    [com.example.permission.ActionType].
 */
object AgentToolSchemas {

    // --- Canonical tool names ------------------------------------------------

    const val GET_SCREEN = "get_screen"
    const val TAP = "tap"
    const val INPUT_TEXT = "input_text"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val LONG_PRESS = "long_press"
    const val PRESS_BACK = "press_back"
    const val PRESS_HOME = "press_home"
    const val PRESS_RECENTS = "press_recents"
    const val OPEN_NOTIFICATIONS = "open_notifications"
    const val OPEN_APP = "open_app"
    const val OPEN_URL = "open_url"
    const val WEB_SEARCH = "web_search"
    const val TORCH = "torch"
    const val BRIGHTNESS = "set_brightness"
    const val SET_DND = "set_dnd"
    const val SAVE_SKILL = "save_skill"
    const val RECALL_SKILL = "recall_skill"
    const val LIST_SKILLS = "list_skills"
    const val EDIT_SELF_PROMPT = "update_self_prompt"
    const val TASK_COMPLETE = com.example.agent.TASK_COMPLETE

    // --- Parameter schema for the one synthetic tool with no AgentTool instance ----------

    val TASK_COMPLETE_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "success": {
      "type": "boolean",
      "description": "true if the user's task was completed successfully."
    },
    "summary": {
      "type": "string",
      "description": "A short, spoken-style summary of the outcome."
    }
  },
  "required": [
    "success",
    "summary"
  ]
}
""".trimIndent()
}
