package com.example.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The global panic stop for the phone-control agent (ARCHITECTURE.md §4.4). A single
 * shared latch (one instance, owned by the DI graph) that, once [trigger]ed, halts all
 * automation: in-flight gestures are cancelled, the accessibility service can
 * `disableSelf()`, and the autonomy mode reverts to [AutonomyMode.ASK].
 *
 * Reachable three ways per §4.4: the persistent STOP in the foreground-service
 * notification, a hardware trigger (volume-down long-press / power triple-press routed
 * through the a11y service), and the screen-off / app-leave auto-disarm. All of them
 * funnel into [trigger].
 *
 * Created and held as the single shared instance by `ServiceLocator` (constructed via
 * `KillSwitch()`), so every layer — the executor, the loop controller, the gesture
 * dispatcher, the UI — observes the same [tripped] flow through that one reference. The
 * flow is a cold-start `false` (armed but not tripped).
 *
 * Consumers must check [isTripped] *before* dispatching any gesture and should collect
 * [tripped] to abort anything already running.
 *
 * **Autonomy revert.** Per the [AutonomyModeStore] contract, the safety baseline must
 * drop to [AutonomyMode.ASK] when this latch fires. Supply an [onTrigger] callback (the
 * integration layer wires it to [AutonomyModeStore.resetToSafeDefault]); it is invoked
 * synchronously inside the winning [trigger] so re-arming after a panic STOP can never
 * silently resume in AUTO/BYPASS. A `KillSwitch()` with no callback (e.g. in tests) just
 * skips the revert.
 */
class KillSwitch(
    /**
     * Invoked once, synchronously, in the [trigger] call that actually flips the latch
     * (never on idempotent re-triggers). Wired by the integration layer to revert the
     * autonomy mode to [AutonomyMode.ASK]; defaults to a no-op so the no-arg
     * `KillSwitch()` the DI graph constructs stays valid and tests need no callback.
     */
    private val onTrigger: ((KillReason) -> Unit)? = null
) {

    private val _tripped = MutableStateFlow(false)

    /**
     * True the moment the kill switch fires; stays true until [reset]. Collect this to
     * cancel in-flight actions and to gate any new dispatch. Backed by a [StateFlow] so
     * late subscribers immediately see the current state.
     */
    val tripped: StateFlow<Boolean> = _tripped.asStateFlow()

    /** Optional one-shot listeners invoked synchronously on [trigger] (e.g. cancel gestures). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(KillReason) -> Unit>()

    private val _lastReason = MutableStateFlow(KillReason.NONE)
    /** Why the switch was last tripped, for the audit log and notification copy. */
    val lastReason: StateFlow<KillReason> = _lastReason.asStateFlow()

    /** Synchronous, allocation-free read of the latch for the gesture hot path. */
    val isTripped: Boolean get() = _tripped.value

    /**
     * Fires the kill switch. Idempotent: triggering an already-tripped switch is a
     * no-op (listeners are not re-invoked). Records [reason], reverts the autonomy mode
     * to [AutonomyMode.ASK] via [onTrigger], and notifies listeners synchronously so a
     * caller on the dispatch thread can abort immediately.
     *
     * @param reason what caused the stop (defaults to [KillReason.USER_STOP]).
     */
    fun trigger(reason: KillReason = KillReason.USER_STOP) {
        // Compare-and-set so concurrent triggers only fire listeners once.
        if (_tripped.compareAndSet(expect = false, update = true)) {
            _lastReason.value = reason
            // Drop the autonomy baseline to ASK so re-arming after a panic STOP can
            // never silently resume in AUTO/BYPASS (AutonomyModeStore owns the persist).
            runCatching { onTrigger?.invoke(reason) }
            for (l in listeners) {
                runCatching { l(reason) }
            }
        }
    }

    /**
     * Re-arms the switch after a stop has been fully handled (gestures cancelled, mode
     * reverted, session reset). Safe to call when not tripped. Does NOT itself restart
     * the agent — that is a deliberate user action.
     */
    fun reset() {
        _tripped.value = false
        _lastReason.value = KillReason.NONE
    }

    /**
     * Registers a listener invoked synchronously on [trigger]. Returns a handle that
     * removes the listener when invoked. Use for cancelling in-flight gesture jobs.
     */
    fun addListener(listener: (KillReason) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Removes every registered listener. Mainly for test isolation. */
    fun clearListeners() = listeners.clear()
}

/** What caused the [KillSwitch] to trip — surfaced to the audit log / notification. */
enum class KillReason {
    /** Not tripped, or reset. */
    NONE,

    /** User pressed STOP in the notification or on the overlay. */
    USER_STOP,

    /** Hardware panic gesture (volume-down long-press / power triple-press). */
    HARDWARE_TRIGGER,

    /** Screen turned off or the foreground app changed — auto-disarm. */
    SCREEN_OFF_OR_APP_LEFT,

    /** Auto-fallback counters tripped (too many blocks). */
    SAFETY_FALLBACK,

    /** An unrecoverable error in the agent loop forced a stop. */
    ERROR
}
