package com.example.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data-access object for the append-only audit log.
 *
 * The log is conceptually append-only: there is an [insert] and a single [recent] read.
 * No update or delete is exposed here — pruning, if ever needed, is a deliberate
 * maintenance concern handled elsewhere, not a routine operation on this DAO.
 */
@Dao
interface AuditDao {

    /**
     * Appends one row. Uses [OnConflictStrategy.IGNORE] so that re-recording an entry
     * with an id that already exists is a harmless no-op rather than overwriting a
     * historical row — keeping the log truly append-only and idempotent on the stable id.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AuditRecord)

    /**
     * Returns the most recent [limit] rows, newest first.
     *
     * @param limit maximum number of rows to return.
     */
    @Query("SELECT * FROM audit_entries ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditRecord>
}
