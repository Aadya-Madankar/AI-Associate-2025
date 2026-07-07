package com.example.accessibility

import android.accessibilityservice.AccessibilityService

/**
 * Thin, testable wrappers over [AccessibilityService.performGlobalAction] for the
 * navigation primitives the agent exposes: Back, Home, Recents and the notification
 * shade. Each returns the platform's success flag. See ARCHITECTURE.md §3.2.
 */
class GlobalActions(private val service: AccessibilityService) {

    /** Press the system Back button. */
    fun back(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_BACK)

    /** Go to the system Home screen. */
    fun home(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_HOME)

    /** Open the Recents / overview screen. */
    fun recents(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** Expand the notification shade. */
    fun openNotifications(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun perform(action: Int): Boolean =
        try {
            service.performGlobalAction(action)
        } catch (t: Throwable) {
            false
        }
}
