package com.example.permission

import com.example.accessibility.ScreenState

/**
 * Maps an [ActionType] to its static [RiskTier] and computes the *effective* tier as
 * `MAX(static action risk, runtime context risk)` — exactly the rule from
 * ARCHITECTURE.md §4.2.
 *
 * Two invariants enforced here, regardless of the table:
 *  1. Irreversible/outbound action types (SEND_MESSAGE, SEND_EMAIL, PLACE_CALL,
 *     MAKE_PURCHASE, DELETE_DATA, UNINSTALL_APP, INSTALL_APP, CHANGE_SECURITY_SETTING)
 *     can NEVER be classified [RiskTier.SAFE]. Even if a caller passes
 *     `reversible = true` by mistake, they floor at [RiskTier.GUARDED]. This set
 *     mirrors [com.example.agent.GeminiToolMapper] `IRREVERSIBLE_TYPES`, the code that
 *     actually sets [AgentAction.reversible]; the two MUST stay in lock-step.
 *  2. A secure/sensitive runtime context (denylisted app, password/OTP field, secure
 *     window…) escalates any action to at least [RiskTier.GUARDED], and to
 *     [RiskTier.BLOCKED] for the hard-block reasons. The classifier itself runs NO
 *     secure-context detection: it acts only on the [SecureReason] it is given, so
 *     callers MUST supply the detector's verdict (the [secureReason] parameter has no
 *     default — see [classify]). This makes the escalation impossible to skip rather
 *     than caller-dependent: there is no `classify(action)` that silently assumes a
 *     non-secure screen.
 *  3. A TAP/LONG_PRESS is SAFE only by *type*; the click itself can complete an
 *     irreversible/outbound flow (pressing the in-app "Send"/"Call"/"Pay"/"Delete"/
 *     "Confirm" button), which the type cannot see. So for these label-sensitive types
 *     the *target element's label* is consulted: a [dangerousTapLabel] match escalates to
 *     at least [RiskTier.GUARDED] so it can never auto-run in AUTO/ASK_LESS. This closes
 *     the gap whereby a draft (SAFE because "only a human presses Send") is followed by an
 *     auto-tap on the Send button. Because the classifier cannot resolve the label itself
 *     (the TAP params carry only `{index}`), the caller must supply it via the
 *     [classify] `targetLabel` overload; a TAP/LONG_PRESS with an UNRESOLVED label
 *     fails CLOSED to [RiskTier.GUARDED] rather than SAFE.
 *
 * This class is pure and stateless so it can be unit-tested and shared freely.
 */
class RiskClassifier {

    /**
     * Action types that are inherently irreversible or send data across a trust
     * boundary. Per §4.2 "reversibility dominates": these never qualify for AUTO and
     * are floored at GUARDED — regardless of the static table or any `reversible = true`
     * flag a caller passes by mistake.
     *
     * This MUST be the same set as [com.example.agent.GeminiToolMapper] `IRREVERSIBLE_TYPES`
     * (the code that actually marks [AgentAction.reversible]); the type-based floor is
     * dishonest if the two disagree. Keep them in lock-step — adding a type to one means
     * adding it to the other.
     */
    private val irreversibleTypes: Set<ActionType> = setOf(
        ActionType.SEND_MESSAGE,
        ActionType.SEND_EMAIL,
        ActionType.PLACE_CALL,
        ActionType.MAKE_PURCHASE,
        ActionType.DELETE_DATA,
        ActionType.UNINSTALL_APP,
        ActionType.INSTALL_APP,
        ActionType.CHANGE_SECURITY_SETTING
    )

    /**
     * Action types whose effect is a single accessibility *click* on a target UI element
     * (ARCHITECTURE.md §5). For these the [ActionType] alone is harmless, but the click can
     * *complete* an irreversible/outbound flow (pressing the in-app "Send"/"Call"/"Pay"/
     * "Delete"/"Confirm" button), so the danger lives in the target element's label, not the
     * type. These are the only types subjected to [dangerousTapLabel] escalation and to the
     * fail-closed "unresolved label ⇒ GUARDED" rule in [classify].
     */
    private val labelSensitiveTapTypes: Set<ActionType> = setOf(
        ActionType.TAP,
        ActionType.LONG_PRESS
    )

