package com.example.audit

import android.content.Context
import com.example.audit.AuditMappers.toEntity
import com.example.audit.AuditMappers.toEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed implementation of the [AuditLog] contract.
 *
 * Both operations are `suspend` and run on an IO dispatcher so disk access never blocks the
 * caller's thread (typically the agent loop / ViewModel main scope). Entity <-> contract
 * conversion is delegated to [AuditMappers]; raw literal params are never stored — only a
 * random per-entry id carried on [AuditEntry.paramsHash].
 *
 * @param dao the underlying DAO.
 * @param ioDispatcher dispatcher for the blocking Room calls; overridable for tests.
 */
class RoomAuditLog(
    private val dao: AuditDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuditLog {

    override suspend fun record(entry: AuditEntry) = withContext(ioDispatcher) {
        dao.insert(entry.toEntity())
    }

    override suspend fun recent(limit: Int): List<AuditEntry> = withContext(ioDispatcher) {
        dao.recent(limit).map { it.toEntry() }
    }

    companion object {
        /**
         * Convenience factory wiring a [RoomAuditLog] over the process-wide [AuditDatabase].
         *
         * @param context any context; the application context is used internally.
         */
        fun create(context: Context): RoomAuditLog =
            RoomAuditLog(AuditDatabase.getInstance(context).auditDao())
    }
}
