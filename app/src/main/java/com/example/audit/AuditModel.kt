package com.example.audit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.permission.AutonomyMode

/** Final disposition of an audited action. */
enum class AuditOutcome { ALLOWED_AUTO, ALLOWED_CONFIRMED, BLOCKED, DENIED_BY_USER, FAILED }

/**
 * One append-only audit record — the Room row *is* the contract type, used directly by
 * [AuditDao] and [RoomAuditLog]. Per the privacy rules (ARCHITECTURE.md §8) this NEVER
 * stores raw field contents — [paramsHash] is a random per-entry token, not derived from
 * the arguments — so the log itself can't leak message bodies, numbers, or amounts.
 *
 * [mode] and [outcome] are enums but persist as their stable `name` string via
 * [AuditConverters], so reordering an enum can never silently re-map historical rows.
 */
@Entity(
    tableName = "audit_entries",
    indices = [Index(value = ["timestampMs"])]
)
data class AuditRecord(
    /** Stable id, the natural primary key (no autogenerate). */
    @PrimaryKey
    val id: String,

    /** Wall-clock time of the action, epoch millis. Indexed — [AuditDao.recent] orders by it. */
    @ColumnInfo(name = "timestampMs")
    val timestampMs: Long,

    /** [AutonomyMode] in effect when the action ran. */
    @ColumnInfo(name = "mode")
    val mode: AutonomyMode,

    /** The [com.example.permission.ActionType] name (kept as String per the contract). */
    @ColumnInfo(name = "actionType")
    val actionType: String,

    /** Target package the action was aimed at, if known. */
    @ColumnInfo(name = "targetApp")
    val targetApp: String?,

    /** Random per-entry token — never derived from or containing the raw params. */
    @ColumnInfo(name = "paramsHash")
    val paramsHash: String,

    /** Human-readable permission decision string. */
    @ColumnInfo(name = "decision")
    val decision: String,

    /** Final disposition of the action. */
    @ColumnInfo(name = "outcome")
    val outcome: AuditOutcome
)

/**
 * Room [TypeConverter]s for the two enum columns on [AuditRecord]. Unknown/legacy names are
 * decoded defensively so a historical row from an older build can never crash the audit
 * screen: an unrecognized mode falls back to [AutonomyMode.ASK] (the safest baseline) and an
 * unrecognized outcome to [AuditOutcome.FAILED].
 */
internal class AuditConverters {
    @TypeConverter
    fun fromAutonomyMode(mode: AutonomyMode): String = mode.name

    @TypeConverter
    fun toAutonomyMode(value: String): AutonomyMode =
        AutonomyMode.entries.firstOrNull { it.name == value } ?: AutonomyMode.ASK

    @TypeConverter
    fun fromAuditOutcome(outcome: AuditOutcome): String = outcome.name

    @TypeConverter
    fun toAuditOutcome(value: String): AuditOutcome =
        AuditOutcome.entries.firstOrNull { it.name == value } ?: AuditOutcome.FAILED
}

/** Append-only audit sink (Room-backed in the real impl). */
interface AuditLog {
    suspend fun record(entry: AuditRecord)
    suspend fun recent(limit: Int = 100): List<AuditRecord>
}
