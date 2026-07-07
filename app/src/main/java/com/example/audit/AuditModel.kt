package com.example.audit

import com.example.permission.AutonomyMode

/** Final disposition of an audited action. */
enum class AuditOutcome { ALLOWED_AUTO, ALLOWED_CONFIRMED, BLOCKED, DENIED_BY_USER, FAILED, UNDONE }

/**
 * One append-only audit record. Per the privacy rules (ARCHITECTURE.md §8) this NEVER
 * stores raw field contents — only a [paramsHash] of the literal arguments — so the
 * log itself can't leak message bodies, numbers, or amounts.
 */
data class AuditEntry(
    val id: String,
    val timestampMs: Long,
    val mode: AutonomyMode,
    val actionType: String,
    val targetApp: String?,
    val paramsHash: String,
    val decision: String,
    val outcome: AuditOutcome,
    val undoToken: String? = null
)

/** Append-only audit sink (Room-backed in the real impl). */
interface AuditLog {
    suspend fun record(entry: AuditEntry)
    suspend fun recent(limit: Int = 100): List<AuditEntry>
}
