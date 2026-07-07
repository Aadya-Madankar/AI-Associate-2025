package com.example.di

import android.app.Application
import android.content.Context
import com.example.accessibility.Accessibility
import com.example.accessibility.AccessibilityController
import com.example.agent.DefaultPhoneControlExecutor
import com.example.agent.PhoneControlExecutor
import com.example.agent.ToolDeclaration
import com.example.agent.ToolRegistry
import com.example.agent.ToolResult
import com.example.audit.AuditLog
import com.example.audit.RoomAuditLog
import com.example.permission.AutonomyModeStore
import com.example.permission.DataStoreRuleStore
import com.example.permission.DefaultPermissionEngine
import com.example.permission.DefaultSecureContextDetector
import com.example.permission.KillSwitch
import com.example.permission.PermissionEngine
import com.example.permission.RateLimiter
import com.example.permission.RiskClassifier
import com.example.permission.RuleStore
import com.example.permission.SecureContextDetector
import com.example.security.ScreenRedactor
import com.example.skill.JsonFileSkillStore
import com.example.skill.SkillStore

/**
 * Manual dependency-injection container for the Xeno Live phone-control agent.
 *
 * This is the **single** place in the codebase allowed to reference the concrete
 * implementations created this wave by name (every other module depends only on the
 * committed contract interfaces). It wires those impls together as process-wide,
 * lazily-initialized singletons backed by the [Application] context, so the rest of
 * the app — most notably `XenoViewModel`, which is wired to this graph during
 * integration — never has to know which concrete class fulfils a contract.
 *
 * ### Lifecycle & threading
 * - [init] **must** be called exactly once, early, from `Application.onCreate()`. It
 *   captures the application context and is itself **side-effect free**: nothing is
 *   constructed until first access, so calling [init] does not touch DataStore, open
 *   the Room database, or start any service.
 * - Every accessor is `@Synchronized` and idempotent. The first read of a given
 *   singleton constructs it (double-checked via the backing field); subsequent reads
 *   return the same instance. This is safe to call from the main thread, the agent
 *   coroutine, and the WebSocket reader thread alike.
 * - Accessors throw [IllegalStateException] with a clear message if used before
 *   [init], rather than NPE-ing on a null context — keeping the locator null-safe.
 *
 * ### What lives here
 * Permission stack ([permissionEngine] + [secureContextDetector] + [ruleStore] +
 * [riskClassifier] + [autonomyModeStore]), runaway-loop guards ([rateLimiter],
 * [killSwitch]), the audit sink ([auditLog]), on-device PII redaction
 * ([screenRedactor]), the tool catalog ([toolRegistry]) and the
 * [phoneControlExecutor] that drives it, plus a thin accessor over the live
 * [AccessibilityController] published out-of-band by the accessibility service.
 *
 * ### Safety wiring — read before integrating
 * These singletons are collaborators; obtaining one does **not** by itself make the
 * agent "protected". In particular:
 * - **[screenRedactor] IS on the cloud-bound path** *for tool calls dispatched through
 *   [phoneControlExecutor]*: that executor is handed out as a redacting wrapper that
 *   runs [ScreenRedactor.redact] over every [ToolResult]'s `screen` before it can be
 *   serialized back to the model. If you read a [com.example.accessibility.ScreenState]
 *   by any **other** path (e.g. directly off [accessibilityController]) you MUST call
 *   [screenRedactor].`redact(...)` yourself before it leaves the device — the locator
 *   cannot intercept reads it does not own.
 * - **[permissionEngine] is NOT auto-applied.** Neither [phoneControlExecutor] nor
 *   [toolRegistry] consults it; the executor handed out here is **UNGATED**. The gate
 *   is owned upstream by `XenoViewModel` (Phase 3), which MUST call
 *   [permissionEngine].`decide(action, autonomyModeStore.current, screen)` for every
 *   model tool call and only forward an Allow to [phoneControlExecutor] (Confirm →
 *   raise a confirm sheet; Block → synthesize a failure). Do NOT wire raw model tool
 *   calls straight to [phoneControlExecutor] believing the gate is already present.
 *
 * @see ARCHITECTURE.md §4 (permission & autonomy), §3.4 (plan-act-observe loop).
 */
object ServiceLocator {

    /**
     * The application context, set once by [init]. Held as the application instance
     * (which is itself a [Context]) so we never leak an Activity/Service. `null` until
     * initialized; all accessors guard on this via [requireContext].
     */
    @Volatile
    private var appContext: Context? = null

    // --- Backing singletons (constructed lazily on first access) -------------------

    @Volatile private var _secureContextDetector: SecureContextDetector? = null
    @Volatile private var _riskClassifier: RiskClassifier? = null
    @Volatile private var _ruleStore: RuleStore? = null
    @Volatile private var _autonomyModeStore: AutonomyModeStore? = null
    @Volatile private var _permissionEngine: PermissionEngine? = null
    @Volatile private var _rateLimiter: RateLimiter? = null
    @Volatile private var _auditLog: AuditLog? = null
    @Volatile private var _screenRedactor: ScreenRedactor? = null
    @Volatile private var _skillStore: SkillStore? = null
    @Volatile private var _toolRegistry: ToolRegistry? = null
    @Volatile private var _phoneControlExecutor: PhoneControlExecutor? = null

