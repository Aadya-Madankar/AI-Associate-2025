package com.example.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data-access for [MemoryDatabase]. One DAO over all four tables keeps the wiring in a single
 * place; the [MemoryStore] facade is the only caller and layers the scoring/retrieval on top.
 *
 * Reads deliberately bound their result sets (candidate caps) so retrieval loads a small working
 * set into memory and scores it in Kotlin — no vector index needed at this scale. When the store
 * outgrows a brute-force scan, an FTS mirror / vector index slots in behind this DAO.
 */
@Dao
interface MemoryDao {

    // --- Episodes ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(row: EpisodeRow)

    @Query("SELECT * FROM episodes WHERE outcome = 'OPEN' ORDER BY startedAt DESC")
    suspend fun openEpisodes(): List<EpisodeRow>

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun episodeById(id: String): EpisodeRow?

    @Query("SELECT * FROM episodes WHERE endedAt IS NOT NULL AND summary != '' ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentEpisodes(limit: Int): List<EpisodeRow>

    // --- Events ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(row: EventRow)

    @Query("SELECT COUNT(*) FROM events WHERE episodeId = :episodeId")
    suspend fun eventCount(episodeId: String): Int

    @Query("SELECT * FROM events WHERE episodeId = :episodeId ORDER BY seq ASC")
    suspend fun eventsFor(episodeId: String): List<EventRow>

    @Query("SELECT * FROM events ORDER BY ts DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<EventRow>

    /** Prune fine-grained events older than [beforeTs]; episode summaries are kept. */
    @Query("DELETE FROM events WHERE ts < :beforeTs")
    suspend fun pruneEventsBefore(beforeTs: Long)

    // --- Facts -------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFact(row: FactRow)

    @Update
    suspend fun updateFact(row: FactRow)

    @Query("SELECT * FROM facts WHERE deleted = 0 AND subject = :subject AND factKey = :factKey LIMIT 1")
    suspend fun factBySubjectKey(subject: String, factKey: String): FactRow?

    @Query("SELECT * FROM facts WHERE id = :id LIMIT 1")
    suspend fun factById(id: String): FactRow?

    /**
     * Candidate set for recall(). NORMAL-only: recall returns hit text to the cloud model, so it
     * is a cloud-bound injection path exactly like the boot context — SECRET/PRIVATE facts must
     * never leave the device through it.
     */
    @Query("SELECT * FROM facts WHERE deleted = 0 AND sensitivity = 'NORMAL' ORDER BY salience DESC, lastConfirmedAt DESC LIMIT :limit")
    suspend fun liveFacts(limit: Int): List<FactRow>

    /** Live, non-sensitive facts for the boot context, freshest first. */
    @Query("SELECT * FROM facts WHERE deleted = 0 AND sensitivity = 'NORMAL' ORDER BY salience DESC, lastConfirmedAt DESC LIMIT :limit")
    suspend fun bootFacts(limit: Int): List<FactRow>

    // --- Intentions --------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIntention(row: IntentionRow)

    @Query("SELECT * FROM intentions WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun pendingIntentions(limit: Int): List<IntentionRow>

    @Query("SELECT * FROM intentions WHERE id = :id LIMIT 1")
    suspend fun intentionById(id: String): IntentionRow?

    // --- Wipe (privacy) ----------------------------------------------------------------------

    @Query("DELETE FROM facts") suspend fun deleteAllFacts()
    @Query("DELETE FROM events") suspend fun deleteAllEvents()
    @Query("DELETE FROM episodes") suspend fun deleteAllEpisodes()
    @Query("DELETE FROM intentions") suspend fun deleteAllIntentions()
}
