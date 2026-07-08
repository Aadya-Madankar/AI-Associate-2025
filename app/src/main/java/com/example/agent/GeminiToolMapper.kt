package com.example.agent

import com.example.live.LiveFunctionCall
import com.example.live.LiveFunctionDeclaration
import com.example.live.LiveTool
import com.example.permission.ActionType
import com.example.permission.AgentAction
import com.example.permission.PermissionModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Bridges the agent's tool model and the Gemini Live wire DTOs (`com.example.live.*`),
 * keeping the agent layer free of any Moshi/wire coupling beyond this one file.
 *
 * Two directions:
 *  1. **Declare** — [toLiveTool] / [toLiveFunctionDeclaration] convert our
 *     [ToolDeclaration]s (whose schema is a raw JSON string) into the wire
 *     [LiveTool] / [LiveFunctionDeclaration] shape (whose `parameters` is a parsed
 *     `Map<String, Any?>` per the protocol DTO contract).
 *  2. **Classify** — [toAgentAction] turns an inbound [LiveFunctionCall] (`name` + `args`)
 *     into an [AgentAction] the [com.example.permission.PermissionEngine] can gate, mapping
 *     the tool name to an [ActionType], normalizing args into the action's literal `params`,
 *     deriving the `targetApp`, and marking irreversibility. Irreversible actions
 *     (send / call / purchase / delete) MUST be flagged so they never auto-run
 *     (ARCHITECTURE.md §4.2).
 *
 * The class is stateless and side-effect-free (it only parses/maps), so it is fully
 * unit-testable without Android.
 */
class GeminiToolMapper(moshi: Moshi = defaultMoshi()) {

    /** Adapter for the open `Map<String, Any?>` JSON-object shape used by the wire DTOs. */
    private val mapAdapter = moshi.adapter<Map<String, Any?>?>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    // ------------------------------------------------------------------------
    // Direction 1: ToolDeclaration -> LiveTool / LiveFunctionDeclaration
    // ------------------------------------------------------------------------

    /** Group all [declarations] into the single [LiveTool] advertised in the Live setup. */
    fun toLiveTool(declarations: List<ToolDeclaration>): LiveTool =
        LiveTool(functionDeclarations = declarations.map(::toLiveFunctionDeclaration))

    /**
     * Convert one [ToolDeclaration] to a [LiveFunctionDeclaration]. The declaration's
     * `parametersJsonSchema` string is parsed into the `Map<String, Any?>` the wire DTO
     * expects; an empty/blank/unparseable schema becomes `null` (a parameter-less tool).
     */
    fun toLiveFunctionDeclaration(declaration: ToolDeclaration): LiveFunctionDeclaration =
        LiveFunctionDeclaration(
            name = declaration.name,
            description = declaration.description,
            parameters = parseSchema(declaration.parametersJsonSchema)
        )

    private fun parseSchema(schemaJson: String): Map<String, Any?>? {
        if (schemaJson.isBlank()) return null
        val parsed = runCatching { mapAdapter.fromJson(schemaJson) }.getOrNull() ?: return null
        // A `{ "type":"object","properties":{} }` schema is still a valid (empty-arg) schema.
        return parsed
    }

    // ------------------------------------------------------------------------
    // Direction 2: LiveFunctionCall -> AgentAction (for the permission gate)
    // ------------------------------------------------------------------------

    /**
     * Classify an inbound model tool call into an [AgentAction]. Pure mapping — no execution
     * and no I/O. The returned action carries the literal [AgentAction.params] (so the confirm
     * sheet can show the real text / number / amount), the resolved [AgentAction.targetApp],
     * and the [AgentAction.reversible] flag that decides whether it may ever auto-run.
     */
    fun toAgentAction(call: LiveFunctionCall): AgentAction {
        val args = call.args ?: emptyMap()
        val type = actionTypeFor(call.name)
        // UNKNOWN is conservative-by-default: a tool we don't recognize (per PermissionModel,
        // "anything the model asked for that we don't recognize → treated as GUARDED") has
        // unknown reversibility, so it MUST be assumed irreversible. This routes it through
        // the forced-ask Confirm in every mode (including BYPASS) and prevents an outbound
        // tool whose name is simply missing from NAME_TO_TYPE from silently auto-running.
        val reversible = type != ActionType.UNKNOWN && type !in IRREVERSIBLE_TYPES
        return AgentAction(
            type = type,
            targetApp = resolveTargetApp(type, args),
            params = args,
            reversible = reversible,
            description = describe(type, call.name, args)
        )
    }

    /** Map a declared tool name to its [ActionType]; unknown names → [ActionType.UNKNOWN]. */
    fun actionTypeFor(toolName: String): ActionType = NAME_TO_TYPE[toolName] ?: ActionType.UNKNOWN

