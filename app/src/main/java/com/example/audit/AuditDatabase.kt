package com.example.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database hosting the append-only audit log.
 *
 * Single table ([AuditEntity]); single DAO ([AuditDao]). The database is exposed through a
 * process-wide singleton so every collaborator (foreground service, ViewModel, audit screen)
 * shares one connection rather than opening competing handles to the same file.
 */
@Database(
    entities = [AuditEntity::class],
    version = 1,
    exportSchema = false
)
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
                // The audit log is the append-only, user-facing accountability record that past
                // agent actions happened and the permission gate was honored (ARCHITECTURE.md
                // §4.4); those rows are NOT regenerable, so a schema upgrade must NEVER drop them.
                // Every version increment must ship an explicit Room Migration that preserves
                // existing rows, registered here, e.g.:
                //     .addMigrations(MIGRATION_1_2, /* MIGRATION_2_3, ... */)
                // We are still at v1, so no upgrade migrations exist yet. Only an unforeseen
                // *downgrade* may fall back to a destructive recreate (the schema for the older
                // app version cannot be reconstructed forward); a forward upgrade with a missing
                // migration must fail loudly rather than silently erase the forensic record.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
