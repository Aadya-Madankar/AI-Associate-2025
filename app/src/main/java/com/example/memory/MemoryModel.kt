package com.example.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * On-device long-term memory for XENO, modelled on human memory (see the design in the
 * `connect-zeno-database` brainstorm). One Room database ([MemoryDatabase]) holds four
 * layers as four tables:
 *
 *  - **Episodic** — [EpisodeRow] (one hands-free session/task) + [EventRow] (the actions/turns
 *    inside it). Auto-captured by the [com.example.agent.AgentCoordinator] as XENO acts.
 *  - **Semantic** — [FactRow]: durable facts XENO knows about the user and the world
 *    ("wife's name is Priya", "prefers Marathi jokes"). Written by the model via the
 *    `remember` tool and, conservatively, distilled from finished episodes.
 *  - **Prospective** — [IntentionRow]: discrete "remember to do X when Y" notes, surfaced when
 *    due. (XENO's always-on directive stays in [com.example.config.PersonaStore].)
 *  - **Procedural** — unchanged: saved skills in [com.example.skill.JsonFileSkillStore].
 *
 * Retrieval blends embedding cosine similarity, keyword overlap, recency, salience and use
 * frequency (see [com.example.memory.MemoryScoring]) — the "activation" of human recall.
 *
 * PRIVACY: [FactRow.sensitivity] gates what may be folded into the cloud-bound system prompt;
 * SECRET/PRIVATE facts are never injected. Nothing here ever stores verbatim password/OTP text —
 * episodic [EventRow.text] is the same redacted description shown on the confirm sheet.
 */

/** What an [EventRow] records. */
enum class EventKind { USER_UTTERANCE, XENO_UTTERANCE, TOOL_ACTION, OBSERVATION }

/** Where a [FactRow] came from — drives default confidence and trust. */
enum class FactSource { USER_TOLD, MODEL_ASSERTED, EXTRACTED }

/**
 * How freely a fact may reach the cloud model. Only [NORMAL] is cloud-eligible; [PRIVATE] and
 * [SECRET] stay on the device — they are never embedded (no write-time cloud send), never returned
 * by `recall`, and never folded into the system prompt. (The two non-cloud tiers behave the same
 * for now; the split is kept so a future on-device retrieval path can treat PRIVATE differently.)
 */
enum class Sensitivity { NORMAL, PRIVATE, SECRET }

/** Final disposition of an episode; [OPEN] while the session is still running. */
enum class EpisodeOutcome { OPEN, SUCCESS, FAILED, ABORTED }

/** What makes a prospective [IntentionRow] fire. */
enum class IntentionTrigger { TIME, APP_OPEN, TOPIC, MANUAL }

/** Lifecycle of a prospective [IntentionRow]. */
enum class IntentionStatus { PENDING, DONE, CANCELLED }

/**
 * One episode — a single hands-free session/task (opened on connect, closed on task_complete /
 * step cap / stop). The autobiographical "chapter"; its [summary] is kept long-term even after
 * the fine-grained [EventRow]s are pruned.
 */
@Entity(tableName = "episodes", indices = [Index(value = ["startedAt"])])
data class EpisodeRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    @ColumnInfo(name = "endedAt") val endedAt: Long? = null,
    @ColumnInfo(name = "outcome") val outcome: EpisodeOutcome = EpisodeOutcome.OPEN,
    @ColumnInfo(name = "summary") val summary: String = "",
    @ColumnInfo(name = "appContext") val appContext: String? = null,
    @ColumnInfo(name = "salience") val salience: Double = 1.0,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null
)

/**
 * One event inside an [EpisodeRow]: a tool action XENO took, or a user/XENO utterance. [text] is
 * the redacted human description (never raw field contents). Recalled by recency + keyword; not
 * embedded (to save embedding calls on high-volume rows).
 */
@Entity(tableName = "events", indices = [Index(value = ["episodeId"]), Index(value = ["ts"])])
data class EventRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "episodeId") val episodeId: String,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "kind") val kind: EventKind,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "toolName") val toolName: String? = null,
    @ColumnInfo(name = "targetApp") val targetApp: String? = null,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "outcome") val outcome: String? = null
)

