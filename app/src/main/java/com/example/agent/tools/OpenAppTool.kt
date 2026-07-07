package com.example.agent.tools

import android.content.Context
import android.content.Intent
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult
import com.example.permission.DenyLists

/**
 * `open_app` — launches another installed app by package name or (best-effort) by
 * a human app name. Uses [android.content.pm.PackageManager.getLaunchIntentForPackage]
 * which already targets the app's launcher activity, so no `resolveActivity` guard is
 * needed (a null intent means "not installed OR not visible to this app").
 *
 * Note: on targetSdk >= 30 a null launch intent can also be caused by package-visibility
 * filtering. The app's manifest declares the LAUNCHER `<intent>` query so launchable apps
 * are visible (see AndroidManifest.xml `<queries>`).
 *
 * SAFE: opening a non-denylisted app is reversible. However, the upstream permission gate
 * only sees the raw model args, so when the model supplies a human `appName` (e.g. "Chase",
 * "Authy") it can name a banking/wallet/authenticator app whose display name matches no
 * denylist entry. This tool therefore re-applies [DenyLists.isDenylistedPackage] to the
 * RESOLVED package and refuses denylisted apps, and never echoes a newly-discovered package
 * back to the model — so the installed-app inventory is not leaked across the trust boundary.
 * See ARCHITECTURE.md §4.3 / §5 / §8.
 *
 * @param context application/activity context used to resolve and start the launch intent.
 */
class OpenAppTool(private val context: Context) : AgentTool {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_app",
        description = "Open/launch an installed app on the device. Provide the app's package " +
            "name (e.g. com.android.settings) when known, otherwise its display name.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "package": { "type": "string", "description": "Android package id, e.g. com.spotify.music" },
                "appName": { "type": "string", "description": "Human app name, e.g. Spotify (used if package is absent)" }
              },
              "required": []
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val pkg = (args["package"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }
        val appName = (args["appName"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }

        // True when the package was discovered from a human appName rather than supplied
        // directly by the caller. In that case we must NOT echo the resolved package back
        // to the model (it would leak the installed-app inventory across the trust boundary).
        val resolvedFromName = pkg == null

        val resolvedPkg = pkg ?: appName?.let { resolvePackageByName(it) }
        if (resolvedPkg == null) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = "Provide a 'package' or a resolvable 'appName'."
            )
        }

        // Re-apply the denylist to the RESOLVED package. The upstream permission gate only
        // sees the raw model args, so an appName like "Chase"/"Authy" passes it un-resolved;
        // here we honor the §4.3/§8 hard boundary on the real package. resolvePackageByName
        // already excludes denylisted candidates, but the direct 'package' path is checked
        // here too. Do NOT return the denylisted package id to the model.
        if (DenyLists.isDenylistedPackage(resolvedPkg)) {
            return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = if (resolvedFromName) {
                    "Cannot open '$appName': banking/wallet/authenticator apps are not automated."
                } else {
                    "Cannot open '$resolvedPkg': banking/wallet/authenticator apps are not automated."
                }
            )
        }

        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(resolvedPkg) }
            .getOrNull()
            ?: return ToolResult.Failure(
                toolName = declaration.name,
                callId = callId,
                error = if (resolvedFromName) {
                    "App '$appName' is not installed or has no launcher activity."
                } else {
                    "App '$resolvedPkg' is not installed or has no launcher activity."
                }
            )

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            if (resolvedFromName) {
                // Name path: report the user-supplied label, never the discovered package,
                // so the model cannot enumerate installed apps via name guesses.
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Opened $appName."
                )
            } else {
                ToolResult.Success(
                    toolName = declaration.name,
                    callId = callId,
                    message = "Opened $resolvedPkg.",
                    data = mapOf("package" to resolvedPkg)
                )
            }
        }.getOrElse {
            val label = if (resolvedFromName) appName else resolvedPkg
            ToolResult.Failure(declaration.name, callId, "Could not open '$label': ${it.message}")
        }
    }

    /**
     * Best-effort: match a launchable app whose loaded label equals (case-insensitive) [name].
     * Denylisted packages (banking/wallet/authenticator) are excluded from the candidate set so
     * that name-based probing can neither resolve nor confirm a sensitive app is installed.
     */
    private fun resolvePackageByName(name: String): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(launcher, 0)
                .asSequence()
                .filter { info -> info.loadLabel(pm).toString().equals(name, ignoreCase = true) }
                .map { it.activityInfo?.packageName }
                .firstOrNull { !it.isNullOrEmpty() && !DenyLists.isDenylistedPackage(it) }
        }.getOrNull()
    }
}
