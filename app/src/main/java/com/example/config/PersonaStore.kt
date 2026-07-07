package com.example.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.personaDataStore: DataStore<Preferences> by preferencesDataStore(name = "xeno_persona")

/**
 * On-device store for **XENO's self-authored prompt note** — the directive XENO can write to
 * itself with the `update_self_prompt` tool ("remember to always greet in Hindi", "be more
 * concise", "the user prefers Marathi for jokes"…). Stored only on this phone (DataStore, no
 * cloud, no DB), it is appended to XENO's system instruction on the next session, so XENO can
 * genuinely shape how it behaves over time.
 *
 * The Live API fixes the system instruction at connect time, so a note written mid-session takes
 * full effect when the session next starts.
 */
class PersonaStore(private val context: Context) {

    private val directiveKey = stringPreferencesKey("self_directive")

    /** XENO's current self-directive (may be blank), as a reactive flow. */
    val directive: Flow<String> = context.personaDataStore.data.map { it[directiveKey].orEmpty() }

    /** Read the current self-directive once (for building the system instruction). */
    suspend fun current(): String = context.personaDataStore.data.first()[directiveKey].orEmpty()

    /** Append a new line to the self-directive (the default — notes accumulate). */
    suspend fun append(note: String) {
        val n = note.trim()
        if (n.isEmpty()) return
        context.personaDataStore.edit { prefs ->
            val existing = prefs[directiveKey].orEmpty().trim()
            prefs[directiveKey] = if (existing.isEmpty()) "• $n" else "$existing\n• $n"
        }
    }

    /** Replace the whole self-directive (used when XENO wants to rewrite its note wholesale). */
    suspend fun replace(note: String) {
        context.personaDataStore.edit { prefs -> prefs[directiveKey] = note.trim() }
    }

    /** Clear the self-directive. */
    suspend fun clear() {
        context.personaDataStore.edit { prefs -> prefs.remove(directiveKey) }
    }
}
