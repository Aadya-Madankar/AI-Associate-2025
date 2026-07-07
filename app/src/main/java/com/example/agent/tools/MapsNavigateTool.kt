package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `navigate` — starts turn-by-turn navigation to a destination using the
 * `google.navigation:q=` deep link, falling back to a generic `geo:0,0?q=` query
 * when no navigation-capable handler is present. SAFE: shows a route, drives nothing
 * irreversible. See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve a maps handler and start the activity.
 */
class MapsNavigateTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "navigate",
        description = "Start navigation / show directions to a destination (address or place name).",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "destination": { "type": "string", "description": "Address or place to navigate to." },
                "mode": {
                  "type": "string",
                  "description": "Travel mode: driving, walking, bicycling or transit.",
                  "enum": ["driving", "walking", "bicycling", "transit"]
                }
              },
              "required": ["destination"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val destination = (args["destination"] as? String)?.trim()
        if (destination.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'destination'.")
        }
        val mode = (args["mode"] as? String)?.trim()?.lowercase()
        val travelMode = when (mode) {
            "walking" -> "w"
            "bicycling" -> "b"
            "transit" -> "r"
            "driving" -> "d"
            else -> null
        }

        val encoded = Uri.encode(destination)
        val navUriString = buildString {
            append("google.navigation:q=").append(encoded)
            if (travelMode != null) append("&mode=").append(travelMode)
        }
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse(navUriString))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // NOTE: `resolveActivity` is NOT used as the launch gate here. On API 30+ with
        // package-visibility filtering, the app cannot "see" handlers for the non-web
        // `google.navigation:`/`geo:` schemes unless they are declared in <queries>, so
        // resolveActivity returns null even when a maps app IS installed and can handle the
        // intent. Treating that null as definitive made the tool silently non-functional on
        // the bulk of the install base. The robust remedy is the manifest <queries> entries
        // for the `geo`/`google.navigation` VIEW intents; this in-file mitigation makes the
        // tool resilient regardless: we attempt the launch directly and only fall back when
        // the system itself reports no handler via ActivityNotFoundException.
        runCatching {
            context.startActivity(navIntent)
        }.onSuccess {
            return ToolResult.Success(
                declaration.name, callId,
                "Navigating to $destination.",
                data = mapOf("destination" to destination)
            )
        }.onFailure {
            // A non-resolution failure (no handler) → try the geo fallback below. Any other
            // failure is genuinely unexpected for this launch and is reported as such.
            if (it !is ActivityNotFoundException) {
                return ToolResult.Failure(declaration.name, callId, "Could not start navigation: ${it.message}")
            }
        }

        // Fallback: generic geo query (any maps app). Same visibility caveat applies, so we
        // launch directly and let ActivityNotFoundException be the only "no maps app" signal.
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(geoIntent)
            ToolResult.Success(
                declaration.name, callId,
                "Showing $destination on the map.",
                data = mapOf("destination" to destination)
            )
        }.getOrElse {
            if (it is ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No maps app available to navigate.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not open map: ${it.message}")
            }
        }
    }
}
