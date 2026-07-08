package com.example.permission

import com.example.accessibility.ScreenState

/**
 * Evaluates an [AgentAction] against the active [AutonomyMode], the rule store, and
 * the current screen context, returning a [PermissionDecision].
 *
 * First-match order (ARCHITECTURE.md §4.2), applied in EVERY mode including BYPASS:
 *   1. global denylist (apps + action types) → Block
 *   2. forced-ask list (send-money, delete-all, place-call) → Confirm
 *   3. secure-context detector → Block
 *   4. per-action/per-app allow rules + current mode → Allow or Confirm
 */
interface PermissionEngine {
    /**
     * @param keyguardLocked true when the device is currently locked (keyguard showing);
     *   forces [SecureReason.KEYGUARD] ahead of every other secure-context signal.
     *   Defaults to `false` for callers that have no keyguard reading.
     */
    fun decide(
        action: AgentAction,
        mode: AutonomyMode,
        screen: ScreenState?,
        keyguardLocked: Boolean = false
    ): PermissionDecision
}

/** Detects sensitive/secure contexts that must block automation regardless of mode. */
interface SecureContextDetector {
    fun inspect(screen: ScreenState?): SecureReason

    /**
     * As [inspect], but lets the caller assert a locked keyguard — which forces
     * [SecureReason.KEYGUARD] ahead of every other signal — since the bare [ScreenState]
     * carries no keyguard flag of its own.
     */
    fun inspectWithKeyguard(screen: ScreenState?, keyguardLocked: Boolean): SecureReason
}

/** Persisted allow/deny rules (DataStore-backed). */
interface RuleStore {
    fun isDenied(action: AgentAction): Boolean
    fun isAllowed(action: AgentAction, mode: AutonomyMode): Boolean
    fun grant(action: AgentAction, scope: RuleScope)
    fun revokeSession()
}
