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

private val Context.apiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "xeno_api_keys")

/**
 * On-device store for the user's Gemini API keys (no cloud, no DB — DataStore on the phone).
 *
 * Supports MULTIPLE keys, kept in order. The live session uses them with failover: if a
 * connection errors (e.g. Live-API quota / auth), the app rotates to the next key automatically.
 * Keys entered in-app take precedence over the build-time BuildConfig key.
 */
class ApiKeyStore(private val context: Context) {

    private val keysPref = stringPreferencesKey("keys") // newline-delimited, order preserved

    /** The user's keys, in priority order, as a reactive flow. */
    val keys: Flow<List<String>> = context.apiKeyDataStore.data.map { decode(it[keysPref]) }

    /** Read the current keys once (for starting a session). */
    suspend fun current(): List<String> = decode(context.apiKeyDataStore.data.first()[keysPref])

    /** Add a key to the end of the list (ignored if blank or already present). */
    suspend fun add(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        context.apiKeyDataStore.edit { prefs ->
            val list = decode(prefs[keysPref]).toMutableList()
            if (!list.contains(k)) list.add(k)
            prefs[keysPref] = list.joinToString("\n")
        }
    }

    /** Remove a key. */
    suspend fun remove(key: String) {
        context.apiKeyDataStore.edit { prefs ->
            val list = decode(prefs[keysPref]).toMutableList()
            list.remove(key)
            prefs[keysPref] = list.joinToString("\n")
        }
    }

    private fun decode(raw: String?): List<String> =
        raw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
