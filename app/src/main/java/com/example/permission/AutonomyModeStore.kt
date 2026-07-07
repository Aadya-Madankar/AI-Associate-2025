package com.example.permission

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Process-wide Preferences DataStore for the persisted autonomy mode. */
private val Context.autonomyModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "xeno_autonomy_mode"
)

/**
 * Persists the active [AutonomyMode] and exposes it as a cold [Flow] (ARCHITECTURE.md
 * §4.1). The default is [AutonomyMode.ASK] — the safe "confirm every action" mode — so
 * a fresh install, a corrupted store, or an unknown persisted value can never silently
 * leave the user in a more permissive mode.
 *
 * The store does NOT auto-revert on the kill-switch / fallback thresholds; that policy
 * lives in [RateLimiter] / [KillSwitch], which call [setMode]`(AutonomyMode.ASK)` when
 * a threshold trips. This class is purely the persistence + observation surface.
 *
 * @param context application context (uses [Context.applicationContext] internally).
 */
class AutonomyModeStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dataStore get() = appContext.autonomyModeDataStore

    /**
     * The current mode as a cold flow, defaulting to [AutonomyMode.ASK]. Emits on every
     * persisted change. IO errors fall back to an empty preferences snapshot → ASK.
     */
    val mode: Flow<AutonomyMode> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[MODE_KEY].toAutonomyModeOrDefault() }

    /** Reads the current mode once (suspending), falling back to [AutonomyMode.ASK]. */
    suspend fun current(): AutonomyMode = mode.first()

    /** Persists [mode]. Suspends until the write completes. */
    suspend fun setMode(mode: AutonomyMode) {
        dataStore.edit { prefs -> prefs[MODE_KEY] = mode.name }
    }

    /**
     * Convenience for the kill-switch / fallback path: force the safe baseline. Equivalent
     * to `setMode(AutonomyMode.ASK)` but named for intent at call sites.
     */
    suspend fun resetToSafeDefault() = setMode(AutonomyMode.ASK)

    private fun String?.toAutonomyModeOrDefault(): AutonomyMode =
        this?.let { name -> AutonomyMode.entries.firstOrNull { it.name == name } } ?: DEFAULT_MODE

    private companion object {
        val MODE_KEY = stringPreferencesKey("autonomy_mode")
        // Default to AUTO: a phone-control companion should just DO the harmless things — open an
        // app, scroll, tap a normal button, toggle the torch — without nagging for a confirm on
        // every step. AUTO still FORCES a confirm on irreversible/outbound actions (send/call/
        // pay/delete/install) and on commit-taps (Send/Pay/Delete buttons), and still HARD-BLOCKS
        // secure contexts (banking/wallet, password/OTP fields, FLAG_SECURE, keyguard). The user
        // can dial down to ASK or up to BYPASS from the mode pill; the kill-switch/auto-fallback
        // still reverts to ASK. (Was ASK, which made even "open an app" require a manual confirm.)
        val DEFAULT_MODE = AutonomyMode.AUTO
    }
}
