package com.example.memory

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The one facade over XENO's long-term memory ([MemoryDatabase]). It is the seam every other
 * layer uses:
 *  - the [com.example.agent.AgentCoordinator] opens/closes episodes and records actions,
 *  - the memory tools (`remember`/`recall`/`note_intention`/`forget`) read and write,
 *  - the [com.example.viewmodels.XenoViewModel] assembles the boot context at connect.
 *
 * Retrieval is a brute-force scan of a small candidate set scored by [MemoryScoring] — embedding
 * cosine + keyword overlap + recency + salience + use frequency. Embeddings are best-effort
 * ([EmbeddingClient]); with no key/network, recall degrades to keyword + recency and still works.
 */
class MemoryStore(
    context: Context,
    private val embedder: EmbeddingClient = GeminiEmbeddingClient(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val dao: MemoryDao by lazy { MemoryDatabase.getInstance(appContext).memoryDao() }

    // --- Episodic (auto-captured by the coordinator) -----------------------------------------

    /**
     * Open a fresh episode for a hands-free session under a caller-supplied [id] (generated
     * synchronously by the coordinator so its `currentEpisodeId` is valid immediately, with no
     * race against early events). Any dangling OPEN episode (app killed mid-task) is closed as
     * [EpisodeOutcome.ABORTED] first, so at most one is ever open.
     */
    suspend fun beginEpisode(id: String, appContext: String? = null) = withContext(ioDispatcher) {
        if (id.isBlank()) return@withContext
        runCatching {
            val now = System.currentTimeMillis()
            dao.openEpisodes().filter { it.id != id }.forEach {
                dao.upsertEpisode(it.copy(outcome = EpisodeOutcome.ABORTED, endedAt = now))
            }
            dao.upsertEpisode(EpisodeRow(id = id, startedAt = now, appContext = appContext))
        }
        Unit
    }

    /** Close an episode with its outcome + one-line [summary] (embedded best-effort). */
    suspend fun endEpisode(id: String, success: Boolean, summary: String) = withContext(ioDispatcher) {
        if (id.isBlank()) return@withContext
        runCatching {
            val existing = dao.episodeById(id)?.takeIf { it.outcome == EpisodeOutcome.OPEN }
                ?: return@runCatching
            val text = summary.trim()
            val embedding = if (text.isNotEmpty()) embedBounded(text)?.let(MemoryScoring::floatsToBytes) else null
            dao.upsertEpisode(
                existing.copy(
                    endedAt = System.currentTimeMillis(),
                    outcome = if (success) EpisodeOutcome.SUCCESS else EpisodeOutcome.FAILED,
                    summary = text,
                    embedding = embedding
                )
            )
            // Consolidation ("sleep"): prune fine-grained events past the retention window; the
            // episode summaries survive. ponytail: fixed 14-day window, lift to a setting later.
            dao.pruneEventsBefore(System.currentTimeMillis() - EVENT_RETENTION_MS)
        }
        Unit
    }

    /** Record one tool action inside [episodeId]. Pure screen reads are skipped as noise. */
    suspend fun recordEvent(
        episodeId: String,
        toolName: String,
        targetApp: String?,
        description: String,
        outcome: String
    ) = withContext(ioDispatcher) {
        if (episodeId.isBlank() || toolName in NOISE_TOOLS) return@withContext
        runCatching {
            val seq = dao.eventCount(episodeId)
            dao.insertEvent(
                EventRow(
                    id = UUID.randomUUID().toString(),
                    episodeId = episodeId,
                    seq = seq,
                    ts = System.currentTimeMillis(),
                    kind = EventKind.TOOL_ACTION,
                    actor = "tool",
                    toolName = toolName,
                    targetApp = targetApp,
                    text = description.ifBlank { toolName },
                    outcome = outcome
                )
            )
        }
        Unit
    }

    /** Record a typed user / spoken XENO turn inside [episodeId]. */
    suspend fun recordUtterance(episodeId: String, fromUser: Boolean, text: String) =
        withContext(ioDispatcher) {
            val clean = text.trim()
            if (episodeId.isBlank() || clean.isEmpty()) return@withContext
            runCatching {
                val seq = dao.eventCount(episodeId)
                dao.insertEvent(
                    EventRow(
                        id = UUID.randomUUID().toString(),
                        episodeId = episodeId,
                        seq = seq,
                        ts = System.currentTimeMillis(),
                        kind = if (fromUser) EventKind.USER_UTTERANCE else EventKind.XENO_UTTERANCE,
                        actor = if (fromUser) "user" else "xeno",
                        text = clean
                    )
                )
            }
            Unit
        }

    // --- Semantic ----------------------------------------------------------------------------

    /**
     * Write or update a durable fact. Upserts by ([subject], [factKey]) when a key is given (a
     * new value supersedes the old in place, bumping confirmation); a keyless free-form fact is
     * always inserted. Embedded best-effort for semantic recall.
     */
    suspend fun remember(
        subject: String?,
        factKey: String?,
        text: String,
        value: String? = null,
        sensitivity: Sensitivity = Sensitivity.NORMAL,
        source: FactSource = FactSource.MODEL_ASSERTED
    ): Boolean = withContext(ioDispatcher) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext false
        val subj = subject?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() } ?: "user"
        val key = factKey?.trim()?.lowercase().orEmpty()
        runCatching {
            val now = System.currentTimeMillis()
            // Only NORMAL facts are ever embedded — embedding POSTs the verbatim text to the cloud,
            // and SECRET/PRIVATE facts must never leave the device. They rely on keyword recall.
            val embedding = if (sensitivity == Sensitivity.NORMAL) {
                embedBounded(body)?.let(MemoryScoring::floatsToBytes)
            } else null
            val existing = if (key.isNotEmpty()) dao.factBySubjectKey(subj, key) else null
            if (existing != null) {
                dao.updateFact(
                    existing.copy(
                        value = value?.trim().orEmpty().ifEmpty { existing.value },
                        text = body,
                        sensitivity = sensitivity,
                        source = source,
                        lastConfirmedAt = now,
                        confidence = maxOf(existing.confidence, defaultConfidence(source)),
                        embedding = embedding ?: existing.embedding
                    )
                )
            } else {
                dao.upsertFact(
                    FactRow(
                        id = UUID.randomUUID().toString(),
                        subject = subj,
                        factKey = key,
                        value = value?.trim().orEmpty(),
                        text = body,
                        confidence = defaultConfidence(source),
                        source = source,
                        sensitivity = sensitivity,
                        firstSeenAt = now,
                        lastConfirmedAt = now,
                        lastUsedAt = now,
                        embedding = embedding
                    )
                )
            }
            true
        }.getOrDefault(false)
    }

    // --- Prospective -------------------------------------------------------------------------

    suspend fun noteIntention(
        text: String,
        trigger: IntentionTrigger = IntentionTrigger.MANUAL,
        triggerValue: String? = null,
        dueAt: Long? = null
    ): Boolean = withContext(ioDispatcher) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext false
        runCatching {
            dao.upsertIntention(
                IntentionRow(
                    id = UUID.randomUUID().toString(),
                    text = body,
                    trigger = trigger,
                    triggerValue = triggerValue?.trim()?.takeUnless { it.isEmpty() },
                    createdAt = System.currentTimeMillis(),
                    dueAt = dueAt,
                    // Intentions aren't semantically searched (boot just lists pending ones), so
                    // there's nothing to gain from a write-time cloud embed — skip it.
                    embedding = null
                )
            )
            true
        }.getOrDefault(false)
    }

    // --- Correction / privacy ----------------------------------------------------------------

    /** Soft-delete a fact (by id or "subject:key") or cancel an intention (by id). */
    suspend fun forget(idOrKey: String): Boolean = withContext(ioDispatcher) {
        val ref = idOrKey.trim()
        if (ref.isEmpty()) return@withContext false
        runCatching {
            dao.factById(ref)?.let {
                dao.updateFact(it.copy(deleted = true))
                return@runCatching true
            }
            dao.intentionById(ref)?.let {
                dao.upsertIntention(it.copy(status = IntentionStatus.CANCELLED))
                return@runCatching true
            }
            if (ref.contains(':')) {
                val subj = ref.substringBefore(':').trim().lowercase()
                val key = ref.substringAfter(':').trim().lowercase()
                dao.factBySubjectKey(subj, key)?.let {
                    dao.updateFact(it.copy(deleted = true))
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    /** Erase everything (privacy "forget me"). */
    suspend fun wipe() = withContext(ioDispatcher) {
        runCatching {
            dao.deleteAllEvents(); dao.deleteAllEpisodes(); dao.deleteAllFacts(); dao.deleteAllIntentions()
        }
        Unit
    }

    // --- Retrieval ---------------------------------------------------------------------------

    /**
     * Rank memory against [query] and return the top [limit] hits (facts + recent episode
     * summaries). Bumps use frequency / recency on the facts it returns, so recalled memories
     * strengthen — the human "retrieval practice" effect. Never throws; empty on failure.
     */
    suspend fun recall(query: String, limit: Int = 6): List<MemoryHit> = withContext(ioDispatcher) {
        runCatching {
            val q = query.trim()
            val qEmb = if (q.isNotEmpty()) embedBounded(q) else null
            val now = System.currentTimeMillis()

            val factHits = dao.liveFacts(FACT_CANDIDATES).map { f ->
                val score = MemoryScoring.scoreFact(
                    query = q, qEmb = qEmb, text = f.text, emb = f.embedding,
                    lastConfirmedAt = f.lastConfirmedAt, salience = f.salience, useCount = f.useCount, now = now
                )
                f to MemoryHit(kind = "fact", text = f.text, score = score, subject = f.subject, id = f.id)
            }
            val episodeHits = dao.recentEpisodes(EPISODE_CANDIDATES).map { e ->
                val score = MemoryScoring.scoreEpisode(
                    query = q, qEmb = qEmb, summary = e.summary, emb = e.embedding, startedAt = e.startedAt, now = now
                )
                MemoryHit(kind = "episode", text = e.summary, score = score, id = e.id)
            }

            val top = (factHits.map { it.second } + episodeHits)
                .filter { it.text.isNotBlank() }
                .sortedByDescending { it.score }
                .take(limit)

            // Strengthen the facts we actually surfaced (retrieval practice).
            val topFactIds = top.filter { it.kind == "fact" }.mapNotNull { it.id }.toSet()
            factHits.filter { it.first.id in topFactIds }.forEach { (f, _) ->
                runCatching { dao.updateFact(f.copy(lastUsedAt = now, useCount = f.useCount + 1)) }
            }
            top
        }.getOrDefault(emptyList())
    }

    /**
     * The "WHAT YOU KNOW" block folded into the system instruction at connect: top non-sensitive
     * facts, recent episode summaries, and pending intentions. Returns "" when memory is empty.
     * Character-budgeted so the prompt stays sharp.
     */
    suspend fun assembleBootContext(): String = withContext(ioDispatcher) {
        runCatching {
            val facts = dao.bootFacts(BOOT_FACTS)
            val episodes = dao.recentEpisodes(BOOT_EPISODES)
            val intentions = dao.pendingIntentions(BOOT_INTENTIONS)
            if (facts.isEmpty() && episodes.isEmpty() && intentions.isEmpty()) return@runCatching ""

            buildString {
                append("WHAT YOU REMEMBER (your on-device memory of this user — use it naturally, ")
                append("never read it aloud verbatim):")
                if (facts.isNotEmpty()) {
                    append("\nFacts you know:")
                    facts.forEach { append("\n• ${it.text}") }
                }
                if (episodes.isNotEmpty()) {
                    append("\nRecently you helped with:")
                    episodes.forEach { append("\n• ${it.summary}") }
                }
                if (intentions.isNotEmpty()) {
                    append("\nYou meant to:")
                    intentions.forEach { append("\n• ${it.text}") }
                }
            }.take(BOOT_CHAR_BUDGET)
        }.getOrDefault("")
    }

    /**
     * Embed [text] but never let it gate a tool response for long: the coroutine-level cap keeps a
     * slow network from stalling the sequential Live turn (embedding is best-effort enrichment, so
     * a timeout just falls back to keyword+recency). Complements the shorter OkHttp call timeout.
     */
    private suspend fun embedBounded(text: String): FloatArray? =
        withTimeoutOrNull(EMBED_TIMEOUT_MS) { embedder.embed(text) }

    private fun defaultConfidence(source: FactSource): Double = when (source) {
        FactSource.USER_TOLD -> 0.95
        FactSource.MODEL_ASSERTED -> 0.8
        FactSource.EXTRACTED -> 0.6
    }

    private companion object {
        const val EMBED_TIMEOUT_MS = 1200L
        const val EVENT_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        const val FACT_CANDIDATES = 300
        const val EPISODE_CANDIDATES = 40
        const val BOOT_FACTS = 8
        const val BOOT_EPISODES = 4
        const val BOOT_INTENTIONS = 6
        const val BOOT_CHAR_BUDGET = 2000
        val NOISE_TOOLS = setOf("get_screen", "take_screenshot")
    }
}

/**
 * Pure, stateless recall math — extracted so the ranking is unit-testable without Room/Android
 * (see MemoryScoringTest). Models human recall as a blend of relevance (semantic + keyword),
 * recency and strength (salience + frequency).
 *
 * ponytail: fixed weights + 30-day recency half-life. Tune against real recall quality; expose as
 * settings only if it earns it.
 */
object MemoryScoring {

    private const val RECENCY_HALF_LIFE_MS = 30.0 * 24 * 60 * 60 * 1000

    /** Little-endian float32 packing so an embedding round-trips through a Room BLOB column. */
    fun floatsToBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun bytesToFloats(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }

    /** Cosine similarity in [-1, 1]; 0 when either vector is missing or lengths differ. */
    fun cosine(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Fraction of query tokens present in [text], in [0, 1]. Cheap keyword-recall signal. */
    fun keyword(query: String, text: String): Double {
        val q = tokenize(query)
        if (q.isEmpty()) return 0.0
        val t = tokenize(text)
        if (t.isEmpty()) return 0.0
        val hit = q.count { it in t }
        return hit.toDouble() / q.size
    }

    /** Exponential recency decay in (0, 1]; 1.0 at age 0, 0.5 at one half-life. */
    fun recency(ageMs: Long): Double {
        if (ageMs <= 0L) return 1.0
        return exp(-ln(2.0) * ageMs / RECENCY_HALF_LIFE_MS)
    }

    fun scoreFact(
        query: String, qEmb: FloatArray?, text: String, emb: ByteArray?,
        lastConfirmedAt: Long, salience: Double, useCount: Int, now: Long
    ): Double {
        val sem = semantic(qEmb, emb)
        val kw = keyword(query, text)
        val rec = recency(now - lastConfirmedAt)
        val sal = (salience / 2.0).coerceIn(0.0, 1.0)
        val freq = (useCount / 10.0).coerceIn(0.0, 1.0)
        return 0.45 * sem + 0.25 * kw + 0.15 * rec + 0.10 * sal + 0.05 * freq
    }

    fun scoreEpisode(
        query: String, qEmb: FloatArray?, summary: String, emb: ByteArray?, startedAt: Long, now: Long
    ): Double {
        val sem = semantic(qEmb, emb)
        val kw = keyword(query, summary)
        val rec = recency(now - startedAt)
        // Episodes lean more on recency than facts do.
        return 0.4 * sem + 0.25 * kw + 0.35 * rec
    }

    /**
     * Semantic similarity signal in [0, 1]: 0 when either embedding is absent AND 0 for an
     * unrelated/orthogonal pair (cosine <= 0). Using max(cosine, 0) — not (cosine+1)/2 — so that a
     * merely-unrelated embedded memory does not get a ~0.5 baseline that outranks a relevant memory
     * that happens to have no embedding (e.g. written while offline).
     */
    private fun semantic(qEmb: FloatArray?, emb: ByteArray?): Double =
        if (qEmb != null && emb != null) cosine(qEmb, bytesToFloats(emb)).coerceAtLeast(0.0) else 0.0

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
}
