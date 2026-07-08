package com.example.agent

import android.content.Context
import com.example.agent.tools.GetScreenTool
import com.example.agent.tools.InputTextTool
import com.example.agent.tools.LongPressTool
import com.example.agent.tools.OpenAppTool
import com.example.agent.tools.OpenNotificationsTool
import com.example.agent.tools.OpenUrlTool
import com.example.agent.tools.PressBackTool
import com.example.agent.tools.PressHomeTool
import com.example.agent.tools.PressRecentsTool
import com.example.agent.tools.ScrollTool
import com.example.agent.tools.SwipeTool
import com.example.agent.tools.TapTool
import com.example.agent.tools.TorchTool
import com.example.agent.tools.UpdateSelfPromptTool
import com.example.agent.tools.WebSearchTool
import com.example.config.PersonaStore
import com.example.skill.JsonFileSkillStore
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
     * Immutable name → tool map. Built eagerly so [declarations] and [byName] are cheap and
     * thread-safe to read from the WebSocket reader thread and the agent coroutine alike.
     */
    private val tools: Map<String, AgentTool> = buildTools(appContext)
        .associateBy { it.declaration.name }

    /** Look up a tool by its declared name, or `null` if no such tool is registered. */
    fun byName(name: String): AgentTool? = tools[name]

    /**
     * All tool declarations to advertise in the Gemini Live setup request. Includes the
     * synthetic [TASK_COMPLETE_DECLARATION] so the model can signal it is finished.
     */
    fun declarations(): List<ToolDeclaration> =
        tools.values.map { it.declaration } + TASK_COMPLETE_DECLARATION

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

        /**
         * Construct the concrete tool set. Pure stateless tools are created without a context;
         * intent/hardware tools receive the application context.
         *
         * The skill tools share one on-device [SkillStore] (a [JsonFileSkillStore] writing
         * `nazim_skills.json` in `filesDir`) so save/list/recall all operate on the same file.
         * All three are SAFE local-memory tools; recall returns a recipe and executes nothing,
         * so each replayed step still goes through the normal permission gate.
         */
        private fun buildTools(context: Context): List<AgentTool> {
            val skillStore: SkillStore = JsonFileSkillStore(context)
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
                // On-device memory + self-authored prompt.
                SaveSkillTool(skillStore),
                ListSkillsTool(skillStore),
                RecallSkillTool(skillStore),
                UpdateSelfPromptTool(PersonaStore(context))
            )
        }
    }
}

/** Canonical name of the loop-terminating synthetic tool. */
const val TASK_COMPLETE: String = "task_complete"
