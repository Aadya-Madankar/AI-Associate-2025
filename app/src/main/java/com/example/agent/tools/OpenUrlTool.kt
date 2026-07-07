package com.example.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `open_url` — opens a web URL (http/https only) via [Intent.ACTION_VIEW], letting the
 * system pick the default browser / handler. SAFE: viewing a web page is reversible. An
 * `https://` scheme is added when the model passes a bare host. Non-web schemes (tel:,
 * sms:, mailto:, geo:, market:, app deep links, …) are rejected — those side-effecting or
 * app-routing actions must go through their own, separately-gated tools. See ARCHITECTURE.md §5.
 *
 * @param context context used to start the activity.
 */
class OpenUrlTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_url",
        description = "Open a web URL (http/https only) in the browser or its default handler app.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "url": { "type": "string", "description": "Absolute web URL (http/https only), e.g. https://example.com" }
              },
              "required": ["url"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val raw = (args["url"] as? String)?.trim()
        if (raw.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'url'.")
        }

        val normalized = if (Uri.parse(raw).scheme.isNullOrEmpty()) "https://$raw" else raw
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
            ?: return ToolResult.Failure(declaration.name, callId, "Malformed url '$raw'.")

        // Restrict to web schemes only. open_url is not a universal launcher: deep links
        // into other apps (dialer, maps, store, finance/wallet/2FA, …) must be driven by
        // their dedicated, separately-gated tools — never forwarded as a raw ACTION_VIEW here.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ToolResult.Failure(
                declaration.name,
                callId,
                "open_url only opens http(s) web pages; '$scheme' is not allowed."
            )
        }

        // Make the navigation target inspectable and reject opaque / embedded-credential
        // URLs (e.g. http://user:pass@host) before launching, so the audit log and any
        // host-based gating see exactly what was opened.
        if (uri.isOpaque || !uri.userInfo.isNullOrEmpty()) {
            return ToolResult.Failure(
                declaration.name,
                callId,
                "Refusing to open opaque or credential-embedding url '$normalized'."
            )
        }
        val host = uri.host

        // Force a web ACTION_VIEW for the validated URI. startActivity with an implicit
        // intent is exempt from Android 11+ package visibility, so we wrap it in a
        // try/catch instead of an unreliable resolveActivity precheck (which returns null
        // under package filtering and would break the happy path on every Android 11+ device).
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            ToolResult.Success(
                declaration.name,
                callId,
                "Opened $normalized.",
                data = mapOf("url" to normalized, "host" to host)
            )
        }.getOrElse { e ->
            if (e is android.content.ActivityNotFoundException) {
                ToolResult.Failure(declaration.name, callId, "No app can open '$normalized'.")
            } else {
                ToolResult.Failure(declaration.name, callId, "Could not open url: ${e.message}")
            }
        }
    }
}
