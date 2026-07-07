package com.example.audit

import com.example.permission.AutonomyMode

/**
 * Conversions between the persistence row [AuditEntity] and the contract [AuditEntry].
 *
 * The two carry the same data; the only translation is enum <-> stable `name` string for
 * [AutonomyMode] and [AuditOutcome]. Unknown/legacy enum names are decoded defensively so a
 * historical row written by an older build can never crash the audit screen:
 * an unrecognized mode falls back to [AutonomyMode.ASK] (the safest baseline) and an
 * unrecognized outcome to [AuditOutcome.FAILED].
 */
internal object AuditMappers {

    /** Contract -> persistence. Enums are stored as their stable [Enum.name]. */
    fun AuditEntry.toEntity(): AuditEntity = AuditEntity(
        id = id,
        timestampMs = timestampMs,
        mode = mode.name,
        actionType = actionType,
        targetApp = targetApp,
        paramsHash = paramsHash,
        decision = decision,
        outcome = outcome.name,
        undoToken = undoToken
    )

    /** Persistence -> contract. Enum strings are decoded leniently (see class KDoc). */
    fun AuditEntity.toEntry(): AuditEntry = AuditEntry(
        id = id,
        timestampMs = timestampMs,
        mode = mode.toAutonomyMode(),
        actionType = actionType,
        targetApp = targetApp,
        paramsHash = paramsHash,
        decision = decision,
        outcome = outcome.toAuditOutcome(),
        undoToken = undoToken
    )

    private fun String.toAutonomyMode(): AutonomyMode =
        AutonomyMode.entries.firstOrNull { it.name == this } ?: AutonomyMode.ASK

    private fun String.toAuditOutcome(): AuditOutcome =
        AuditOutcome.entries.firstOrNull { it.name == this } ?: AuditOutcome.FAILED
}