    /**
     * Button/element labels that escalate a TAP/LONG_PRESS to at least [RiskTier.GUARDED]
     * (ARCHITECTURE.md §5: `Tap "Pay"/"Send"/"Transfer"/"Delete"/"Confirm" → ⚠️/⛔`).
     *
     * A draft-only safety story (SMS_DRAFT/DIAL_PREFILL/EMAIL_DRAFT are SAFE because "only a
     * human presses Send") collapses the moment the agent can auto-press the very Send/Call/Pay
     * button itself. Matching one of these words on the tapped element's label forces the action
     * through the Confirm gate (with the literal label shown) instead of auto-running in
     * AUTO/ASK_LESS. Word-bounded + case-insensitive so it fires on "Send", "PAY NOW",
     * "Confirm payment"… without matching unrelated substrings.
     */
    private val dangerousTapLabel = Regex(
        "(?i)\\b(pay|send|transfer|confirm|place call|call now|delete|buy|purchase|checkout|authori[sz]e|wire)\\b"
    )

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
     * non-secure context and let typing into a password/OTP field resolve SAFE. Callers
     * that have a [ScreenState] but no verdict yet should use the
     * `classify(action, screen, detector)` overload, which derives the [SecureReason]
     * first.
     *
     * For a label-sensitive tap (TAP/LONG_PRESS) this 2-arg form has NO target label and
     * therefore CANNOT clear the element as a harmless tap, so it fails CLOSED to at least
     * [RiskTier.GUARDED]. Callers that can resolve the tapped element's label MUST use the
     * [classify] `targetLabel` overload so an ordinary tap stays SAFE while a tap on a
     * "Send"/"Pay"/"Delete" button is escalated.
     *
     * @param action       the action under evaluation.
     * @param secureReason the detector's verdict on the current screen (never assumed).
     */
    fun classify(action: AgentAction, secureReason: SecureReason): RiskTier =
        classify(action, secureReason, targetLabel = null)

    /**
     * Label-aware effective tier. Identical to [classify]`(action, secureReason)` except
     * that, for a label-sensitive tap (TAP/LONG_PRESS), the resolved [targetLabel] — the
     * tapped element's text / content-description — is inspected against [dangerousTapLabel].
     *
     * Escalation rules for TAP/LONG_PRESS (invariant 3 in the class KDoc):
     *  - [targetLabel] matches [dangerousTapLabel] (Send/Pay/Transfer/Delete/Confirm…)
     *    → floored to at least [RiskTier.GUARDED], so it can never auto-run in AUTO/ASK_LESS
     *    and is forced through Confirm with the literal label visible.
     *  - [targetLabel] is `null`/blank (the element could not be resolved) → fail CLOSED,
     *    also floored to [RiskTier.GUARDED]: we refuse to treat a tap we cannot identify as
     *    a harmless SAFE click.
     *  - [targetLabel] is a non-blank, non-dangerous label → no tap escalation (an ordinary
     *    tap on a normal control stays SAFE).
     *
     * For every other [ActionType] [targetLabel] is irrelevant and ignored.
     *
     * @param action       the action under evaluation.
     * @param secureReason the detector's verdict on the current screen (never assumed).
     * @param targetLabel  for TAP/LONG_PRESS, the resolved target element's label, or `null`
     *                     if it could not be resolved (treated as a fail-closed escalation).
     */
    fun classify(action: AgentAction, secureReason: SecureReason, targetLabel: String?): RiskTier {
        val static = staticTier(action.type)
        val context = contextTier(secureReason)
        var effective = maxTier(static, context)

        // Reversibility dominates: an action declared irreversible (or of an inherently
        // irreversible type) can never be SAFE.
        if (!action.reversible || action.type in irreversibleTypes) {
            effective = maxTier(effective, RiskTier.GUARDED)
        }

        // Label sensitivity for clicks: a tap that completes an outbound/irreversible flow
        // has ActionType.TAP/LONG_PRESS (which the floors above can't catch). Inspect the
        // resolved target label. Fail closed when it can't be resolved.
        if (action.type in labelSensitiveTapTypes) {
            val label = targetLabel?.trim()
            val dangerous = label.isNullOrEmpty() || dangerousTapLabel.containsMatchIn(label)
            if (dangerous) {
                effective = maxTier(effective, RiskTier.GUARDED)
            }
        }
        return effective
    }

    /**
     * Overload that runs against a raw [ScreenState] using the provided [detector] to
     * derive the [SecureReason] first, then classifies. Convenience for callers that
     * have a screen but haven't inspected it yet.
     */
    fun classify(
        action: AgentAction,
        screen: ScreenState?,
        detector: SecureContextDetector
    ): RiskTier = classify(action, detector.inspect(screen))

    /** Ordinal-based MAX over the [RiskTier] lattice SAFE < GUARDED < BLOCKED. */
    private fun maxTier(a: RiskTier, b: RiskTier): RiskTier =
        if (a.ordinal >= b.ordinal) a else b
}
