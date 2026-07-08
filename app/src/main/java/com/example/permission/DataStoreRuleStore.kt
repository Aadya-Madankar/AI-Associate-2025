package com.example.permission

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Process-wide Preferences DataStore for persisted (ALWAYS) permission rules. */
private val Context.permissionRulesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "xeno_permission_rules"
)

/**
 * [RuleStore] backed by androidx Preferences DataStore.
 *
 * Two tiers of grants, matching ARCHITECTURE.md §4.4:
 *  - **Persisted** ([RuleScope.ALWAYS_THIS_ACTION_AND_APP]) — survives process death,
 *    stored as a `Set<String>` of canonical rule keys in DataStore.
 *  - **Session** ([RuleScope.SESSION]) — held in memory only, cleared by [revokeSession]
 *    at the end of each hands-free session, on screen-off, or on app-background. New
 *    "always" grants default to session-only at the call site; persisting is an
 *    explicit choice.
 *
 * [RuleScope.ONCE] grants nothing — the engine simply proceeds for that single action
 * and does not consult the store again.
 *
 * Rule keys are canonicalized as `ACTIONTYPE@package`. Lookups are O(1) against
 * in-memory sets; the persisted set is mirrored into memory on first read and kept warm.
 *
 * @param context     application context (uses [Context.applicationContext] internally).
 * @param ioScope     scope for fire-and-forget writes; defaults to an IO supervisor scope.
 */
class DataStoreRuleStore(
    context: Context,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RuleStore {

    private val appContext: Context = context.applicationContext
    private val dataStore get() = appContext.permissionRulesDataStore

    /** Persisted ALWAYS rules, mirrored from DataStore for synchronous reads. */
    private val persistedRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** In-memory session rules (action+app). Cleared on [revokeSession]. */
    private val sessionRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // Warm the in-memory mirror from disk and keep it in sync.
        ioScope.launch {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { prefs -> prefs[PERSISTED_KEY] ?: emptySet() }
                .collect { set ->
                    persistedRules.clear()
                    persistedRules.addAll(set)
                }
        }
    }

    override fun isDenied(action: AgentAction): Boolean {
        // Hard denylist (apps + action types) is global and always wins; per-rule
        // allows can never override it. Engine also checks this, but we honor it here
        // so a stray allow rule can't resurrect a denied action.
        if (DenyLists.isDenylistedActionType(action.type)) return true
        if (DenyLists.isDenylistedPackage(action.targetApp)) return true
        return false
    }

    override fun isAllowed(action: AgentAction, mode: AutonomyMode): Boolean {
        if (isDenied(action)) return false

        // A null/blank target app must never satisfy a wildcard rule: otherwise a single
        // app-less grant would silently auto-allow EVERY other app-less action for the
        // session. Such over-broad rules are also refused at write time (see [grant]),
        // but we defend here too so a null-app action can only ever match a fully-exact,
        // non-wildcard key.
        if (action.targetApp.isNullOrBlank()) {
            val exactKey = ruleKey(action.type, action.targetApp)
            if (isWildcardKey(exactKey)) return false
            return persistedRules.contains(exactKey) || sessionRules.contains(exactKey)
        }

        val exactKey = ruleKey(action.type, action.targetApp)
        return persistedRules.contains(exactKey) || sessionRules.contains(exactKey)
    }

    override fun grant(action: AgentAction, scope: RuleScope) {
        // INVARIANT: the store can never hold a *standing* grant for an irreversible /
        // forced-ask / BLOCKED-tier action. Even though the current engine evaluates
        // forced-ask before isAllowed, the store is the durable source of truth and is
        // contractually independent of the engine: a stored "always allow SEND_EMAIL@app"
        // is a latent footgun the moment any caller consults isAllowed first. Such grants
        // are downgraded to ONCE semantics (no rule written) here.
        if (scope != RuleScope.ONCE && action.type in NEVER_STANDING_TYPES) {
            return
        }
        when (scope) {
            RuleScope.ONCE -> {
                // No standing rule; the engine proceeds once and re-asks next time.
            }
            RuleScope.SESSION -> {
                // Refuse to persist a wildcard-app key for an unknown app: that key would
                // match every other app-less action, exploding the grant's scope. Fall
                // back to ONCE semantics (write nothing) when the app is unknown.
                if (action.targetApp.isNullOrBlank()) return
                sessionRules.add(ruleKey(action.type, action.targetApp))
            }
            RuleScope.ALWAYS_THIS_ACTION_AND_APP -> {
                // A persisted exact rule for an unknown app would store "TYPE@*", which any
                // future app-less action of that type would match. Refuse it (treat as ONCE).
                if (action.targetApp.isNullOrBlank()) return
                val key = ruleKey(action.type, action.targetApp)
                persistedRules.add(key)
                ioScope.launch {
                    dataStore.edit { prefs ->
                        val current = prefs[PERSISTED_KEY]?.toMutableSet() ?: mutableSetOf()
                        current.add(key)
                        prefs[PERSISTED_KEY] = current
                    }
                }
            }
        }
    }

    override fun revokeSession() {
        sessionRules.clear()
    }

    private companion object {
        val PERSISTED_KEY = stringSetPreferencesKey("always_rules")

        /** App-component sentinel used when the target app is unknown; never a real key. */
        const val WILDCARD_APP = "*"

        /**
         * Action types for which a *standing* grant (SESSION / ALWAYS_THIS_ACTION_AND_APP)
         * is never written: irreversible/forced-ask and
         * BLOCKED-tier types. Mirrors the engine's forced-ask list plus the BLOCKED tier
         * (DELETE_DATA, CHANGE_SECURITY_SETTING) so the store itself can never hold a
         * grant that, if honored, would auto-send/auto-delete/auto-purchase.
         */
        val NEVER_STANDING_TYPES: Set<ActionType> = setOf(
            ActionType.SEND_MESSAGE,
            ActionType.SEND_EMAIL,
            ActionType.PLACE_CALL,
            ActionType.MAKE_PURCHASE,
            ActionType.DELETE_DATA,
            ActionType.INSTALL_APP,
            ActionType.UNINSTALL_APP,
            ActionType.CHANGE_SECURITY_SETTING
        )

        /** Canonical key for one action type aimed at one app (or no-app). */
        fun ruleKey(type: ActionType, app: String?): String =
            "${type.name}@${app?.takeIf { it.isNotBlank() } ?: WILDCARD_APP}"

        /** True if [key]'s app component is the unknown-app wildcard sentinel. */
        fun isWildcardKey(key: String): Boolean =
            key.substringAfterLast('@') == WILDCARD_APP
    }
}
