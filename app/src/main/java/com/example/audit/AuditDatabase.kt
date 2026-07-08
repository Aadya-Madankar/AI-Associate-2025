package com.example.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database hosting the append-only audit log.
 *
 * Single table ([AuditRecord]); single DAO ([AuditDao]). The database is exposed through a
 * process-wide singleton so every collaborator (foreground service, ViewModel, audit screen)
 * shares one connection rather than opening competing handles to the same file.
 */
@Database(
    entities = [AuditRecord::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(AuditConverters::class)
abstract class AuditDatabase : RoomDatabase() {

    /** Accessor for the audit DAO. */
    abstract fun auditDao(): AuditDao

    companion object {
        private const val DB_NAME = "xeno_audit.db"

        @Volatile
        private var instance: AuditDatabase? = null

        /**
         * Returns the process-wide [AuditDatabase], building it on first use.
         *
         * Uses the application context (via [Context.getApplicationContext]) to avoid leaking
         * the caller's Activity/Service, and double-checked locking so concurrent callers
         * never race to create two databases over the same file.
         */
        fun getInstance(context: Context): AuditDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): AuditDatabase =
            Room.databaseBuilder(appContext, AuditDatabase::class.java, DB_NAME)
                // The audit log is diagnostic, not user data (ARCHITECTURE.md §4.4 covers the
                // *live* permission gate; this table is just its trailing history). A schema
                // change simply wipes and recreates rather than shipping a Migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
