package com.example.agent.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `web_search` — runs a web search for a free-text query via [Intent.ACTION_WEB_SEARCH].
 * The system search provider (usually the default browser/Google app) renders results.
 * SAFE: searching is reversible. See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve a search handler and start the activity.
 */
class WebSearchTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "web_search",
        description = "Perform a web search for the given query and show the results.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "What to search for." }
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

        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(declaration.name, callId, "Searching for \"$query\".", data = mapOf("query" to query))
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No app available to perform a web search.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not start search: ${it.message}")
            }
        }
    }
}
