package com.example.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database hosting XENO's long-term memory ([EpisodeRow], [EventRow], [FactRow],
 * [IntentionRow]). Separate from the diagnostic audit DB — this is real user memory, exposed
 * back to the model via the [MemoryStore] facade.
 *
 * Process-wide singleton (double-checked), built on the application context. Mirrors the
 * [com.example.audit.AuditDatabase] pattern.
 *
 * ponytail: v1 uses destructive migration — a schema change wipes memory rather than shipping a
 * Migration. Acceptable pre-launch; add real migrations before this ships to users, since unlike
 * the audit log these rows are not regenerable.
 */
@Database(
    entities = [EpisodeRow::class, EventRow::class, FactRow::class, IntentionRow::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(MemoryConverters::class)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DB_NAME = "xeno_memory.db"

        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): MemoryDatabase =
            Room.databaseBuilder(appContext, MemoryDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