    /**
     * Initialize the locator with the process [Application]. Call once from
     * `Application.onCreate()` before any accessor is used.
     *
     * Idempotent and side-effect free: it only records the context. Re-invoking with
     * the same application is a no-op; the already-built singletons are preserved.
     *
     * @param app the process [Application]; its application context is captured.
     */
    @Synchronized
    fun init(app: Application) {
        if (appContext == null) {
            appContext = app.applicationContext
        }
    }

    /** True once [init] has been called. */
    val isInitialized: Boolean
        get() = appContext != null

    /**
     * Returns the captured application context, or throws if [init] has not run. Keeps
     * the accessors null-safe instead of dereferencing a possibly-null context.
     */
    private fun requireContext(): Context = appContext
        ?: error(
            "ServiceLocator.init(app) must be called from Application.onCreate() " +
                "before any dependency is accessed."
        )

    // --- Accessibility ------------------------------------------------------------

    /**
     * The live [AccessibilityController], or `null` until the
     * `AgentAccessibilityService` is connected and publishes itself via
     * [Accessibility.controller]. Callers should gate on readiness, e.g.
     * `accessibilityController?.takeIf { it.isReady }`. This is intentionally a thin
     * pass-through (not a cached singleton) because the controller's lifecycle is owned
     * by the OS-managed service, not by this locator.
     */
    val accessibilityController: AccessibilityController?
        get() = Accessibility.controller

    // --- Permission stack ---------------------------------------------------------

    /** Pure, stateless risk table + `MAX(static, context)` classifier. */
    val riskClassifier: RiskClassifier
        @Synchronized get() = _riskClassifier
            ?: RiskClassifier().also { _riskClassifier = it }

    /**
     * Detects sensitive/secure contexts (denylisted app, FLAG_SECURE window, password /
     * OTP fields…) that must block automation regardless of mode. Pure & stateless.
     */
    val secureContextDetector: SecureContextDetector
        @Synchronized get() = _secureContextDetector
            ?: DefaultSecureContextDetector().also { _secureContextDetector = it }

    /**
     * DataStore-backed persisted allow/deny rules (per-action, per-app, per-session).
     * Built from the application context so it survives Activity recreation.
     */
    val ruleStore: RuleStore
        @Synchronized get() = _ruleStore
            ?: DataStoreRuleStore(requireContext()).also { _ruleStore = it }

    /**
     * DataStore-backed persistence for the current [com.example.permission.AutonomyMode],
     * surfaced as a mode pill in the UI and read on every permission decision.
     */
    val autonomyModeStore: AutonomyModeStore
        @Synchronized get() = _autonomyModeStore
            ?: AutonomyModeStore(requireContext()).also { _autonomyModeStore = it }

    /**
     * The four-layer permission engine (denylist → forced-ask → secure-context →
     * allow/mode). Composed from the [secureContextDetector], [riskClassifier] and
     * [ruleStore] so all four layers share one source of truth.
     */
    val permissionEngine: PermissionEngine
        @Synchronized get() = _permissionEngine
            ?: DefaultPermissionEngine(
                detector = secureContextDetector,
                classifier = riskClassifier,
                ruleStore = ruleStore
            ).also { _permissionEngine = it }

    // --- Runaway-loop guards ------------------------------------------------------

    /**
     * Token-bucket rate limiter + consecutive-irreversible cap + auto-fallback counters
     * (3 consecutive blocks / 20 total per session → revert AUTO/BYPASS to ASK). Pure
     * in-memory state, reset per hands-free session.
     */
    val rateLimiter: RateLimiter
        @Synchronized get() = _rateLimiter
            ?: RateLimiter().also { _rateLimiter = it }

    /**
     * The panic kill-switch: notification STOP action, hardware panic trigger, and
     * screen-off/app-leave auto-disarm all flip this one shared flag, which the agent
     * loop checks before every step. In-memory and process-wide.
     *
     * [com.example.permission.KillSwitch] is a class (one shared latch held here), so we
     * construct and cache a single instance that the whole app observes via
     * `killSwitch.tripped`.
     */
    private var _killSwitch: KillSwitch? = null

    val killSwitch: KillSwitch
        @Synchronized get() = _killSwitch ?: KillSwitch().also { _killSwitch = it }

    // --- Audit & redaction --------------------------------------------------------

    /**
     * Append-only, Room-backed audit sink. Stores only hashed params (never raw field
     * contents). Shares the process-wide [com.example.audit.AuditDatabase].
     */
    val auditLog: AuditLog
        @Synchronized get() = _auditLog
            ?: RoomAuditLog.create(requireContext()).also { _auditLog = it }

