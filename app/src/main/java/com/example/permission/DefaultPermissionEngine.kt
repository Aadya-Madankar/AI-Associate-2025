package com.example.permission

import com.example.accessibility.ScreenState

/**
 * Default [PermissionEngine] implementing the Claude-Code-style four-layer, first-match
 * decision from ARCHITECTURE.md §4.2. The autonomy [AutonomyMode] only sets a baseline;
 * the denylist, the forced-ask list, and the secure-context block apply in **every**
 * mode, including [AutonomyMode.BYPASS].
 *
 * Evaluation order (first match wins):
 *  1. **Denylist** (apps + action types) → [PermissionDecision.Block].
 *  2. **Forced-ask** (irreversible/outbound: send-money, place-call, delete-all…) →
 *     [PermissionDecision.Confirm], even in BYPASS.
 *  3. **Secure-context** detector (denylisted app / password / OTP / FLAG_SECURE /
 *     keyguard) → [PermissionDecision.Block] in normal modes; in BYPASS it still
 *     requires an extra explicit confirm rather than silently running.
 *  4. **Per-rule + mode**: standing allow rule → Allow; otherwise the effective
 *     [RiskTier] vs. the current mode decides Allow / Confirm / Block.
 *
 * The engine is pure given its injected collaborators; it holds no mutable state of its
 * own, so a single instance can be shared across the app.
 *
 * @param detector       secure-context detector (defaults to [DefaultSecureContextDetector]).
 * @param classifier     risk classifier (defaults to a fresh [RiskClassifier]).
 * @param ruleStore      standing allow/deny rules (nullable; if absent, only modes decide).
 */
