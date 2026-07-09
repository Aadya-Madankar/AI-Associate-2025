package com.example.agent

import android.content.Context
import com.example.agent.tools.AddCalendarEventTool
import com.example.agent.tools.BluetoothSettingsTool
import com.example.agent.tools.BrightnessTool
import com.example.agent.tools.DialPrefillTool
import com.example.agent.tools.DndTool
import com.example.agent.tools.EmailDraftTool
import com.example.agent.tools.GetScreenTool
import com.example.agent.tools.InputTextTool
import com.example.agent.tools.LongPressTool
import com.example.agent.tools.MapsNavigateTool
import com.example.agent.tools.MediaPlayPauseTool
import com.example.agent.tools.MediaVolumeTool
import com.example.agent.tools.OpenAppTool
import com.example.agent.tools.OpenNotificationsTool
import com.example.agent.tools.OpenSettingsPageTool
import com.example.agent.tools.OpenUrlTool
import com.example.agent.tools.PressBackTool
import com.example.agent.tools.PressHomeTool
import com.example.agent.tools.PressRecentsTool
import com.example.agent.tools.ScrollTool
import com.example.agent.tools.SetAlarmTool
import com.example.agent.tools.SetTimerTool
import com.example.agent.tools.ShareTool
import com.example.agent.tools.SmsDraftTool
import com.example.agent.tools.SwipeTool
import com.example.agent.tools.TakeScreenshotTool
import com.example.agent.tools.TapTool
import com.example.agent.tools.TorchTool
import com.example.agent.tools.UpdateSelfPromptTool
import com.example.agent.tools.WebSearchTool
import com.example.agent.tools.WifiPanelTool
import com.example.config.PersonaStore
import com.example.memory.MemoryStore
import com.example.memory.tools.ForgetMemoryTool
import com.example.memory.tools.NoteIntentionTool
import com.example.memory.tools.RecallMemoryTool
import com.example.memory.tools.RememberFactTool
import com.example.skill.JsonFileSkillStore
import com.example.skill.Skill
import com.example.skill.SkillStore
import com.example.skill.tools.ListSkillsTool
import com.example.skill.tools.RecallSkillTool
import com.example.skill.tools.SaveSkillTool

/**
 * Builds and holds every [AgentTool] the phone-control agent can invoke, keyed by the
 * tool's declared name. A registry is constructed once from an Android [Context] (it uses
 * the application context internally so it can outlive the Activity) and then injected into
 * [DefaultPhoneControlExecutor].
 *
 * Tools live in the `com.example.agent.tools.*` package (see [AgentTool] KDoc) and are
 * instantiated here — this is the single place that knows the concrete tool set, so adding
 * a tool is a one-line change confined to [buildTools]. Lookup is by the tool's
 * [ToolDeclaration.name]; an unknown name returns `null`, letting the executor surface a
 * clean [ToolResult.Failure] rather than crashing (ARCHITECTURE.md §3.4).
 *
 * The synthetic `task_complete` tool is NOT registered here: it is a loop-terminating
 * signal handled by the agent loop / executor directly rather than a side-effecting
 * [AgentTool] (see [DefaultPhoneControlExecutor] and [AgentLoopController]).
 *
 * @param context any Context; the application context is captured so tools survive
 *                configuration changes and the creating Activity's lifecycle.
 */
