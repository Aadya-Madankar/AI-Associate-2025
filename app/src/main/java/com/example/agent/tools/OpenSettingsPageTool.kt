package com.example.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.agent.AgentTool
import com.example.agent.ToolDeclaration
import com.example.agent.ToolResult

/**
 * `open_settings` — deep-links to a specific Android Settings page from a fixed
 * allowlist of `Settings.ACTION_*` actions. Only allowlisted pages are reachable so
 * the model can't be steered into arbitrary `android.settings.*` actions; this is the
 * sanctioned handoff used for Wi-Fi/Bluetooth/etc. (SAFE — the user changes settings
 * themselves). The allowlist is restricted to benign connectivity/display/sound/app
 * pages; the security-configuration surface (Developer Options / Security / Privacy) is
 * intentionally excluded so a SAFE-tier handoff cannot reach pages that the BLOCKED +
 * denylisted `CHANGE_SECURITY_SETTING` rule keeps unreachable. See ARCHITECTURE.md §5.
 *
 * @param context context used to resolve and start the settings activity.
 */
class OpenSettingsPageTool(private val context: Context) : AgentTool {

    /**
     * Allowlist: page key → Settings action string. Anything outside this map is refused.
     *
     * Only benign connectivity/display/sound/app handoffs are exposed so this tool is a
     * truly SAFE handoff (consistent with [com.example.permission.ActionType.OPEN_SETTINGS_PAGE]
     * → SAFE). The security-configuration surface — Developer Options
     * (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`), Security (`ACTION_SECURITY_SETTINGS`) and
     * Privacy (`ACTION_PRIVACY_SETTINGS`) — is deliberately NOT reachable here: deep-linking
     * the agent onto those pages would hand it the exact surface that the BLOCKED + denylisted
     * `CHANGE_SECURITY_SETTING` rule exists to keep unreachable (USB debugging, Play Protect,
     * lock-screen/biometric toggles drivable via the SAFE TAP/SCROLL/INPUT_TEXT tools). Any
     * security/privacy handoff must go through an action type whose tier is at least GUARDED,
     * never advertised under `open_settings`.
     *
     * `notifications` maps to the public per-app notification page
     * (`ACTION_APP_NOTIFICATION_SETTINGS`), which requires the app's package in
     * [Settings.EXTRA_APP_PACKAGE]; see [execute]. (`Settings.ACTION_NOTIFICATION_SETTINGS`
     * is a @hide/@SystemApi constant absent from the public SDK and does not compile.)
     */
    private val pages: Map<String, String> = mapOf(
        "settings" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "wireless" to Settings.ACTION_WIRELESS_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS
    )

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "open_settings",
        description = "Open a specific Android Settings page. The 'page' must be one of the supported keys.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "page": {
                  "type": "string",
                  "description": "Which settings page to open.",
                  "enum": ["settings","wifi","bluetooth","data","wireless","airplane","display","sound","notifications","apps","battery","storage","location","date","language","accessibility","nfc"]
                }
              },
              "required": ["page"]
            }
        """.trimIndent()
    )

    override suspend fun execute(callId: String, args: Map<String, Any?>): ToolResult {
        val key = (args["page"] as? String)?.trim()?.lowercase()
        if (key.isNullOrEmpty()) {
            return ToolResult.Failure(declaration.name, callId, "Missing 'page'.")
        }
        val action = pages[key]
            ?: return ToolResult.Failure(
                declaration.name, callId,
                "Unsupported settings page '$key'. Supported: ${pages.keys.joinToString(", ")}."
            )

        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ACTION_APP_NOTIFICATION_SETTINGS only resolves to a useful page when the target
        // app's package is supplied; without it the page is empty/unresolved.
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

        return try {
            context.startActivity(intent)
            ToolResult.Success(declaration.name, callId, "Opened the $key settings page.", data = mapOf("page" to key))
        } catch (e: ActivityNotFoundException) {
            ToolResult.Failure(declaration.name, callId, "This device has no '$key' settings page.")
        }
    }
}