class DefaultPermissionEngine(
    private val detector: SecureContextDetector = DefaultSecureContextDetector(),
    private val classifier: RiskClassifier = RiskClassifier(),
    private val ruleStore: RuleStore? = null
) : PermissionEngine {

    /**
     * Action types that ALWAYS require explicit confirmation, regardless of mode or any
     * standing "always allow" rule. These are the irreversible/outbound primitives from
     * §4.2's forced-ask list; reversibility dominates, so they never auto-run.
     */
    private val forcedAskTypes: Set<ActionType> = setOf(
        ActionType.SEND_MESSAGE,
        ActionType.SEND_EMAIL,
        ActionType.PLACE_CALL,
        ActionType.MAKE_PURCHASE,
        ActionType.DELETE_DATA,
        ActionType.UNINSTALL_APP,
        ActionType.INSTALL_APP
    )

    override fun decide(
        action: AgentAction,
        mode: AutonomyMode,
        screen: ScreenState?
    ): PermissionDecision {
        val secureReason = detector.inspect(screen)

        // ---- Layer 1: global denylist (apps + action types) → hard Block ----
        if (DenyLists.isDenylistedActionType(action.type)) {
            return PermissionDecision.Block(
                secureReason = SecureReason.NONE,
                message = "Action '${action.type.name}' is on the global denylist and is never automated."
            )
        }
        if (DenyLists.isDenylistedPackage(action.targetApp) ||
            DenyLists.isDenylistedPackage(screen?.packageName)
        ) {
            return PermissionDecision.Block(
                secureReason = SecureReason.DENYLISTED_APP,
                message = "The current app is a banking/wallet/authenticator app. Automation is blocked here."
            )
        }
        if (ruleStore?.isDenied(action) == true) {
            return PermissionDecision.Block(
                secureReason = SecureReason.NONE,
                message = "A standing deny rule blocks this action."
            )
        }

        // For a TAP/LONG_PRESS, resolve the tapped element's label from the screen so the
        // classifier can escalate a click on a "Send"/"Pay"/"Delete"/"Confirm" control (a UI
        // commit of an irreversible/outbound flow the ActionType alone can't see). The TAP
        // params carry only {index}, so the label is resolved here, where the screen is
        // available; an UNRESOLVED label makes the classifier fail closed to GUARDED so the
        // effective tier reflects the same commit guard enforced explicitly in Layer 2b below.
        val tapTargetLabel = resolveTapTargetLabel(action, screen)
        val effectiveTier = classifier.classify(action, secureReason, tapTargetLabel)

        // ---- Layer 2: forced-ask list → always Confirm (even in BYPASS) ----
        if (action.type in forcedAskTypes || !action.reversible) {
            // If the secure context is a HARD block reason, prefer the block message but
            // still require confirmation in BYPASS only. In every other mode, a hard
            // secure context blocks outright (handled in Layer 3 below for non-forced
            // actions; for forced actions we surface the secure reason on the sheet).
            if (isHardSecureBlock(secureReason, screen) && mode != AutonomyMode.BYPASS) {
                return blockForSecureReason(secureReason)
            }
            return PermissionDecision.Confirm(
                buildConfirmRequest(action, effectiveTier, secureReason)
            )
        }

        // ---- Layer 2b: content-aware commit guard for raw UI primitives ----
        // The agent drives apps by tapping their own buttons, so a generic TAP/LONG_PRESS
        // on a "Send"/"Pay"/"Transfer"/"Confirm"/"Delete" control is an irreversible UI
        // commit that statically classifies SAFE and would otherwise auto-run in
        // AUTO/ASK_LESS (ARCHITECTURE.md §5/§8: such taps must always confirm, never
        // AUTO). Resolve the target element from the passed screen by its index param and,
        // if its label looks like a commit control, force an explicit ONCE confirm — it
        // can never auto-run and can never receive a standing/ALWAYS grant. Fail closed.
        if (action.type == ActionType.TAP || action.type == ActionType.LONG_PRESS) {
            if (isCommitTap(action, screen)) {
                if (isHardSecureBlock(secureReason, screen) && mode != AutonomyMode.BYPASS) {
                    return blockForSecureReason(secureReason)
                }
                // GUARDED forces a confirm that offeredScopesFor clamps to [ONCE, SESSION];
                // routing a commit tap through this branch makes it forced-ask in EVERY
                // mode (including AUTO/ASK_LESS/BYPASS) so the UI-level commit never runs
                // silently and never becomes an ALWAYS rule.
                return PermissionDecision.Confirm(
                    buildConfirmRequest(action, maxTier(effectiveTier, RiskTier.GUARDED), secureReason)
                )
            }
        }

        // ---- Layer 3: secure-context block ----
        if (isHardSecureBlock(secureReason, screen)) {
            return if (mode == AutonomyMode.BYPASS) {
                // BYPASS does not silently run secure contexts — it still confirms.
                PermissionDecision.Confirm(
                    buildConfirmRequest(action, RiskTier.BLOCKED, secureReason)
                )
            } else {
                blockForSecureReason(secureReason)
            }
        }

        // ---- Layer 4: per-rule allow + mode baseline ----
        if (ruleStore?.isAllowed(action, mode) == true) {
            return PermissionDecision.Allow(reason = "Standing allow rule.")
        }

        return decideByModeAndTier(action, mode, effectiveTier, secureReason)
    }

    /**
     * Applies the autonomy-mode baseline against the already-computed effective
     * [RiskTier]. This is reached only after the denylist, forced-ask and secure-context
     * layers have passed.
     */
    private fun decideByModeAndTier(
        action: AgentAction,
        mode: AutonomyMode,
        tier: RiskTier,
        secureReason: SecureReason
    ): PermissionDecision = when (mode) {
        // ASK confirms everything.
        AutonomyMode.ASK ->
            PermissionDecision.Confirm(buildConfirmRequest(action, tier, secureReason))

        // ASK_LESS auto-runs only the SAFE whitelist; confirm the rest.
        AutonomyMode.ASK_LESS -> when (tier) {
            RiskTier.SAFE -> PermissionDecision.Allow(reason = "SAFE action under ASK_LESS.")
            else -> PermissionDecision.Confirm(buildConfirmRequest(action, tier, secureReason))
        }

        // AUTO runs SAFE silently; GUARDED confirms; BLOCKED blocks.
        AutonomyMode.AUTO -> when (tier) {
            RiskTier.SAFE -> PermissionDecision.Allow(reason = "SAFE action under AUTO.")
            RiskTier.GUARDED -> PermissionDecision.Confirm(buildConfirmRequest(action, tier, secureReason))
            RiskTier.BLOCKED -> blockForSecureReason(secureReason)
        }

        // BYPASS runs everything that reached here (secure-context + forced-ask already
        // handled above). It does not bypass a BLOCKED tier that isn't a secure context.
        AutonomyMode.BYPASS -> when (tier) {
            RiskTier.BLOCKED -> PermissionDecision.Confirm(buildConfirmRequest(action, tier, secureReason))
            else -> PermissionDecision.Allow(reason = "BYPASS mode.")
        }
    }

    /**
     * Builds the [ConfirmRequest] the UI renders. Literal [AgentAction.params] are
     * surfaced verbatim (the actual message text, number, amount, brightness…) so the
     * user can verify irreversible actions before they run, per §4.4. The offered
     * [RuleScope]s narrow for irreversible / secure actions: those only ever offer
     * ONCE so a single confirm can never become a standing grant.
     */
    private fun buildConfirmRequest(
        action: AgentAction,
        tier: RiskTier,
        secureReason: SecureReason
    ): ConfirmRequest {
        val literal = literalDetails(action, secureReason)
        val offered = offeredScopesFor(action, tier)
        return ConfirmRequest(
            action = action,
            tier = tier,
            title = confirmTitle(action),
            literalDetails = literal,
            offeredScopes = offered
        )
    }

    /** Human-readable confirm-sheet title, e.g. "Send message in WhatsApp?". */
    private fun confirmTitle(action: AgentAction): String {
        val verb = verbFor(action.type)
        val where = action.targetApp?.let { " in ${friendlyApp(it)}" } ?: ""
        return "$verb$where?"
    }

    /**
     * Flattens [AgentAction.params] into ordered label/value pairs for the sheet. The
     * action's own [AgentAction.description] is added first when present. Values are
     * shown literally so confirming an irreversible action keeps the literal value
     * visible — EXCEPT for sensitive content, which is redacted before it is ever
     * displayed or logged (per AgentAction/ScreenState's no-leak guarantee). Param
     * values are masked when the secure context is non-trivial or when the key/value
     * itself looks like an OTP / card / PIN / password, via
     * [DenyLists.sensitiveFieldRegex] / [DenyLists.sensitiveValueRegex]. The literal
     * `text` of an INPUT_TEXT into a password/OTP field is never surfaced at all.
     */
    private fun literalDetails(
        action: AgentAction,
        secureReason: SecureReason
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (action.description.isNotBlank()) {
            out += "Action" to action.description
        }
        action.targetApp?.let { out += "App" to friendlyApp(it) }
        for ((k, v) in action.params) {
            out += prettyKey(k) to redactValue(action, secureReason, k, v)
        }
        if (secureReason != SecureReason.NONE) {
            out += "Warning" to secureReasonMessage(secureReason)
        }
        return out
    }

    /** Masked placeholder shown in place of a sensitive value. */
    private val redactedPlaceholder = "••••••"

    /**
     * Returns the display string for a single param value, redacting anything sensitive
     * before it leaves the device. A value is masked when:
     *  - the surrounding context is a secure field (password / OTP / card), OR
     *  - the param key matches [DenyLists.sensitiveFieldRegex] (e.g. "otp", "cvv",
     *    "password"), OR
     *  - the value itself matches [DenyLists.sensitiveValueRegex] (e.g. a 6-digit code,
     *    a PAN, an IBAN), in which case only the matched spans are bulleted.
     *
     * The literal `text` of an INPUT_TEXT into a password/OTP field is never surfaced.
     */
    private fun redactValue(
        action: AgentAction,
        secureReason: SecureReason,
        key: String,
        value: Any?
    ): String {
        if (value == null) return "—"
        val raw = value.toString()

        // Never surface the literal typed text when entering a password/OTP field.
        if (action.type == ActionType.INPUT_TEXT &&
            key.equals("text", ignoreCase = true) &&
            (secureReason == SecureReason.PASSWORD_FIELD ||
                secureReason == SecureReason.OTP_OR_CARD_FIELD)
        ) {
            return redactedPlaceholder
        }

        // A sensitive secure context, or a sensitive-looking key, masks the value whole.
        if (secureReason != SecureReason.NONE ||
            DenyLists.sensitiveFieldRegex.containsMatchIn(key)
        ) {
            return redactedPlaceholder
        }

        // Otherwise mask only the spans of the value that look like a secret.
        return DenyLists.sensitiveValueRegex.replace(raw) { match ->
            "•".repeat(match.value.count { !it.isWhitespace() })
        }
    }

    /**
     * Scopes offered on the sheet. Irreversible / forced-ask / non-SAFE-secure actions
     * may only be granted [RuleScope.ONCE] — never a standing "always" rule. Reversible
     * SAFE/GUARDED actions may additionally be granted for the session or persisted.
     */
    private fun offeredScopesFor(action: AgentAction, tier: RiskTier): List<RuleScope> {
        val irreversible = !action.reversible || action.type in forcedAskTypes
        return when {
            irreversible || tier == RiskTier.BLOCKED -> listOf(RuleScope.ONCE)
            tier == RiskTier.GUARDED -> listOf(RuleScope.ONCE, RuleScope.SESSION)
            else -> listOf(
                RuleScope.ONCE,
                RuleScope.SESSION,
                RuleScope.ALWAYS_THIS_ACTION_AND_APP
            )
        }
    }

    /**
     * True if a raw TAP/LONG_PRESS is committing an irreversible/outbound UI action,
     * i.e. it targets an element whose visible label (text / content-description) matches
     * [DenyLists.commitButtonRegex] ("Send"/"Pay"/"Transfer"/"Confirm"/"Delete"…). The
     * target is resolved from [screen] by the action's `index` param. Fails closed: if
     * the index is missing/unparseable or the element can't be found, this returns false
     * (the call is still subject to every other layer), but when an index DOES resolve to
     * a commit-labelled element the tap is forced to confirm regardless of mode.
     */
    private fun isCommitTap(action: AgentAction, screen: ScreenState?): Boolean {
        val elements = screen?.elements ?: return false
        val idx = (action.params["index"] as? Number)?.toInt()
            ?: (action.params["index"] as? String)?.trim()?.toIntOrNull()
            ?: return false
        val target = elements.firstOrNull { it.index == idx } ?: return false
        return DenyLists.matchesCommitButton(target.text, target.contentDescription)
    }

    /**
     * Resolves the visible label (text, else content-description) of the element a
     * TAP/LONG_PRESS targets, so [RiskClassifier.classify] can inspect it for a commit-button
     * keyword. Returns `null` for non-tap actions (the classifier ignores the label for them)
     * and — deliberately — also `null` when the tap's `index` is missing/unparseable or the
     * element cannot be found in [screen]. For a TAP/LONG_PRESS a `null` label is a fail-closed
     * signal: the classifier floors such an unresolved tap to GUARDED rather than SAFE, so a tap
     * we cannot identify can never auto-run in AUTO/ASK_LESS.
     */
    private fun resolveTapTargetLabel(action: AgentAction, screen: ScreenState?): String? {
        if (action.type != ActionType.TAP && action.type != ActionType.LONG_PRESS) return null
        val elements = screen?.elements ?: return null
        val idx = (action.params["index"] as? Number)?.toInt()
            ?: (action.params["index"] as? String)?.trim()?.toIntOrNull()
            ?: return null
        val target = elements.firstOrNull { it.index == idx } ?: return null
        return target.text?.takeUnless { it.isBlank() }
            ?: target.contentDescription?.takeUnless { it.isBlank() }
    }

    /** Ordinal-based MAX over the [RiskTier] lattice SAFE < GUARDED < BLOCKED. */
    private fun maxTier(a: RiskTier, b: RiskTier): RiskTier =
        if (a.ordinal >= b.ordinal) a else b

    private fun isHardSecureBlock(reason: SecureReason, screen: ScreenState?): Boolean = when (reason) {
        SecureReason.DENYLISTED_APP,
        SecureReason.FLAG_SECURE_WINDOW,
        SecureReason.PASSWORD_FIELD,
        SecureReason.OTP_OR_CARD_FIELD,
        SecureReason.KEYGUARD -> true
        SecureReason.NONE -> false
        // Android 14+ hidden sensitive node: normally a soft escalation (GUARDED → Confirm),
        // but when the screen is ALSO empty the framework hid everything sensitive and there
        // is nothing left to ground against — the SAFE-because-empty trap. Treat that case as
        // a hard block so a side-effecting action fails closed in non-BYPASS instead of
        // resolving to a mere confirm against a blank screen.
        SecureReason.SENSITIVE_HIDDEN_NODE -> screen?.elements.isNullOrEmpty()
    }

    private fun blockForSecureReason(reason: SecureReason): PermissionDecision =
        PermissionDecision.Block(
            secureReason = if (reason == SecureReason.NONE) SecureReason.FLAG_SECURE_WINDOW else reason,
            message = secureReasonMessage(reason)
        )

    private fun secureReasonMessage(reason: SecureReason): String = when (reason) {
        SecureReason.NONE -> "This screen is blocked for safety."
        SecureReason.DENYLISTED_APP -> "This is a banking/wallet/authenticator app — automation is not allowed here."
        SecureReason.FLAG_SECURE_WINDOW -> "This is a secure screen — automation is blocked."
        SecureReason.PASSWORD_FIELD -> "This screen has a password field — automation is blocked."
        SecureReason.OTP_OR_CARD_FIELD -> "This screen asks for an OTP/PIN/card detail — automation is blocked."
        SecureReason.KEYGUARD -> "The device is locked — automation is blocked."
        SecureReason.SENSITIVE_HIDDEN_NODE -> "This screen hides sensitive content — proceeding requires confirmation."
    }

    private fun verbFor(type: ActionType): String = when (type) {
        ActionType.SEND_MESSAGE -> "Send message"
        ActionType.SEND_EMAIL -> "Send email"
        ActionType.PLACE_CALL -> "Place call"
        ActionType.MAKE_PURCHASE -> "Make purchase"
        ActionType.DELETE_DATA -> "Delete data"
        ActionType.INSTALL_APP -> "Install app"
        ActionType.UNINSTALL_APP -> "Uninstall app"
        ActionType.OPEN_APP -> "Open app"
        ActionType.SET_ALARM -> "Set alarm"
        ActionType.SET_TIMER -> "Set timer"
        ActionType.ADD_CALENDAR_EVENT -> "Add calendar event"
        ActionType.DIAL_PREFILL -> "Pre-fill dialer"
        ActionType.SMS_DRAFT -> "Draft SMS"
        ActionType.EMAIL_DRAFT -> "Draft email"
        ActionType.TORCH -> "Toggle torch"
        ActionType.BRIGHTNESS -> "Change brightness"
        ActionType.DO_NOT_DISTURB -> "Change Do-Not-Disturb"
        ActionType.MEDIA_VOLUME -> "Change media volume"
        ActionType.INPUT_TEXT -> "Type text"
        ActionType.TAP -> "Tap element"
        else -> type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Best-effort friendly name from a package id (last segment, title-cased). */
    private fun friendlyApp(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun prettyKey(key: String): String =
        key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