/**
 * One semantic fact. Keyed for upsert by ([subject], [factKey]); a newer or more-confident write
 * supersedes the old (soft, via [supersededBy] + [deleted]). [text] is the natural-language form
 * used for recall and prompt injection.
 */
@Entity(tableName = "facts", indices = [Index(value = ["subject"]), Index(value = ["deleted"])])
data class FactRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "factKey") val factKey: String = "",
    @ColumnInfo(name = "value") val value: String = "",
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "confidence") val confidence: Double = 0.8,
    @ColumnInfo(name = "source") val source: FactSource = FactSource.MODEL_ASSERTED,
    @ColumnInfo(name = "sensitivity") val sensitivity: Sensitivity = Sensitivity.NORMAL,
    @ColumnInfo(name = "firstSeenAt") val firstSeenAt: Long,
    @ColumnInfo(name = "lastConfirmedAt") val lastConfirmedAt: Long,
    @ColumnInfo(name = "lastUsedAt") val lastUsedAt: Long,
    @ColumnInfo(name = "useCount") val useCount: Int = 0,
    @ColumnInfo(name = "salience") val salience: Double = 1.0,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
    @ColumnInfo(name = "deleted") val deleted: Boolean = false,
    @ColumnInfo(name = "supersededBy") val supersededBy: String? = null
)

/** One prospective note ("remind me to…"); surfaced when [dueAt] passes or its trigger matches. */
@Entity(tableName = "intentions", indices = [Index(value = ["status"])])
data class IntentionRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "trigger") val trigger: IntentionTrigger = IntentionTrigger.MANUAL,
    @ColumnInfo(name = "triggerValue") val triggerValue: String? = null,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "dueAt") val dueAt: Long? = null,
    @ColumnInfo(name = "status") val status: IntentionStatus = IntentionStatus.PENDING,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null
)

/** A ranked recall result handed back to the model / boot-context assembler. */
data class MemoryHit(
    val kind: String,
    val text: String,
    val score: Double,
    val subject: String? = null,
    val id: String? = null
)

/**
 * Room [TypeConverter]s for every enum column. Persisted as the stable `name` string and decoded
 * defensively so a row written by an older build can never crash on an unrecognized value.
 */
internal class MemoryConverters {
    @TypeConverter fun fromEventKind(v: EventKind): String = v.name
    @TypeConverter fun toEventKind(v: String): EventKind =
        EventKind.entries.firstOrNull { it.name == v } ?: EventKind.OBSERVATION

    @TypeConverter fun fromFactSource(v: FactSource): String = v.name
    @TypeConverter fun toFactSource(v: String): FactSource =
        FactSource.entries.firstOrNull { it.name == v } ?: FactSource.MODEL_ASSERTED

    @TypeConverter fun fromSensitivity(v: Sensitivity): String = v.name
    @TypeConverter fun toSensitivity(v: String): Sensitivity =
        Sensitivity.entries.firstOrNull { it.name == v } ?: Sensitivity.NORMAL

    @TypeConverter fun fromEpisodeOutcome(v: EpisodeOutcome): String = v.name
    @TypeConverter fun toEpisodeOutcome(v: String): EpisodeOutcome =
        EpisodeOutcome.entries.firstOrNull { it.name == v } ?: EpisodeOutcome.ABORTED

    @TypeConverter fun fromIntentionTrigger(v: IntentionTrigger): String = v.name
    @TypeConverter fun toIntentionTrigger(v: String): IntentionTrigger =
        IntentionTrigger.entries.firstOrNull { it.name == v } ?: IntentionTrigger.MANUAL

    @TypeConverter fun fromIntentionStatus(v: IntentionStatus): String = v.name
    @TypeConverter fun toIntentionStatus(v: String): IntentionStatus =
        IntentionStatus.entries.firstOrNull { it.name == v } ?: IntentionStatus.PENDING
}
