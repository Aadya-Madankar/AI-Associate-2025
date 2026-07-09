package com.example.memory.tools

import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.memory.MemoryStore

/**
 * `recall` — semantic search over XENO's on-device memory (facts + past episodes). Returns the
 * most relevant items so the model can ground its answer in what it knows/did before. SAFE:
 * read-only; recalling a fact also strengthens it (retrieval practice).
 */
class RecallMemoryTool(private val store: MemoryStore) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "recall",
        description = "Search your on-device memory for what you know about the user and what you " +
            "did in past sessions. Use it when a question depends on earlier context (\"what's my " +
            "wife's name?\", \"what did we set up last time?\"). Returns the most relevant memories.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "What to look for, in natural language." },
                "limit": { "type": "integer", "description": "Max memories to return (default 6)." }
              },
              "required": ["query"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val query = (args["query"] as? String)?.trim()
        if (query.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'query'.")
        }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 6).coerceIn(1, 20)
        val hits = store.recall(query, limit)
        val message = if (hits.isEmpty()) "No relevant memories found." else "Found ${hits.size} memories."
        return ToolResult.Success(
            declaration.name, callId, message,
            data = mapOf("hits" to hits.map { mapOf("kind" to it.kind, "text" to it.text) })
        )
    }
}
