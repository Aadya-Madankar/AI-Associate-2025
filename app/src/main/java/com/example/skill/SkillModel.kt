package com.example.skill

import android.content.Context
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device "skill memory" for Nazim. A *skill* is a named, ordered list of tool calls the
 * model has bundled together so it can replay them later. Everything lives on the phone — the
 * sole backing store is a JSON file in `filesDir` ([JsonFileSkillStore]). No database, no cloud.
 *
 * The model interacts with skills exclusively through the three [AgentTool]s below
 * (`save_skill`, `list_skills`, `recall_skill`). Recall deliberately does NOT execute anything:
 * it hands the steps back so the model re-issues them as ordinary, permission-gated tool calls.
 */

/** One step of a skill: the tool to invoke and the arguments to pass it. */
data class SkillStep(
    val tool: String,
    val args: Map<String, Any?> = emptyMap()
)

/** A named, replayable bundle of [SkillStep]s plus light metadata. */
data class Skill(
    val name: String,
    val description: String,
    val steps: List<SkillStep>,
    val createdAtMs: Long
)

/** Persistence boundary for skills. Implementations must be safe to call from any thread. */
interface SkillStore {
    /** Every saved skill, in no guaranteed order. Never null; empty when nothing is stored. */
    fun all(): List<Skill>

    /** The skill with [name], or null if none exists. */
    fun get(name: String): Skill?

    /** Insert or overwrite the skill keyed by [Skill.name]. */
    fun save(skill: Skill)

    /** Remove the skill named [name]; a no-op if it does not exist. */
    fun delete(name: String)
}

/**
 * [SkillStore] backed by a single JSON array file at `filesDir/nazim_skills.json`. THIS FILE is
 * the on-device memory. Design choices:
 *
 *  - **Thread-safe:** every public method synchronizes on [lock]; reads and writes never overlap.
 *  - **Fail-soft:** a missing or corrupt file is treated as an empty list rather than crashing,
 *    so a bad write can never permanently brick skill memory.
 *  - **Last-write-wins by name:** [save] replaces any existing skill with the same name.
 */
class JsonFileSkillStore(context: Context) : SkillStore {

    // Use applicationContext to avoid leaking an Activity/Service through a long-lived store.
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    override fun all(): List<Skill> = synchronized(lock) { readAll() }

    override fun get(name: String): Skill? = synchronized(lock) {
        readAll().firstOrNull { it.name == name }
    }

    override fun save(skill: Skill): Unit = synchronized(lock) {
        val merged = readAll().filterNot { it.name == skill.name } + skill
        writeAll(merged)
    }

    override fun delete(name: String): Unit = synchronized(lock) {
        val current = readAll()
        val remaining = current.filterNot { it.name == name }
        if (remaining.size != current.size) writeAll(remaining)
    }

    // --- I/O (always called under [lock]) ----------------------------------------------------

    /** Read and parse the whole file. Any failure (missing/corrupt) yields an empty list. */
    private fun readAll(): List<Skill> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            val out = ArrayList<Skill>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                skillFromJson(obj)?.let { out.add(it) }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** Serialize and atomically-ish replace the file. Failures are swallowed (fail-soft). */
    private fun writeAll(skills: List<Skill>) {
        runCatching {
            val arr = JSONArray()
            skills.forEach { arr.put(skillToJson(it)) }
            // Write to a temp sibling then rename so a crash mid-write can't truncate the store.
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems; fall back to a direct write.
                file.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    // --- (De)serialization --------------------------------------------------------------------

    private fun skillToJson(skill: Skill): JSONObject = JSONObject().apply {
        put(KEY_NAME, skill.name)
        put(KEY_DESCRIPTION, skill.description)
        put(KEY_CREATED_AT, skill.createdAtMs)
        put(KEY_STEPS, JSONArray().apply {
            skill.steps.forEach { step ->
                put(JSONObject().apply {
                    put(KEY_TOOL, step.tool)
                    put(KEY_ARGS, mapToJson(step.args))
                })
            }
        })
    }

    /** Parse one skill object defensively; returns null if it lacks a usable name. */
    private fun skillFromJson(obj: JSONObject): Skill? {
        val name = obj.optString(KEY_NAME, "")
        if (name.isEmpty()) return null
        val description = obj.optString(KEY_DESCRIPTION, "")
        // Numbers can come back as Int/Long/Double depending on the writer; optLong copes.
        val createdAtMs = obj.optLong(KEY_CREATED_AT, 0L)
        val stepsArr = obj.optJSONArray(KEY_STEPS) ?: JSONArray()
        val steps = ArrayList<SkillStep>(stepsArr.length())
        for (i in 0 until stepsArr.length()) {
            val stepObj = stepsArr.optJSONObject(i) ?: continue
            val tool = stepObj.optString(KEY_TOOL, "")
            if (tool.isEmpty()) continue
            val args = stepObj.optJSONObject(KEY_ARGS)?.let { jsonToMap(it) } ?: emptyMap()
            steps.add(SkillStep(tool, args))
        }
        return Skill(name, description, steps, createdAtMs)
    }

    private companion object {
        const val FILE_NAME = "nazim_skills.json"
        const val KEY_NAME = "name"
        const val KEY_DESCRIPTION = "description"
        const val KEY_CREATED_AT = "createdAtMs"
        const val KEY_STEPS = "steps"
        const val KEY_TOOL = "tool"
        const val KEY_ARGS = "args"
    }
}

// --- Shared JSON <-> Map helpers --------------------------------------------------------------

/**
 * Recursively convert an arbitrary arg [Map] into a [JSONObject]. Unsupported values fall back
 * to [JSONObject.NULL] so serialization never throws.
 */
private fun mapToJson(map: Map<String, Any?>): JSONObject {
    val obj = JSONObject()
    for ((k, v) in map) obj.put(k, anyToJson(v))
    return obj
}

private fun anyToJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray -> value
    is Map<*, *> -> {
        val o = JSONObject()
        for ((k, v) in value) if (k != null) o.put(k.toString(), anyToJson(v))
        o
    }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
    is Array<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
    is String, is Boolean, is Int, is Long, is Double, is Float -> value
    is Number -> value
    else -> value.toString()
}

/** Recursively convert a [JSONObject] back into a plain Kotlin map of standard types. */
private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(obj.length())
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = jsonToValue(obj.get(key))
    }
    return out
}