    /**
     * The package the action targets, used for allow/deny scoping; null when not app-specific.
     *
     * SECURITY: only action types whose target genuinely IS a named app/intent may read a
     * package/app from the (untrusted, model-controlled) args. The accessibility UI primitives
     * (TAP / INPUT_TEXT / SWIPE / SCROLL / LONG_PRESS / PRESS_* / OPEN_NOTIFICATIONS / GET_SCREEN /
     * screenshot) have NO real per-app target — their effect lands on whatever index of whatever
     * screen is foreground, and the only authoritative app identity is `screen.packageName`, which
     * the permission engine tracks separately. If a primitive were allowed to read an arbitrary
     * `targetApp`/`package` arg, the model could (a) choose the standing-grant scope key and
     * (b) replay a per-app "always allow" grant from any unrelated foreground screen, silently
     * auto-tapping / auto-typing on purchase confirms, compose+send, destructive Settings dialogs,
     * etc. Returning null here forces a primitive's scoping app to come only from the engine's
     * authoritative `screen.packageName`; the rule store already refuses the null-app wildcard, so
     * primitives can never acquire a replayable standing grant. Fail closed.
     */
    private fun resolveTargetApp(type: ActionType, args: Map<String, Any?>): String? = when (type) {
        ActionType.OPEN_APP ->
            (args["package"] as? String)?.takeUnless { it.isBlank() }
                ?: (args["appName"] as? String)?.takeUnless { it.isBlank() }
        else -> when (type) {
            // Intent/app-targeted types: the target really is a named app/intent, so reading it
            // from args is the legitimate scoping identity.
            ActionType.OPEN_URL, ActionType.OPEN_SETTINGS_PAGE ->
                (args["package"] as? String)?.takeUnless { it.isBlank() }
                    ?: (args["targetApp"] as? String)?.takeUnless { it.isBlank() }
            // Accessibility UI primitives (and everything else): no model-claimed target app.
            // The authoritative app identity is the foreground screen, tracked by the engine.
            else -> null
        }
    }

    private fun describe(type: ActionType, toolName: String, args: Map<String, Any?>): String = when (type) {
        ActionType.OPEN_APP -> "Open ${args["package"] ?: args["appName"] ?: "an app"}"
        ActionType.OPEN_URL -> "Open ${args["url"] ?: "a URL"}"
        ActionType.WEB_SEARCH -> "Search the web for \"${args["query"] ?: ""}\""
        ActionType.TORCH -> "Turn the flashlight ${if (args["on"] == true) "on" else "off"}"
        ActionType.TAP -> "Tap element ${args["index"] ?: "?"}"
        ActionType.INPUT_TEXT -> "Type into element ${args["index"] ?: "?"}"
        ActionType.TASK_COMPLETE -> "Finish the task"
        else -> toolName.replace('_', ' ')
    }

    companion object {
        /**
         * Builds a Moshi capable of (de)serializing the open `Map<String, Any?>` JSON-object
         * shape used by the wire DTOs and tool schemas. Moshi's built-in adapters cover
         * String/Boolean/Double/List/Map values — exactly the JSON surface here.
         */
        fun defaultMoshi(): Moshi = Moshi.Builder().build()

        /**
         * Canonical tool-name → [ActionType] mapping. The keys mirror
         * [AgentToolSchemas] tool names; the values feed the risk classifier.
         */
        private val NAME_TO_TYPE: Map<String, ActionType> = mapOf(
            // Accessibility UI primitives
            AgentToolSchemas.GET_SCREEN to ActionType.GET_SCREEN,
            AgentToolSchemas.TAP to ActionType.TAP,
            AgentToolSchemas.INPUT_TEXT to ActionType.INPUT_TEXT,
            AgentToolSchemas.SWIPE to ActionType.SWIPE,
            AgentToolSchemas.SCROLL to ActionType.SCROLL,
            AgentToolSchemas.LONG_PRESS to ActionType.LONG_PRESS,
            AgentToolSchemas.PRESS_BACK to ActionType.PRESS_BACK,
            AgentToolSchemas.PRESS_HOME to ActionType.PRESS_HOME,
            AgentToolSchemas.PRESS_RECENTS to ActionType.PRESS_RECENTS,
            AgentToolSchemas.OPEN_NOTIFICATIONS to ActionType.OPEN_NOTIFICATIONS,
            AgentToolSchemas.TASK_COMPLETE to ActionType.TASK_COMPLETE,
            // Intents / deep links (SAFE drafts)
            AgentToolSchemas.OPEN_APP to ActionType.OPEN_APP,
            AgentToolSchemas.OPEN_URL to ActionType.OPEN_URL,
            AgentToolSchemas.WEB_SEARCH to ActionType.WEB_SEARCH,
            // System toggles
            AgentToolSchemas.TORCH to ActionType.TORCH,
            AgentToolSchemas.BRIGHTNESS to ActionType.BRIGHTNESS,
            AgentToolSchemas.SET_DND to ActionType.DO_NOT_DISTURB,
            // On-device skill memory (SAFE, reversible — deliberately NOT in IRREVERSIBLE_TYPES).
            AgentToolSchemas.SAVE_SKILL to ActionType.SAVE_SKILL,
            AgentToolSchemas.RECALL_SKILL to ActionType.RECALL_SKILL,
            AgentToolSchemas.LIST_SKILLS to ActionType.LIST_SKILLS,
            // XENO's self-authored prompt note (SAFE, local DataStore only).
            AgentToolSchemas.EDIT_SELF_PROMPT to ActionType.EDIT_SELF_PROMPT
        )

        /**
         * Action types whose effects cannot be undone. These are marked
         * [AgentAction.reversible] = false and therefore NEVER qualify for AUTO
         * (ARCHITECTURE.md §4.2). The single canonical [PermissionModel.FORCED_ASK_TYPES]
         * set — kept as a local `val` (rather than referenced inline everywhere) so a
         * future tool-specific addition can union onto it here without forking the
         * canonical definition.
         *
         * This set is NOT the only backstop: [ActionType.UNKNOWN] is treated as
         * irreversible by [toAgentAction] independently of this set. That is the
         * intended fail-safe — a registered outbound tool whose name is forgotten in
         * [NAME_TO_TYPE] degrades to UNKNOWN and is forced through the Confirm gate
         * rather than silently becoming a reversible auto-run. Adding the tool to
         * [NAME_TO_TYPE] (mapping it to one of the types below) is what upgrades it
         * from "unknown, ask always" to "known irreversible, ask per policy".
         */
        private val IRREVERSIBLE_TYPES: Set<ActionType> = PermissionModel.FORCED_ASK_TYPES
    }
}