class ToolRegistry(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * The one on-device skill store, shared by the save/list/recall tools AND used to advertise
     * every saved skill as its own callable tool (see [declarations] / [skillForToolName]).
     */
    private val skillStore: SkillStore = JsonFileSkillStore(appContext)

    /**
     * The one on-device long-term memory facade, shared by the remember/recall/note/forget tools.
     * Wraps the process-wide [com.example.memory.MemoryDatabase]; the coordinator/ViewModel use
     * their own facade over the same DB, so this second instance shares all state.
     */
    private val memoryStore: MemoryStore = MemoryStore(appContext)

    /**
     * Immutable name → tool map. Built eagerly so [declarations] and [byName] are cheap and
     * thread-safe to read from the WebSocket reader thread and the agent coroutine alike.
     */
    private val tools: Map<String, AgentTool> = buildTools(appContext, skillStore, memoryStore)
        .associateBy { it.declaration.name }

    /** Look up a tool by its declared name, or `null` if no such tool is registered. */
    fun byName(name: String): AgentTool? = tools[name]

    /**
     * All tool declarations to advertise in the Gemini Live setup request: the built-in tools,
     * the synthetic [TASK_COMPLETE_DECLARATION], and — the key to "XENO authors its own tools" —
     * one declaration per **saved skill**, so the model can call a routine it taught itself by
     * name. Skills saved mid-session appear on the next connect (the Live tool set is fixed at
     * setup). The [AgentCoordinator] recognises a skill call via [skillForToolName] and expands
     * it into its steps, each of which is re-gated by the permission engine — a skill grants no
     * new authority, it only chains existing tools.
     */
    fun declarations(): List<ToolDeclaration> =
        tools.values.map { it.declaration } + TASK_COMPLETE_DECLARATION + skillDeclarations()

    /** Advertise each saved skill as a callable tool, skipping any name that collides with a built-in. */
    private fun skillDeclarations(): List<ToolDeclaration> {
        val reserved = tools.keys + TASK_COMPLETE
        return skillStore.all().mapNotNull { skill ->
            val toolName = skillToolName(skill.name)
            if (toolName.isBlank() || toolName in reserved) return@mapNotNull null
            ToolDeclaration(
                name = toolName,
                description = "Run your saved skill \"${skill.name}\": ${skill.description}",
                parametersJsonSchema = EMPTY_OBJECT_SCHEMA
            )
        }
    }

    /** Reverse a skill tool-call name back to the saved [Skill], or null if it isn't a skill. */
    fun skillForToolName(toolName: String): Skill? =
        skillStore.all().firstOrNull { skillToolName(it.name) == toolName }

    companion object {
        /**
         * Declaration of the synthetic `task_complete` tool. Calling it terminates the
         * plan-act-observe loop; the executor never dispatches it to a side-effecting tool —
         * it maps directly to [ToolResult.Completed]. See ARCHITECTURE.md §3.4.
         */
        val TASK_COMPLETE_DECLARATION: ToolDeclaration = ToolDeclaration(
            name = TASK_COMPLETE,
            description = "Call this when the user's task is finished (or cannot be completed) " +
                "to end the session. Provide whether it succeeded and a short spoken summary.",
            parametersJsonSchema = AgentToolSchemas.TASK_COMPLETE_SCHEMA
        )

        /** A parameter-less (empty-object) JSON schema — used by skill-as-tool declarations. */
        const val EMPTY_OBJECT_SCHEMA: String = """{"type":"object","properties":{}}"""

        /**
         * A saved skill's name → a Live-valid tool name: `skill_` + the name lowercased with
         * every run of non-alphanumeric characters collapsed to `_`. Gemini function names must
         * match a restricted identifier grammar, but skill names are free-form ("morning routine"),
         * so they are sanitised here and reversed by [skillForToolName].
         */
        fun skillToolName(skillName: String): String =
            "skill_" + skillName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

        /**
         * Construct the concrete tool set. Pure stateless tools are created without a context;
         * intent/hardware tools receive the application context. The skill tools receive the
         * registry's shared [skillStore] so save/list/recall and the skill-as-tool advertising
         * all operate on the same `nazim_skills.json` file.
         */
        private fun buildTools(
            context: Context,
            skillStore: SkillStore,
            memoryStore: MemoryStore
        ): List<AgentTool> {
            return listOf(
                // Read + act on the current screen.
                GetScreenTool(),
                TapTool(),
                InputTextTool(),
                ScrollTool(),
                SwipeTool(),
                LongPressTool(),
                // Global navigation (these are why XENO can actually move around the phone).
                PressBackTool(),
                PressHomeTool(),
                PressRecentsTool(),
                OpenNotificationsTool(),
                // Launch / search / hardware.
                OpenAppTool(context),
                OpenUrlTool(context),
                WebSearchTool(context),
                TorchTool(context),
                // System toggles + hardware
                BrightnessTool(context),
                DndTool(context),
                MediaVolumeTool(context),
                MediaPlayPauseTool(context),
                WifiPanelTool(context),
                BluetoothSettingsTool(context),
                // Intents / deep links (SAFE drafts — nothing sends without a user tap)
                MapsNavigateTool(context),
                OpenSettingsPageTool(context),
                EmailDraftTool(context),
                SmsDraftTool(context),
                DialPrefillTool(context),
                AddCalendarEventTool(context),
                SetAlarmTool(context),
                SetTimerTool(context),
                ShareTool(context),
                // Screen capture (a11y takeScreenshot, API 30+)
                TakeScreenshotTool(),
                // On-device skill memory + self-authored prompt.
                SaveSkillTool(skillStore),
                ListSkillsTool(skillStore),
                RecallSkillTool(skillStore),
                UpdateSelfPromptTool(PersonaStore(context)),
                // On-device long-term memory (episodic/semantic/prospective).
                RememberFactTool(memoryStore),
                RecallMemoryTool(memoryStore),
                NoteIntentionTool(memoryStore),
                ForgetMemoryTool(memoryStore)
            )
        }
    }
}

/** Canonical name of the loop-terminating synthetic tool. */
const val TASK_COMPLETE: String = "task_complete"
