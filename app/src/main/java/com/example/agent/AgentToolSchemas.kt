package com.example.agent

/**
 * Canonical JSON-Schema (draft-07 subset) strings for every agent tool's `parameters`
 * object, plus the canonical tool names.
 *
 * Gemini function declarations require an OpenAPI/JSON-Schema description of each tool's
 * arguments. The concrete [AgentTool] implementations in `com.example.agent.tools.*` each
 * embed their own schema; this object is the central, dependency-free reference used by:
 *  - [ToolRegistry] for the synthetic `task_complete` declaration, and
 *  - the gemini-tool-protocol stream / [GeminiToolMapper] when converting a
 *    [ToolDeclaration] to a `LiveTool` DTO.
 *
 * Keeping the schemas here (as plain strings, no Android/Moshi dependency) means they are
 * trivially unit-testable and identical regardless of which side builds the declaration.
 * All schemas are objects with `additionalProperties:false` so the model cannot smuggle
 * extra keys past the executor.
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

    // --- Parameter schemas ---------------------------------------------------

    /** No-arg tools (`get_screen`, `press_*`, `open_notifications`). */
    val EMPTY: String = """
{
  "type": "object",
  "properties": {}
}
""".trimIndent()

    val GET_SCREEN_SCHEMA: String = EMPTY
    val PRESS_BACK_SCHEMA: String = EMPTY
    val PRESS_HOME_SCHEMA: String = EMPTY
    val PRESS_RECENTS_SCHEMA: String = EMPTY
    val OPEN_NOTIFICATIONS_SCHEMA: String = EMPTY

    val TAP_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "Element index from the latest get_screen result."
    }
  },
  "required": [
    "index"
  ]
}
""".trimIndent()

    val LONG_PRESS_SCHEMA: String = TAP_SCHEMA

    val INPUT_TEXT_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "Editable element index from the latest get_screen result."
    },
    "text": {
      "type": "string",
      "description": "The text to type into the field."
    }
  },
  "required": [
    "index",
    "text"
  ]
}
""".trimIndent()

    val SCROLL_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "index": {
      "type": "integer",
      "description": "Scrollable element index from the latest get_screen result."
    },
    "forward": {
      "type": "boolean",
      "description": "true to scroll down/forward, false to scroll up/back."
    }
  },
  "required": [
    "index",
    "forward"
  ]
}
""".trimIndent()

    val SWIPE_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "x1": {
      "type": "integer",
      "description": "Start X in screen pixels."
    },
    "y1": {
      "type": "integer",
      "description": "Start Y in screen pixels."
    },
    "x2": {
      "type": "integer",
      "description": "End X in screen pixels."
    },
    "y2": {
      "type": "integer",
      "description": "End Y in screen pixels."
    },
    "durationMs": {
      "type": "integer",
      "description": "Gesture duration in milliseconds (default 300)."
    }
  },
  "required": [
    "x1",
    "y1",
    "x2",
    "y2"
  ]
}
""".trimIndent()

    val OPEN_APP_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "package": {
      "type": "string",
      "description": "Android package id, e.g. com.spotify.music."
    },
    "appName": {
      "type": "string",
      "description": "Human app name, used only if package is absent."
    }
  },
  "required": []
}
""".trimIndent()

    val OPEN_URL_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "description": "Absolute URL, e.g. https://example.com."
    }
  },
  "required": [
    "url"
  ]
}
""".trimIndent()

    val WEB_SEARCH_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "What to search for."
    }
  },
  "required": [
    "query"
  ]
}
""".trimIndent()

    val TORCH_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "on": {
      "type": "boolean",
      "description": "true to switch the flashlight on, false to switch it off."
    }
  },
  "required": [
    "on"
  ]
}
""".trimIndent()

    val SET_DND_SCHEMA: String = """
        {
          "type": "object",
          "properties": {
            "on": { "type": "boolean", "description": "true to enable Do-Not-Disturb, false to disable it." }
          },
          "required": ["on"]
        }
    """.trimIndent()

    /**
     * `save_skill` — persist a named, ordered bundle of tool calls to on-device JSON memory.
     * `steps[*].args` is an open object (it holds arbitrary tool arguments), so only the
     * outer object enforces `additionalProperties:false`.
     */
    val SAVE_SKILL_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "Short unique name for the skill; saving with an existing name overwrites it."
    },
    "description": {
      "type": "string",
      "description": "What the skill does, in one sentence."
    },
    "steps": {
      "type": "array",
      "description": "Ordered tool calls that make up the skill.",
      "items": {
        "type": "object",
        "properties": {
          "tool": {
            "type": "string",
            "description": "Name of the tool to invoke for this step."
          },
          "args": {
            "type": "object",
            "description": "Arguments for the tool, as a JSON object."
          }
        },
        "required": [
          "tool"
        ]
      }
    }
  },
  "required": [
    "name",
    "description",
    "steps"
  ]
}
""".trimIndent()

    /** `recall_skill` — fetch one saved skill's steps by name (returns the recipe; runs nothing). */
    val RECALL_SKILL_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "Exact name of the skill to recall."
    }
  },
  "required": [
    "name"
  ]
}
""".trimIndent()

    /** `list_skills` — enumerate saved skills. No arguments. */
    val LIST_SKILLS_SCHEMA: String = EMPTY

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

    /**
     * The full map of tool name → parameter schema. Convenient for tests and for any
     * consumer that wants to validate/declare the whole catalog at once.
     */
    val byName: Map<String, String> = mapOf(
        GET_SCREEN to GET_SCREEN_SCHEMA,
        TAP to TAP_SCHEMA,
        INPUT_TEXT to INPUT_TEXT_SCHEMA,
        SWIPE to SWIPE_SCHEMA,
        SCROLL to SCROLL_SCHEMA,
        LONG_PRESS to LONG_PRESS_SCHEMA,
        PRESS_BACK to PRESS_BACK_SCHEMA,
        PRESS_HOME to PRESS_HOME_SCHEMA,
        PRESS_RECENTS to PRESS_RECENTS_SCHEMA,
        OPEN_NOTIFICATIONS to OPEN_NOTIFICATIONS_SCHEMA,
        OPEN_APP to OPEN_APP_SCHEMA,
        OPEN_URL to OPEN_URL_SCHEMA,
        WEB_SEARCH to WEB_SEARCH_SCHEMA,
        TORCH to TORCH_SCHEMA,
        SET_DND to SET_DND_SCHEMA,
        SAVE_SKILL to SAVE_SKILL_SCHEMA,
        RECALL_SKILL to RECALL_SKILL_SCHEMA,
        LIST_SKILLS to LIST_SKILLS_SCHEMA,
        TASK_COMPLETE to TASK_COMPLETE_SCHEMA
    )

    /** Schema for [toolName], or [EMPTY] if the name is unknown (a no-arg fallback). */
    fun schemaFor(toolName: String): String = byName[toolName] ?: EMPTY
}
