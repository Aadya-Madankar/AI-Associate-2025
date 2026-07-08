package com.example.permission

/**
 * Maps an [ActionType] to its static [RiskTier] and computes the *effective* tier as
 * `MAX(static action risk, runtime context risk)` — exactly the rule from
 * ARCHITECTURE.md §4.2.
 *
 * Two invariants enforced here, regardless of the table:
 *  1. Irreversible/outbound action types ([PermissionModel.FORCED_ASK_TYPES]) can NEVER
 *     be classified [RiskTier.SAFE]. Even if a caller passes `reversible = true` by
 *     mistake, they floor at [RiskTier.GUARDED]. This is the single canonical set —
 *     [com.example.agent.GeminiToolMapper] `IRREVERSIBLE_TYPES` (the code that actually
 *     sets [AgentAction.reversible]) references the same set, so the two cannot drift.
 *  2. A secure/sensitive runtime context (denylisted app, password/OTP field, secure
 *     window…) escalates any action to at least [RiskTier.GUARDED], and to
 *     [RiskTier.BLOCKED] for the hard-block reasons. The classifier itself runs NO
 *     secure-context detection: it acts only on the [SecureReason] it is given, so
 *     callers MUST supply the detector's verdict (the [secureReason] parameter has no
 *     default — see [classify]). This makes the escalation impossible to skip rather
 *     than caller-dependent: there is no `classify(action)` that silently assumes a
 *     non-secure screen.
 *
 * A raw TAP/LONG_PRESS is SAFE only by *type*; the click itself can complete an
 * irreversible/outbound flow (pressing the in-app "Send"/"Call"/"Pay"/"Delete"/
 * "Confirm" button), which the type alone cannot see. That label-aware commit-tap
 * escalation — including "an unresolved target label fails closed" — lives solely in
 * [DefaultPermissionEngine] Layer 2b now (it used to be duplicated here, keyed on a
 * different word list than [DenyLists.commitButtonRegex], so a tap could slip past one
 * copy by matching only the other). This classifier stays a pure `(type, context)`
 * function and is not tap-label-aware.
 *
 * This class is pure and stateless so it can be unit-tested and shared freely.
 */
class RiskClassifier {

    /**
     * Action types that are inherently irreversible or send data across a trust
     * boundary. Per §4.2 "reversibility dominates": these never qualify for AUTO and
     * are floored at GUARDED — regardless of the static table or any `reversible = true`
     * flag a caller passes by mistake. The single canonical
     * [PermissionModel.FORCED_ASK_TYPES] definition — see its doc for the other
     * consuming sites.
     */
    private val irreversibleTypes: Set<ActionType> = PermissionModel.FORCED_ASK_TYPES

    /**
     * The static SAFE/GUARDED/BLOCKED table (ARCHITECTURE.md §5). Anything not listed
     * defaults to [RiskTier.GUARDED] via [staticTier] — we never silently treat an
     * unrecognized action as SAFE.
     */
    private val staticTable: Map<ActionType, RiskTier> = mapOf(
        // --- SAFE: reversible, nothing leaves the trust boundary, drafts only ---
        ActionType.GET_SCREEN to RiskTier.SAFE,
        ActionType.TAKE_SCREENSHOT to RiskTier.SAFE,
        ActionType.TASK_COMPLETE to RiskTier.SAFE,
        ActionType.TAP to RiskTier.SAFE,
        ActionType.SWIPE to RiskTier.SAFE,
        ActionType.SCROLL to RiskTier.SAFE,
        ActionType.LONG_PRESS to RiskTier.SAFE,
        ActionType.PRESS_BACK to RiskTier.SAFE,
        ActionType.PRESS_HOME to RiskTier.SAFE,
        ActionType.PRESS_RECENTS to RiskTier.SAFE,
        ActionType.OPEN_NOTIFICATIONS to RiskTier.SAFE,
        // SAFE only on a verified non-secure field. The classifier does NOT detect this
        // itself — it escalates to GUARDED/BLOCKED solely when the caller passes a
        // non-NONE [SecureReason] for a password/OTP/secure field. Callers MUST supply
        // the detector's verdict (the [classify] secureReason has no default), so typing
        // can never silently fall through as SAFE without a context check.
        ActionType.INPUT_TEXT to RiskTier.SAFE,
        ActionType.OPEN_APP to RiskTier.SAFE,
        ActionType.OPEN_URL to RiskTier.SAFE,
        ActionType.WEB_SEARCH to RiskTier.SAFE,
        ActionType.MAPS_NAVIGATE to RiskTier.SAFE,
        ActionType.SHARE to RiskTier.SAFE,
        ActionType.DIAL_PREFILL to RiskTier.SAFE,
        ActionType.SMS_DRAFT to RiskTier.SAFE,
        ActionType.EMAIL_DRAFT to RiskTier.SAFE,
        ActionType.SET_ALARM to RiskTier.SAFE,
        ActionType.SET_TIMER to RiskTier.SAFE,
        ActionType.ADD_CALENDAR_EVENT to RiskTier.SAFE,
        ActionType.OPEN_SETTINGS_PAGE to RiskTier.SAFE,
        ActionType.TORCH to RiskTier.SAFE,
        ActionType.MEDIA_VOLUME to RiskTier.SAFE,
        ActionType.MEDIA_PLAY_PAUSE to RiskTier.SAFE,
        ActionType.WIFI_PANEL to RiskTier.SAFE,           // deep-link handoff only
        ActionType.BLUETOOTH_SETTINGS to RiskTier.SAFE,   // deep-link handoff only

        // On-device skill memory: read/write a local JSON file in filesDir only. Nothing
        // leaves the trust boundary and every operation is reversible (overwrite/delete by
        // name). RECALL only returns the recipe — it executes nothing; the model re-issues
        // each step as its own permission-gated tool call, so SAVE/RECALL/LIST grant no
        // extra authority. See com.example.skill.* and ARCHITECTURE.md §5.
        ActionType.SAVE_SKILL to RiskTier.SAFE,
        ActionType.RECALL_SKILL to RiskTier.SAFE,
        ActionType.LIST_SKILLS to RiskTier.SAFE,

        // XENO editing its own persisted prompt note: writes one local DataStore string,
        // reversible (overwrite/clear), nothing leaves the device → SAFE.
        ActionType.EDIT_SELF_PROMPT to RiskTier.SAFE,

        // --- GUARDED: irreversible / outbound / special-access ---
        ActionType.PLACE_CALL to RiskTier.GUARDED,
        ActionType.SEND_MESSAGE to RiskTier.GUARDED,
        ActionType.SEND_EMAIL to RiskTier.GUARDED,
        ActionType.MAKE_PURCHASE to RiskTier.GUARDED,
        ActionType.INSTALL_APP to RiskTier.GUARDED,
        ActionType.UNINSTALL_APP to RiskTier.GUARDED,
        ActionType.BRIGHTNESS to RiskTier.GUARDED,        // WRITE_SETTINGS one-time grant
        ActionType.DO_NOT_DISTURB to RiskTier.GUARDED,    // ACCESS_NOTIFICATION_POLICY grant

        // --- BLOCKED: never automate ---
        ActionType.CHANGE_SECURITY_SETTING to RiskTier.BLOCKED,
        ActionType.DELETE_DATA to RiskTier.BLOCKED,

        // --- Unknown → GUARDED (defensive default) ---
        ActionType.UNKNOWN to RiskTier.GUARDED
    )