private fun jsonToList(arr: JSONArray): List<Any?> {
    val out = ArrayList<Any?>(arr.length())
    for (i in 0 until arr.length()) out.add(jsonToValue(arr.get(i)))
    return out
}

private fun jsonToValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> jsonToMap(value)
    is JSONArray -> jsonToList(value)
    else -> value
}

// --- Tools the model uses to read/write skill memory ------------------------------------------

/**
 * `save_skill` — persist a reusable bundle of tool calls. Stamps [Skill.createdAtMs] with the
 * current wall-clock time. Last-write-wins by name (see [SkillStore.save]).
 */
class SaveSkillTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "save_skill",
        description = "Save a named, reusable skill: an ordered list of tool calls (each with a " +
            "tool name and its arguments) that can be recalled and replayed later. Overwrites any " +
            "existing skill with the same name.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string", "description": "Unique skill name (the lookup key)." },
                "description": { "type": "string", "description": "What the skill does." },
                "steps": {
                  "type": "array",
                  "description": "Ordered tool calls to replay.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": { "type": "string", "description": "Tool name to invoke." },
                      "args": { "type": "object", "description": "Arguments for that tool." }
                    },
                    "required": ["tool"]
                  }
                }
              },
              "required": ["name", "description", "steps"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val name = (args["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "'name' is required.")
        }
        val description = (args["description"] as? String)?.trim().orEmpty()
        val steps = parseSteps(args["steps"])
        if (steps.isEmpty()) {
            return ToolResult.Failure(
                declaration.name, callId,
                "'steps' must be a non-empty array of {tool, args} objects."
            )
        }

        return runCatching {
            store.save(
                Skill(
                    name = name,
                    description = description,
                    steps = steps,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            ToolResult.Success(
                declaration.name, callId,
                "Saved skill '$name'.",
                data = mapOf("name" to name, "steps" to steps.size)
            )
        }.getOrElse {
            ToolResult.Failure(declaration.name, callId, "Could not save skill: ${it.message}")
        }
    }

    /** Parse the model's `steps` payload, tolerating missing/odd-typed `args`. */
    private fun parseSteps(raw: Any?): List<SkillStep> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<SkillStep>(list.size)
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val tool = (map["tool"] as? String)?.trim()
            if (tool.isNullOrEmpty()) continue
            val stepArgs = (map["args"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                ?.toMap()
                ?: emptyMap()
            out.add(SkillStep(tool, stepArgs))
        }
        return out
    }
}

/** `list_skills` — enumerate saved skills with their description and step count. No arguments. */
class ListSkillsTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "list_skills",
        description = "List all saved skills with their description and number of steps. " +
            "Takes no arguments.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        return runCatching {
            val skills = store.all().map {
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "steps" to it.steps.size
                )
            }
            ToolResult.Success(
                declaration.name, callId,
                "Found ${skills.size} saved skill(s).",
                data = mapOf("skills" to skills)
            )
        }.getOrElse {
            ToolResult.Failure(declaration.name, callId, "Could not list skills: ${it.message}")
        }
    }
}

/**
 * `recall_skill` — fetch a saved skill's steps so the MODEL can re-issue them as normal,
 * permission-gated tool calls. This tool itself executes nothing; it only returns the recipe.
 */
class RecallSkillTool(private val store: SkillStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "recall_skill",
        description = "Recall a saved skill by name and return its ordered tool calls so you can " +
            "re-issue them yourself as normal tool calls (each still goes through permission " +
            "gating). This does NOT execute anything on its own.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string", "description": "Name of the skill to recall." }
              },
              "required": ["name"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val name = (args["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "'name' is required.")
        }

        val skill = runCatching { store.get(name) }.getOrNull()
            ?: return ToolResult.Failure(declaration.name, callId, "No skill named '$name'.")

        val steps = skill.steps.map { mapOf("tool" to it.tool, "args" to it.args) }
        return ToolResult.Success(
            declaration.name, callId,
            "Recalled skill '${skill.name}' (${steps.size} step(s)). Re-issue these as tool calls.",
            data = mapOf(
                "name" to skill.name,
                "description" to skill.description,
                "steps" to steps
            )
        )
    }
}
