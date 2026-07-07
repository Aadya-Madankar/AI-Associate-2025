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
    fun decide(action: AgentAction, mode: AutonomyMode, screen: ScreenState?): PermissionDecision
}

/** Detects sensitive/secure contexts that must block automation regardless of mode. */
interface SecureContextDetector {
    fun inspect(screen: ScreenState?): SecureReason
}

/** Persisted allow/deny rules (DataStore-backed). */
interface RuleStore {
    fun isDenied(action: AgentAction): Boolean
    fun isAllowed(action: AgentAction, mode: AutonomyMode): Boolean
    fun grant(action: AgentAction, scope: RuleScope)
    fun revokeSession()
}
