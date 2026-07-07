package com.example.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for checking whether the agent's [AgentAccessibilityService] is currently
 * enabled, and for sending the user to the system Accessibility settings to grant it.
 *
 * Enablement cannot be requested programmatically — the user must toggle it in
 * Settings — so the one-time onboarding flow checks [isServiceEnabled] and, if false,
 * launches [settingsIntent]. See ARCHITECTURE.md §3.2 / §6 (MainActivity wiring).
 */
object AccessibilityAvailability {

    /**
     * True if [serviceClass] (default [AgentAccessibilityService]) appears in the
     * colon-separated `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` list. Falls
     * back to the live [Accessibility.controller] readiness when the setting can't be
     * read (e.g. restricted profiles).
     */
    fun isServiceEnabled(
        context: Context,
        serviceClass: Class<*> = AgentAccessibilityService::class.java
    ): Boolean {
        val expected = ComponentName(context.packageName, serviceClass.name)
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (t: Throwable) {
            null
        }
        if (!enabled.isNullOrEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(SERVICE_SEPARATOR)
            splitter.setString(enabled)
            for (component in splitter) {
                val parsed = ComponentName.unflattenFromString(component) ?: continue
                if (parsed == expected) return true
            }
        }
        // Setting unavailable / not yet propagated: trust a live, ready controller.
        return Accessibility.controller?.isReady == true
    }

    /**
     * Intent that opens the system Accessibility settings screen where the user can
     * enable the service. Add [Intent.FLAG_ACTIVITY_NEW_TASK] when launching from a
     * non-Activity context.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private const val SERVICE_SEPARATOR = ':'
}