    /**
     * The static tier for [type] alone, before runtime context. Unrecognized types
     * default to [RiskTier.GUARDED]. Irreversible types are floored at GUARDED even if
     * the table somehow disagrees.
     */
    fun staticTier(type: ActionType): RiskTier {
        val base = staticTable[type] ?: RiskTier.GUARDED
        return if (type in irreversibleTypes) maxTier(base, RiskTier.GUARDED) else base
    }

    /**
     * Translates a [SecureReason] into the runtime risk it implies. Hard-block reasons
     * yield [RiskTier.BLOCKED]; softer "sensitive but readable" signals yield
     * [RiskTier.GUARDED]; [SecureReason.NONE] contributes [RiskTier.SAFE].
     */
    fun contextTier(reason: SecureReason): RiskTier = when (reason) {
        SecureReason.NONE -> RiskTier.SAFE
        // Hard blocks — automation must not proceed in normal modes.
        SecureReason.DENYLISTED_APP,
        SecureReason.FLAG_SECURE_WINDOW,
        SecureReason.PASSWORD_FIELD,
        SecureReason.OTP_OR_CARD_FIELD,
        SecureReason.KEYGUARD -> RiskTier.BLOCKED
        // Sensitive-looking but not provably a hard block → never SAFE.
        SecureReason.SENSITIVE_HIDDEN_NODE -> RiskTier.GUARDED
    }

    /**
     * The effective tier for [action] given the runtime [secureReason] derived from the
     * current screen. This is `MAX(static, context)` with the irreversibility floor
     * applied last so an irreversible action is never reported SAFE.
     *
     * [secureReason] is REQUIRED — there is deliberately no default. The classifier does
     * not inspect the screen itself, so a missing verdict would silently read as a
     * non-secure context and let typing into a password/OTP field resolve SAFE.
     *
     * Note this does NOT escalate a TAP/LONG_PRESS by the tapped element's label — that
     * commit-tap guard (including "unresolved label fails closed") lives solely in
     * [DefaultPermissionEngine] Layer 2b, which is the single enforcement point for it in
     * every mode including BYPASS.
     *
     * @param action       the action under evaluation.
     * @param secureReason the detector's verdict on the current screen (never assumed).
     */
    fun classify(action: AgentAction, secureReason: SecureReason): RiskTier {
        val static = staticTier(action.type)
        val context = contextTier(secureReason)
        var effective = maxTier(static, context)

        // Reversibility dominates: an action declared irreversible (or of an inherently
        // irreversible type) can never be SAFE.
        if (!action.reversible || action.type in irreversibleTypes) {
            effective = maxTier(effective, RiskTier.GUARDED)
        }
        return effective
    }

    /**
     * Ordinal-based MAX over the [RiskTier] lattice SAFE < GUARDED < BLOCKED. Not
     * private: [DefaultPermissionEngine] reuses this single definition instead of
     * keeping its own duplicate copy.
     */
    fun maxTier(a: RiskTier, b: RiskTier): RiskTier =
        if (a.ordinal >= b.ordinal) a else b
}
