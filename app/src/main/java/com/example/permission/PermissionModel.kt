package com.example.permission

/**
 * The permission & autonomy contracts for the Xeno Live phone-control agent.
 *
 * This file is the single source of truth for the types every other layer
 * (agent loop, accessibility executor, UI, audit) depends on. It is deliberately
 * logic-free: only enums and data classes live here so the whole codebase can be
 * built against a stable surface.
 *
 * Design (see ARCHITECTURE.md §4): autonomy [AutonomyMode] sets a *baseline*, but
 * the deny rules, forced-ask rules, and the secure-context block apply in EVERY
 * mode — including [AutonomyMode.BYPASS]. The classifier evaluates first-match in
 * the order: denylist → forced-ask → secure-context → per-action/app allow + mode.
 */

/**
 * How autonomous the agent is allowed to be, mirroring Claude Code's permission
 * modes. Persisted in DataStore and surfaced as a mode pill in the UI.
 */
enum class AutonomyMode {
    /** Read freely, but confirm EVERY action with a preview sheet. The safe default. */
    ASK,

    /** Auto-run a fixed SAFE whitelist; confirm everything else. (Claude `acceptEdits`.) */
    ASK_LESS,

    /** Run SAFE actions without prompts; GUARDED actions go to the classifier. */
    AUTO,

    /** Run everything except hard-blocked secure contexts + the forced-ask list. Opt-in only. */
    BYPASS
}

/**
 * Static risk tier of an action type, before runtime context is considered.
 * The effective tier is `MAX(static tier, runtime context tier)`.
 */
enum class RiskTier {
    /** May auto-run in ASK_LESS/AUTO. Reversible, no data leaves the trust boundary. */
    SAFE,

    /** Always needs explicit confirmation (or a standing per-app/session grant). */
    GUARDED,

    /** Hard-refused in ASK/ASK_LESS/AUTO; only reachable in BYPASS after extra confirm. */
    BLOCKED
}

/**
 * The catalog of things the agent can attempt. Each maps to a concrete mechanism
 * (intent / system toggle / accessibility UI action) in the executor, and to a
 * default [RiskTier] in the classifier. See ARCHITECTURE.md §5 for the full table.
 */
enum class ActionType {
    // --- Accessibility UI primitives -------------------------------------
    TAP, INPUT_TEXT, SWIPE, SCROLL, LONG_PRESS,
    PRESS_BACK, PRESS_HOME, PRESS_RECENTS, OPEN_NOTIFICATIONS,
    GET_SCREEN, TAKE_SCREENSHOT, TASK_COMPLETE,

    // --- Intents / deep links (SAFE drafts) ------------------------------
    OPEN_APP, OPEN_URL, WEB_SEARCH, MAPS_NAVIGATE, SHARE,
    DIAL_PREFILL, SMS_DRAFT, EMAIL_DRAFT,
    SET_ALARM, SET_TIMER, ADD_CALENDAR_EVENT, OPEN_SETTINGS_PAGE,

    // --- System toggles --------------------------------------------------
    TORCH, MEDIA_VOLUME, MEDIA_PLAY_PAUSE, BRIGHTNESS, DO_NOT_DISTURB,
    WIFI_PANEL, BLUETOOTH_SETTINGS,

    // --- GUARDED (irreversible / outbound) -------------------------------
    PLACE_CALL, SEND_MESSAGE, SEND_EMAIL, MAKE_PURCHASE,
    INSTALL_APP, UNINSTALL_APP, CHANGE_SECURITY_SETTING, DELETE_DATA,

    // --- On-device skill memory (SAFE: local JSON only, reversible) ------
    SAVE_SKILL, RECALL_SKILL, LIST_SKILLS,

    // --- XENO's self-authored prompt note (SAFE: local DataStore only, reversible) ---
    EDIT_SELF_PROMPT,

    /** Anything the model asked for that we don't recognize → treated as GUARDED. */
    UNKNOWN
}

