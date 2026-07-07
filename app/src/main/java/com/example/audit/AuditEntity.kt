package com.example.audit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room persistence row for one [AuditEntry].
 *
 * This mirrors the contract [AuditEntry] one-to-one, with two deliberate choices that
 * keep the on-disk log privacy-safe and queryable:
 *
 * - It stores **[paramsHash] only** — never raw literal arguments. Per ARCHITECTURE.md §8
 *   the audit log must never be able to leak message bodies, phone numbers, OTP codes, or
 *   amounts, so the entity has no column for raw params at all.
 * - The enum-typed contract fields ([com.example.permission.AutonomyMode] and [AuditOutcome])
 *   are persisted as their stable `name` strings ([mode], [outcome]) rather than ordinals, so
 *   reordering an enum can never silently re-map historical rows. [AuditMappers] does the
 *   conversion back to the typed contract values.
 *
 * [timestampMs] is indexed because the only read path ([AuditDao.recent]) orders by it.
 */
@Entity(
    tableName = "audit_entries",
    indices = [Index(value = ["timestampMs"])]
)
data class AuditEntity(
    /** Stable id from [AuditEntry.id]; the natural primary key (no autogenerate). */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Wall-clock time of the action, epoch millis. */
    @ColumnInfo(name = "timestampMs")
    val timestampMs: Long,

    /** [com.example.permission.AutonomyMode] name in effect when the action ran. */
    @ColumnInfo(name = "mode")
    val mode: String,

    /** The [com.example.permission.ActionType] name (kept as String per the contract). */
    @ColumnInfo(name = "actionType")
    val actionType: String,

    /** Target package the action was aimed at, if known. */
    @ColumnInfo(name = "targetApp")
    val targetApp: String?,

    /** SHA-256 hex of the literal params — NEVER the raw values. */
    @ColumnInfo(name = "paramsHash")
    val paramsHash: String,

    /** Human-readable permission decision string. */
    @ColumnInfo(name = "decision")
    val decision: String,

    /** [AuditOutcome] name — the final disposition of the action. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    /** Optional undo token for reversible actions. */
    @ColumnInfo(name = "undoToken")
    val undoToken: String?
)