    /**
     * On-device PII redactor that drops password/OTP/CVV/IBAN-shaped strings and
     * sensitive-app content from a screen snapshot (the STRICT cloud-bound policy).
     *
     * Uses the shared strict-policy [ScreenRedactor.INSTANCE]: the redactor is stateless
     * and thread-safe, and the cloud-bound path must run the STRICT policy — never the
     * weaker default. Exposed through the locator so the graph stays the single wiring
     * point even though this particular dependency has no per-process construction cost.
     *
     * **Where it is load-bearing:** the locator applies this redactor automatically to
     * every [ToolResult] dispatched through [phoneControlExecutor] (which is handed out
     * as a redacting wrapper), so the screen returned by `get_screen`/any tool is
     * scrubbed before it can be serialized to the model. It is NOT magically applied to
     * any [com.example.accessibility.ScreenState] read by some other path — a caller that
     * reads the screen directly (e.g. off [accessibilityController]) MUST run it through
     * `redact(...)` itself before that snapshot leaves the device.
     */
    val screenRedactor: ScreenRedactor
        @Synchronized get() = _screenRedactor
            ?: ScreenRedactor.INSTANCE.also { _screenRedactor = it }

    // --- Agent tooling ------------------------------------------------------------

    /**
     * On-device "skill memory" for Nazim: a [JsonFileSkillStore] persisting named, replayable
     * tool-call bundles to a single JSON file in `filesDir` ([com.example.skill.JsonFileSkillStore]).
     * No database, no cloud. Built from the application context so it outlives any Activity, and
     * shared as one process-wide singleton so every skill tool (save/list/recall) reads and writes
     * the same file. The store itself is internally thread-safe.
     */
    val skillStore: SkillStore
        @Synchronized get() = _skillStore
            ?: JsonFileSkillStore(requireContext()).also { _skillStore = it }

    /**
     * The catalog of every [com.example.agent.AgentTool] the agent can invoke, keyed by
     * declared name. Built once from the application context (intent/hardware tools
     * capture it internally).
     */
    val toolRegistry: ToolRegistry
        @Synchronized get() = _toolRegistry
            ?: ToolRegistry(requireContext()).also { _toolRegistry = it }

    /**
     * Resolves and runs model tool calls against the [toolRegistry]. Injected into
     * `XenoViewModel`; survives persona switches / reconnects (it is NOT attached to the
     * rebuilt `GeminiLiveClient`).
     *
     * The instance handed out is a [RedactingExecutor] wrapping the real
     * [DefaultPhoneControlExecutor]: every [ToolResult] it returns has its `screen` run
     * through [screenRedactor].`redact(...)` before it can be serialized to the cloud,
     * so raw passwords/OTPs/card numbers from a `get_screen` (or any tool that echoes the
     * screen) can never leave the device via this path.
     *
     * **It is UNGATED for permissions.** This executor does *not* consult
     * [permissionEngine]/[secureContextDetector] — it dispatches whatever tool call it is
     * given. The permission gate is owned upstream by `XenoViewModel` (Phase 3), which
     * MUST call [permissionEngine].`decide(action, autonomyModeStore.current, screen)`
     * for each model tool call and only forward an Allow here (Confirm → confirm sheet;
     * Block → synthesize a [ToolResult.Failure]). Do not wire raw model tool calls to
     * this executor on the assumption that the secure-context/denylist gate is present.
     */
    val phoneControlExecutor: PhoneControlExecutor
        @Synchronized get() = _phoneControlExecutor
            ?: RedactingExecutor(
                delegate = DefaultPhoneControlExecutor(toolRegistry),
                redactor = screenRedactor
            ).also { _phoneControlExecutor = it }

    /**
     * Decorator that interposes [ScreenRedactor] on the cloud-bound path: it forwards
     * [execute]/[declarations] to [delegate] but rewrites the `screen` carried by the
     * returned [ToolResult] so the STRICT-policy redaction is applied to every snapshot
     * before it is serialized back to the model. Conforms to the committed
     * [PhoneControlExecutor] contract (it adds no new public surface); the redactor is
     * pure and thread-safe, so the wrapper stays stateless.
     */
    private class RedactingExecutor(
        private val delegate: PhoneControlExecutor,
        private val redactor: ScreenRedactor
    ) : PhoneControlExecutor {

        override fun declarations(): List<ToolDeclaration> = delegate.declarations()

        override suspend fun execute(
            callId: String,
            name: String,
            args: Map<String, Any?>
        ): ToolResult = when (val result = delegate.execute(callId, name, args)) {
            is ToolResult.Success ->
                if (result.screen == null) result
                else result.copy(screen = redactor.redact(result.screen))

            is ToolResult.Failure ->
                if (result.screen == null) result
                else result.copy(screen = redactor.redact(result.screen))

            // Completed carries no screen — nothing to redact.
            is ToolResult.Completed -> result
        }
    }

    /**
     * Test/teardown hook: drop the captured context and every cached singleton so a
     * fresh [init] rebuilds the graph. Not used in production; exists so instrumentation
     * tests can isolate state between cases.
     */
    @Synchronized
    fun resetForTesting() {
        appContext = null
        _secureContextDetector = null
        _riskClassifier = null
        _ruleStore = null
        _autonomyModeStore = null
        _permissionEngine = null
        _rateLimiter = null
        _auditLog = null
        _screenRedactor = null
        _skillStore = null
        _toolRegistry = null
        _phoneControlExecutor = null
    }
}