/**
 * Cross-cutting permission constants that must have exactly one definition. Kept
 * addressable (`PermissionModel.FORCED_ASK_TYPES`) so it can be shared by
 * [DefaultPermissionEngine], [RiskClassifier], [DataStoreRuleStore], and
 * [com.example.agent.GeminiToolMapper] — the four places that used to hand-maintain
 * their own copy of "which action types are irreversible/forced-ask" and had already
 * drifted out of lock-step (the engine's copy was missing CHANGE_SECURITY_SETTING).
 */
object PermissionModel {
    /**
     * Action types that ALWAYS require explicit confirmation and can never receive a
     * standing "always allow" grant, regardless of autonomy mode — the forced-ask /
     * irreversible list from ARCHITECTURE.md §4.2. Consuming sites reference this set
     * directly; [com.example.agent.GeminiToolMapper] may union in additional
     * locally-relevant types but must not redefine/fork it.
     */
    val FORCED_ASK_TYPES: Set<ActionType> = setOf(
        ActionType.SEND_MESSAGE,
        ActionType.SEND_EMAIL,
        ActionType.PLACE_CALL,
        ActionType.MAKE_PURCHASE,
        ActionType.DELETE_DATA,
        ActionType.UNINSTALL_APP,
        ActionType.INSTALL_APP,
        ActionType.CHANGE_SECURITY_SETTING
    )
}

/**
 * A concrete action the agent wants to perform, resolved from a Gemini tool call.
 *
 * @param type           which [ActionType] this is.
 * @param targetApp      the package the action is aimed at (for allow/deny scoping), if known.
 * @param params         literal arguments (the actual message text, number, amount, brightness…).
 *                       These are shown verbatim on the confirm sheet for irreversible actions.
 * @param reversible     whether the effect can be undone. Irreversible actions NEVER auto-run.
 * @param description    a short human sentence for the confirm sheet / audit log.
 */
data class AgentAction(
    val type: ActionType,
    val targetApp: String? = null,
    val params: Map<String, Any?> = emptyMap(),
    val reversible: Boolean = true,
    val description: String = ""
)

/**
 * Why a context is considered sensitive. Surfaced to the user so a block is explainable.
 */
enum class SecureReason {
    NONE,
    DENYLISTED_APP,        // banking / wallet / authenticator package
    FLAG_SECURE_WINDOW,    // window has WindowManager.LayoutParams.FLAG_SECURE
    PASSWORD_FIELD,        // node.isPassword() / password inputType
    OTP_OR_CARD_FIELD,     // hint/desc/resource-id matched OTP|CVV|PIN|card number
    KEYGUARD,              // lockscreen showing
    SENSITIVE_HIDDEN_NODE  // Android 14+ isAccessibilityDataSensitive
}

/**
 * The outcome of running [AgentAction] through the [PermissionEngine].
 */
sealed interface PermissionDecision {
    /** Run it now, no prompt. */
    data class Allow(val reason: String = "") : PermissionDecision

    /** Pause and ask the user; UI shows a [ConfirmRequest] built from the action. */
    data class Confirm(val request: ConfirmRequest) : PermissionDecision

    /** Refuse. [secureReason] explains why; this becomes a tool-error fed back to the model. */
    data class Block(val secureReason: SecureReason, val message: String) : PermissionDecision
}

/**
 * What the confirm sheet renders. Literal [params] are mandatory for irreversible actions.
 */
data class ConfirmRequest(
    val action: AgentAction,
    val tier: RiskTier,
    val title: String,
    val literalDetails: List<Pair<String, String>>,
    val offeredScopes: List<RuleScope> = listOf(RuleScope.ONCE, RuleScope.SESSION)
)

/**
 * How long a granted/denied rule lasts. New "always" grants default to [SESSION].
 */
enum class RuleScope {
    /** This one action, right now. */
    ONCE,

    /** This action type + this app, for the remainder of the current hands-free session. */
    SESSION,

    /** This action type + this app, persisted until revoked. */
    ALWAYS_THIS_ACTION_AND_APP
}
