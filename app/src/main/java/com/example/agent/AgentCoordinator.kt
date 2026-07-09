package com.example.agent

import android.app.Application
import android.util.Log
import com.example.accessibility.Accessibility
import com.example.accessibility.ScreenState
import com.example.audit.AuditLog
import com.example.audit.AuditOutcome
import com.example.audit.AuditRecord
import com.example.di.ServiceLocator
import com.example.skill.Skill
import com.example.live.LiveFunctionCall
import com.example.live.LiveFunctionResponse
import com.example.permission.AgentAction
import com.example.permission.AutonomyMode
import com.example.permission.AutonomyModeStore
import com.example.permission.KillReason
import com.example.permission.KillSwitch
import com.example.permission.PermissionDecision
import com.example.permission.PermissionEngine
import com.example.permission.RateLimiter
import com.example.permission.RuleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * The single bridge between the Gemini Live tool-calling loop and the on-device
 * permission / execution / audit stack.
 *
 * `XenoViewModel` constructs **one** [AgentCoordinator] for the process and delegates every
 * inbound model tool call to [handleToolCalls]; this class owns the permission gate, the
 * confirm-sheet hand-off, runaway-loop guards, the kill switch, and the audit trail, and
 * surfaces all UI-visible agent state as [StateFlow]s. The ViewModel never touches the
 * permission stack directly — that wiring lives entirely here (ARCHITECTURE.md §3.4, §4).
 *
 * ### What it ties together (all pulled from [ServiceLocator])
 * - **[PermissionEngine]** — the four-layer, first-match decision (denylist → forced-ask →
 *   secure-context → mode/rule). Run for every call with the *current* [AutonomyMode] and
 *   the *latest* [ScreenState].
 * - **[PhoneControlExecutor]** — the redacting executor that actually performs an allowed
 *   tool call and returns a [ToolResult].
 * - **[AgentLoopController]** — step cap + repeat/stuck detection + cancellation latch.
 * - **[KillSwitch]** — the global panic stop; checked before every step and observed so an
 *   in-flight task aborts the instant it trips.
 * - **[RateLimiter]** — token bucket + consecutive-irreversible cap + auto-fallback to ASK.
 * - **[AuditLog]** — every decision/outcome recorded with only a hashed copy of the params.
 *
 * ### Public state (collected by the UI)
 * - [autonomyMode] — the persisted [AutonomyMode], surfaced as the mode pill.
 * - [pendingConfirm] — the [com.example.permission.ConfirmRequest] currently awaiting the
 *   user, or `null`. The confirm sheet renders this; the user answers via [resolveConfirm].
 * - [agentStatus] — coarse progress ([AgentStatus.active], current step text, step count).
 *
 * ### Threading & the confirm gate
 * Tool calls arrive on the WebSocket reader thread; [handleToolCalls] is `suspend` and is
 * meant to be dispatched onto the ViewModel scope (OFF the reader thread). A [Confirm]
 * decision suspends the calling coroutine on a [CompletableDeferred] gate, emits
 * [pendingConfirm] for the UI, and resumes only when [resolveConfirm] supplies the user's
 * answer — so the model's turn naturally blocks until the human decides, exactly as the
 * sequential/blocking Live protocol requires.
 *
 * @param application the process [Application]; used to seed [ServiceLocator] singletons.
 */
class AgentCoordinator(
    private val application: Application,
    /**
     * Scope the coordinator's own background work (mode persistence, kill-switch observation)
     * runs on. Defaults to a long-lived supervisor scope tied to the process; injectable for
     * tests. The `suspend` [handleToolCalls] runs on whatever scope the caller (the ViewModel)
     * dispatches it on, NOT this one.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val mapper: GeminiToolMapper = GeminiToolMapper(),
    private val loopController: AgentLoopController = AgentLoopController()
) {

    // --- Singletons pulled from the DI graph -----------------------------------------

    init {
        // Idempotent; safe even if the Application already called this in onCreate().
        ServiceLocator.init(application)
    }

    private val permissionEngine: PermissionEngine = ServiceLocator.permissionEngine
    private val executor: PhoneControlExecutor = ServiceLocator.phoneControlExecutor
    private val toolRegistry: ToolRegistry = ServiceLocator.toolRegistry
    private val killSwitch: KillSwitch = ServiceLocator.killSwitch
    private val rateLimiter: RateLimiter = ServiceLocator.rateLimiter
    private val auditLog: AuditLog = ServiceLocator.auditLog
    private val autonomyModeStore: AutonomyModeStore = ServiceLocator.autonomyModeStore
    private val ruleStore = ServiceLocator.ruleStore
    private val memory = ServiceLocator.memoryStore

    /** The episode ([com.example.memory.MemoryStore]) currently being recorded, or "" if none. */
    @Volatile
    private var currentEpisodeId: String = ""

    // --- Public UI state -------------------------------------------------------------

    /**
     * The persisted [AutonomyMode], defaulting to [AutonomyMode.ASK] until the DataStore
     * read completes. Backed by [AutonomyModeStore], so a kill-switch / fallback revert is
     * observed here too. Surfaced as the mode pill.
     */
    val autonomyMode: StateFlow<AutonomyMode> = autonomyModeStore.mode
        .stateIn(scope, SharingStarted.Eagerly, AutonomyMode.AUTO)

    private val _pendingConfirm = MutableStateFlow<com.example.permission.ConfirmRequest?>(null)

    /**
     * The action currently awaiting the user's confirm-sheet decision, or `null` when none
     * is pending. The UI renders the sheet from this and answers with [resolveConfirm]. Only
     * ever one confirm is outstanding at a time (the loop is sequential/blocking).
     */
    val pendingConfirm: StateFlow<com.example.permission.ConfirmRequest?> =
        _pendingConfirm.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus())

    /** Coarse, UI-facing progress while a task runs (active flag, step text, step count). */
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    // --- Confirm gate ----------------------------------------------------------------

    /**
     * The single outstanding confirm gate. Set when a [PermissionDecision.Confirm] suspends a
     * tool call; completed by [resolveConfirm]. Guarded by [confirmMutex] so a stray second
     * resolve or a concurrent confirm can't corrupt it.
     */
    @Volatile
    private var pendingDeferred: CompletableDeferred<ConfirmResolution>? = null

    @Volatile
    private var pendingAction: AgentAction? = null

    private val confirmMutex = Mutex()

    /**
     * The [Job] of the executor dispatch currently in flight (the suspended
     * [runExecutor] → [PhoneControlExecutor.execute] → GestureDispatcher gesture), or `null`
     * when no action is mid-dispatch. The kill switch cancels this so an in-progress synthetic
     * gesture (a tap, a swipe, text input) is torn down the instant a panic STOP fires, instead
     * of completing on the device and being reported back to the model as a success.
     */
    @Volatile
    private var inFlightJob: Job? = null

    /** The user's answer to a confirm sheet. */
    private data class ConfirmResolution(val allowed: Boolean, val scope: RuleScope)

    // --- Kill-switch observation -----------------------------------------------------

    init {
        // Mirror the kill switch into the loop controller and abort any outstanding confirm
        // the instant a panic STOP fires, regardless of which of the three triggers caused it.
        killSwitch.addListener { reason -> onKillSwitchTripped(reason) }
        scope.launch {
            killSwitch.tripped.collect { tripped ->
                if (tripped) {
                    loopController.cancel()
                    // Tear down any executor dispatch already in flight so an in-progress
                    // gesture cannot complete and be reported as a success after STOP.
                    inFlightJob?.cancel(CancellationException("kill switch tripped"))
                }
            }
        }
    }

    // --- Tool-call handling ----------------------------------------------------------

    /**
     * Handle one batch of model tool calls and return the matching function responses.
     *
     * For each [LiveFunctionCall], in order (the Live protocol is sequential/blocking):
     *  1. Map it to an [AgentAction] via [GeminiToolMapper].
     *  2. Check the [KillSwitch] and the [AgentLoopController] step/stuck limits.
     *  3. Run the [PermissionEngine] with the current [AutonomyMode] and the latest screen:
     *     - **Allow** → reserve rate-limit budget, then dispatch through the executor.
     *     - **Confirm** → emit [pendingConfirm], suspend on the gate, and resume on the user's
     *       [resolveConfirm] answer (allowed → dispatch; denied → an error response).
     *     - **Block** → synthesize an error response the model sees (never executes).
     *  4. Record the decision + outcome to the [AuditLog] (hashed params only).
     *
     * Every branch yields exactly one [LiveFunctionResponse] whose `id` echoes the call's id
     * verbatim — a missing/mismatched id hangs the Live turn (ARCHITECTURE.md §3.4). A
     * `task_complete` call ends the loop and resets per-task guards.
     *
     * @param calls the batch of function calls from the current `toolCall` server message.
     * @return one [LiveFunctionResponse] per call, in the same order.
     */
    suspend fun handleToolCalls(calls: List<LiveFunctionCall>): List<LiveFunctionResponse> {
        if (calls.isEmpty()) return emptyList()
        _agentStatus.value = _agentStatus.value.copy(active = true)
        val responses = ArrayList<LiveFunctionResponse>(calls.size)
        for (call in calls) {
            responses += handleOne(call)
        }
        return responses
    }

    /**
     * Expand a saved [skill] into its steps and run each as an ordinary, permission-gated tool
     * call (via [handleOne] with skill expansion disabled, so a skill step naming another skill
     * is treated as an unknown tool and blocked rather than recursing). Returns one combined
     * response summarising every step so the model sees what its self-authored tool did.
     */
    private suspend fun runSkill(callId: String, skill: Skill): LiveFunctionResponse {
        val results = ArrayList<Map<String, Any?>>(skill.steps.size)
        for ((i, step) in skill.steps.withIndex()) {
            if (killSwitch.isTripped) {
                results += mapOf("tool" to step.tool, "skipped" to "kill switch active")
                break
            }
            val stepCall = LiveFunctionCall(id = "$callId#$i", name = step.tool, args = step.args)
            val resp = handleOne(stepCall, expandSkills = false)
            results += mapOf("tool" to step.tool, "response" to resp.response)
        }
        return LiveFunctionResponse(
            id = callId,
            name = ToolRegistry.skillToolName(skill.name),
            response = mapOf("skill" to skill.name, "ranSteps" to results.size, "steps" to results)
        )
    }

    private suspend fun handleOne(
        call: LiveFunctionCall,
        expandSkills: Boolean = true
    ): LiveFunctionResponse {
        val callId = call.id ?: ""
        val toolName = call.name

        // --- Saved skill invoked as a tool: expand into its steps and run each through the SAME
        // gate. A skill is a macro over existing tools — it grants no new authority. expandSkills
        // is false for the steps themselves, so a skill can't recursively invoke another skill. ---
        if (expandSkills) {
            val skill = toolRegistry.skillForToolName(toolName)
            if (skill != null) return runSkill(callId, skill)
        }

        val action = mapper.toAgentAction(call)

        // --- task_complete is a control signal: end the loop, reset per-task guards. ---
        if (action.type == com.example.permission.ActionType.TASK_COMPLETE) {
            val result = runExecutor(callId, toolName, call.args.orEmpty())
            val completed = result as? ToolResult.Completed
            finishTask(success = completed?.success ?: true, summary = completed?.summary.orEmpty())
            return result.toFunctionResponse()
        }

        // --- Kill switch wins over everything. ---
        if (killSwitch.isTripped) {
            return errorResponse(
                callId, toolName,
                "Stopped: the agent kill switch is active. Re-arm before continuing."
            )
        }

        val screen = latestScreen()

        // --- Fail closed on an unreadable screen. ---
        // A null screen makes the engine compute SecureReason.NONE, so NO secure-context /
        // denylisted-app / password-field block fires (DefaultSecureContextDetector.inspect(null)
        // → NONE). Rather than auto-run a side-effecting action against a screen we cannot
        // inspect (possibly a secure one), refuse and ask the model to read the screen first.
        // ONLY the node/coordinate primitives (tap/type/scroll/swipe/long-press) are unsafe to run
        // blind — they act on a specific element of the CURRENT (possibly secure) screen, so they
        // fail closed when it is unreadable. open_app, open_url, Back/Home/Recents, system toggles,
        // intent drafts and skill memory neither read nor act on the current screen's tree, so a
        // null screen must NOT block them (open_app is a launch intent — requiring a screen read
        // here was silently breaking "open <app>" whenever the a11y tree wasn't ready).
        if (screen == null && action.type.requiresReadableScreen()) {
            return errorResponse(
                callId, toolName,
                "Screen is not readable yet. Call get_screen first, then retry."
            )
        }

        // --- Loop guard rails (step cap / repeat-stuck / cancellation). ---
        when (val verdict = loopController.onBeforeStep(toolName, call.args.orEmpty(), screen)) {
            is StepVerdict.Cancelled ->
                return errorResponse(callId, toolName, "Action cancelled.")
            is StepVerdict.StepCapReached -> {
                finishTask(success = false, summary = "Reached the step limit before finishing.")
                return errorResponse(
                    callId, toolName,
                    "Reached the ${verdict.maxSteps}-step limit; stopping the task. " +
                        "Call task_complete to finish."
                )
            }
            is StepVerdict.Stuck -> {
                // Don't terminate outright — feed the model a corrective hint so it can
                // re-observe and recover instead of silently spinning.
                Log.w(TAG, "Loop stuck: ${verdict.reason}")
                return errorResponse(
                    callId, toolName,
                    "You repeated '${verdict.toolName}' on an unchanged screen " +
                        "${verdict.repeats} times. Call get_screen and try a different approach."
                )
            }
            is StepVerdict.Proceed ->
                _agentStatus.value = _agentStatus.value.copy(
                    active = true,
                    currentStep = action.description.ifBlank { toolName },
                    stepCount = verdict.step
                )
        }

        // --- The permission gate (denylist → forced-ask → secure-context → mode/rule). ---
        val mode = currentMode()
        // Fail-secure: a null KeyguardManager (no keyguard service) is treated as locked,
        // mirroring AgentAccessibilityService.isKeyguardActive's fail-secure default.
        val keyguardLocked = ServiceLocator.keyguardManager?.isKeyguardLocked ?: true
        return when (val decision = permissionEngine.decide(action, mode, screen, keyguardLocked)) {
            is PermissionDecision.Allow ->
                executeAllowed(callId, toolName, action, mode, decision.reason, confirmed = false)

            is PermissionDecision.Block -> {
                onBlocked()
                audit(action, mode, "Block", AuditOutcome.BLOCKED, toolName)
                errorResponse(callId, toolName, decision.message)
            }

            is PermissionDecision.Confirm -> {
                val request = decision.request
                val resolution = awaitConfirm(request.action, request)
                if (resolution.allowed) {
                    // The coordinator is the enforcement boundary: never trust a scope the engine
                    // did not offer for THIS action. The engine narrows offeredScopes per action
                    // (irreversible/forced-ask/BLOCKED → [ONCE]; GUARDED → [ONCE, SESSION], never
                    // ALWAYS), and DataStoreRuleStore only backstops the never-standing types — a
                    // GUARDED-reversible action escalated to a standing ALWAYS grant would slip
                    // through. Clamp an unoffered scope down to ONCE before persisting.
                    val safeScope =
                        if (resolution.scope in request.offeredScopes) resolution.scope
                        else RuleScope.ONCE
                    // Persist any standing grant the user chose before running.
                    runCatching { ruleStore.grant(action, safeScope) }
                    executeAllowed(callId, toolName, action, mode, "User confirmed.", confirmed = true)
                } else {
                    onBlocked()
                    audit(action, mode, "Confirm", AuditOutcome.DENIED_BY_USER, toolName)
                    errorResponse(callId, toolName, "The user declined this action.")
                }
            }
        }
    }

    /**
     * Reserve rate-limit budget for an allowed action, then dispatch it. A rate-limit /
     * consecutive-irreversible rejection becomes a soft error the model sees (it does not run).
     */
    private suspend fun executeAllowed(
        callId: String,
        toolName: String,
        action: AgentAction,
        mode: AutonomyMode,
        reason: String,
        confirmed: Boolean
    ): LiveFunctionResponse {
        val budget = rateLimiter.tryConsume(irreversible = !action.reversible)
        if (!budget.allowed) {
            audit(action, mode, "Allow", AuditOutcome.BLOCKED, toolName)
            val why = when (budget.rejection) {
                RateLimiter.Rejection.RATE_LIMITED ->
                    "Slow down — too many actions in a short time. Wait a moment and retry."
                RateLimiter.Rejection.CONSECUTIVE_IRREVERSIBLE_CAP ->
                    "Too many irreversible actions in a row; do a reversible step or finish first."
                RateLimiter.Rejection.NONE -> "Action throttled."
            }
            return errorResponse(callId, toolName, why)
        }

        rateLimiter.onActionAllowed()
        val result = runExecutor(callId, toolName, action.params)
        // Defense-in-depth backstop: if the kill switch tripped while the executor was running,
        // never audit/return this as an allowed success — fail closed even if the dispatch job
        // happened to finish before its cancellation landed.
        if (killSwitch.isTripped) {
            audit(action, mode, if (confirmed) "Confirm" else "Allow", AuditOutcome.BLOCKED, toolName)
            return errorResponse(
                callId, toolName,
                "Stopped: the agent kill switch is active. Re-arm before continuing."
            )
        }
        val outcome = when (result) {
            is ToolResult.Failure -> AuditOutcome.FAILED
            else -> if (confirmed) AuditOutcome.ALLOWED_CONFIRMED else AuditOutcome.ALLOWED_AUTO
        }
        audit(action, mode, if (confirmed) "Confirm" else "Allow", outcome, toolName)
        return result.toFunctionResponse()
    }

    /**
     * Run the (redacting) executor, translating cancellation into a clean error response.
     *
     * The dispatch runs inside a dedicated child [Job] (parented to the caller's coroutine,
     * so it still inherits the dispatcher and is cancelled if the caller is) that is published
     * as [inFlightJob] for the lifetime of the call. The kill switch cancels that job, which
     * tears down the active cancellation-aware suspension in GestureDispatcher and aborts the
     * in-progress gesture — so a tap/swipe/text input can never complete after a panic STOP.
     */
    private suspend fun runExecutor(
        callId: String,
        toolName: String,
        args: Map<String, Any?>
    ): ToolResult {
        val dispatchJob = Job(coroutineContext[Job])
        // If the switch tripped between the gate and here, cancel immediately (don't dispatch).
        if (killSwitch.isTripped) dispatchJob.cancel(CancellationException("kill switch tripped"))
        inFlightJob = dispatchJob
        return try {
            withContext(dispatchJob) {
                executor.execute(callId, toolName, args)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The executor already converts tool failures to ToolResult.Failure; this guards the
            // dispatch path itself. Keep the raw message local — never hand it to the model.
            Log.w(TAG, "Executor dispatch failed for '$toolName': ${t.message}", t)
            ToolResult.Failure(toolName, callId, "Tool '$toolName' failed.")
        } finally {
            if (inFlightJob === dispatchJob) inFlightJob = null
            // Complete the child job so it never leaks; cancellation above already finished it.
            dispatchJob.complete()
        }
    }

    // --- Confirm-sheet hand-off ------------------------------------------------------

    /**
     * Emit [pendingConfirm] and suspend on a fresh [CompletableDeferred] until the user answers
     * via [resolveConfirm]. Exactly one confirm is outstanding at a time (sequential loop).
     */
    private suspend fun awaitConfirm(
        action: AgentAction,
        request: com.example.permission.ConfirmRequest
    ): ConfirmResolution {
        val deferred = CompletableDeferred<ConfirmResolution>()
        confirmMutex.withLock {
            pendingDeferred = deferred
            pendingAction = action
            _pendingConfirm.value = request
        }
        return try {
            deferred.await()
        } finally {
            confirmMutex.withLock {
                if (pendingDeferred === deferred) {
                    pendingDeferred = null
                    pendingAction = null
                    _pendingConfirm.value = null
                }
            }
        }
    }

    /**
     * Complete the outstanding confirm with the user's decision from the confirm sheet.
     *
     * @param allowed `true` if the user tapped Allow, `false` for Deny.
     * @param scope   the [RuleScope] the user picked (defaults to a single [RuleScope.ONCE]
     *                grant). Standing scopes are honored by the [RuleStore], which itself
     *                refuses to persist a grant for an irreversible/forced-ask action.
     */
    fun resolveConfirm(allowed: Boolean, scope: RuleScope = RuleScope.ONCE) {
        val deferred = pendingDeferred ?: return
        // Clear the UI immediately; the awaiting coroutine's `finally` also clears, but the
        // sheet should disappear the instant the user taps.
        _pendingConfirm.value = null
        deferred.complete(ConfirmResolution(allowed, scope))
    }

    // --- Mode ------------------------------------------------------------------------

    /**
     * Update and persist the active [AutonomyMode]. The change is observed by [autonomyMode]
     * once the DataStore write lands. BYPASS is a deliberate, re-confirmed opt-in upstream in
     * the UI — this method just persists whatever it is given.
     */
    fun setMode(mode: AutonomyMode) {
        scope.launch { runCatching { autonomyModeStore.setMode(mode) } }
    }

    /** Snapshot the current mode (suspending) for a permission decision. */
    private suspend fun currentMode(): AutonomyMode =
        runCatching { autonomyModeStore.current() }.getOrDefault(autonomyMode.value)

    /**
     * Record a typed user turn into the current episode (episodic memory). No-op when no session
     * is open. Fire-and-forget: memory never blocks the UI.
     */
    fun noteUserUtterance(text: String) {
        val episodeId = currentEpisodeId
        if (episodeId.isEmpty()) return
        scope.launch { runCatching { memory.recordUtterance(episodeId, fromUser = true, text = text) } }
    }

    // --- Session / task lifecycle ----------------------------------------------------

    /**
     * Begin a new hands-free task: reset the loop guards and the per-session rate-limit /
     * block counters, and re-arm the kill switch if it was tripped. Call when the user kicks
     * off a fresh spoken task.
     */
    fun beginTask() {
        loopController.reset()
        rateLimiter.resetSession()
        if (killSwitch.isTripped) killSwitch.reset()
        _agentStatus.value = AgentStatus(active = true, currentStep = "", stepCount = 0)
        // Open a fresh episodic-memory chapter for this session (auto-captures actions as XENO
        // acts). The id is generated synchronously so currentEpisodeId is valid immediately (no
        // race against early events); the DB write is fire-and-forget — memory never blocks the loop.
        val episodeId = UUID.randomUUID().toString()
        currentEpisodeId = episodeId
        scope.launch { runCatching { memory.beginEpisode(episodeId) } }
    }

    /**
     * Mark the current task finished (via `task_complete`, the step cap, or a cancel). Clears
     * the active flag; leaves the loop controller's counters until [beginTask] resets them.
     * Closes the episodic-memory chapter with its outcome + summary.
     */
    fun finishTask(success: Boolean = true, summary: String = "") {
        _agentStatus.value = _agentStatus.value.copy(active = false)
        // Drop any session allow rules so the next task starts from a clean slate.
        runCatching { ruleStore.revokeSession() }
        val episodeId = currentEpisodeId
        currentEpisodeId = ""
        if (episodeId.isNotEmpty()) {
            scope.launch { runCatching { memory.endEpisode(episodeId, success, summary) } }
        }
    }

    private fun onKillSwitchTripped(reason: KillReason) {
        loopController.cancel()
        // Cancel any executor dispatch in flight so the active suspendCancellableCoroutine in
        // GestureDispatcher is torn down and the gesture (tap/swipe/text input) is aborted —
        // never completing on the device and being audited/returned as a success after STOP.
        inFlightJob?.cancel(CancellationException("kill switch tripped"))
        // Resolve any outstanding confirm as a denial so the suspended call returns promptly.
        pendingDeferred?.complete(ConfirmResolution(allowed = false, scope = RuleScope.ONCE))
        _pendingConfirm.value = null
        _agentStatus.value = _agentStatus.value.copy(active = false, currentStep = "Stopped")
        // Drop the autonomy baseline to ASK so re-arming (beginTask → killSwitch.reset()) can
        // never silently resume in AUTO/BYPASS — the panic-latch contract (KillSwitch /
        // AutonomyModeStore KDoc). Mirrors the onBlocked() auto-fallback revert.
        scope.launch { runCatching { autonomyModeStore.resetToSafeDefault() } }
        Log.i(TAG, "Kill switch tripped: $reason")
    }

    /**
     * Record a block / user-denial and, if the auto-fallback threshold trips (3 consecutive /
     * 20 per session), revert the autonomy baseline to [AutonomyMode.ASK].
     */
    private fun onBlocked() {
        val fellBack = rateLimiter.onBlock()
        if (fellBack) {
            Log.w(TAG, "Auto-fallback threshold reached; reverting to ASK.")
            scope.launch { runCatching { autonomyModeStore.resetToSafeDefault() } }
        }
    }

    // --- Screen / audit helpers ------------------------------------------------------

    /**
     * The latest [ScreenState] for the permission DECISION ONLY: read from the live
     * accessibility controller when it is ready, else `null`.
     *
     * This is read with `redact = false` deliberately. The secure-context gate
     * ([com.example.permission.DefaultSecureContextDetector]) must see VERBATIM element
     * text: its backstop for an unlabeled displayed secret (an OTP/PAN/IBAN printed as
     * plain static text with no hint/resource-id/password flag) matches on the raw value,
     * and redaction would have already replaced that value with the placeholder before the
     * gate ever ran — defeating the check. This screen never leaves the device: it is fed
     * only to the loop guard and [permissionEngine]; the model receives the tool's own
     * redacted [com.example.agent.ToolResult.screen]. Redaction is the cloud boundary, not
     * the security decision.
     *
     * NOTE: a `null` screen does NOT fail closed in the engine — `DefaultSecureContextDetector
     * .inspect(null)` yields [com.example.permission.SecureReason.NONE], so the engine fires no
     * secure-context / denylisted-app / password-field block. [handleOne] therefore refuses the
     * node/coordinate primitives when this returns `null` (see [requiresReadableScreen]); screen-
     * independent actions (open_app, global nav, toggles, intents) still proceed.
     */
    private suspend fun latestScreen(): ScreenState? {
        val controller = Accessibility.controller?.takeIf { it.isReady } ?: return null
        return runCatching { controller.readScreen(redact = false) }.getOrNull()
    }

    /**
     * `true` ONLY for the primitives that act on a specific on-screen node/coordinate resolved
     * from the most recent screen read — tap, type, scroll, swipe, long-press. These must fail
     * closed when the screen is unreadable, because acting blind on a screen we cannot inspect
     * (possibly a secure login/OTP) is exactly the risk the guard defends.
     *
     * Everything else is screen-INDEPENDENT and must NOT be blocked by a null screen:
     *  - open_app / open_url / web_search / intent drafts → fired via Intents, ignore the a11y tree.
     *  - Back / Home / Recents / notifications → performGlobalAction, no node lookup.
     *  - torch / brightness / DND / volume → system toggles.
     *  - save/recall/list skill → local JSON only.
     *  - get_screen / screenshot / task_complete → pure reads / control (handled earlier).
     * Each of these has its own guard (e.g. OpenAppTool re-checks the denylist on the resolved
     * package); none is made safer by a screen snapshot, so requiring one only breaks them.
     */
    private fun com.example.permission.ActionType.requiresReadableScreen(): Boolean = when (this) {
        com.example.permission.ActionType.TAP,
        com.example.permission.ActionType.INPUT_TEXT,
        com.example.permission.ActionType.SWIPE,
        com.example.permission.ActionType.SCROLL,
        com.example.permission.ActionType.LONG_PRESS -> true
        else -> false
    }

    private fun audit(
        action: AgentAction,
        mode: AutonomyMode,
        decision: String,
        outcome: AuditOutcome,
        toolName: String
    ) {
        scope.launch {
            runCatching {
                auditLog.record(
                    AuditRecord(
                        id = UUID.randomUUID().toString(),
                        timestampMs = System.currentTimeMillis(),
                        mode = mode,
                        actionType = action.type.name,
                        targetApp = action.targetApp,
                        // ponytail: random id, not a hash — deterministic dedup needs the salt removed first anyway
                        paramsHash = UUID.randomUUID().toString(),
                        decision = decision,
                        outcome = outcome
                    )
                )
            }.onFailure { Log.w(TAG, "Audit write failed: ${it.message}") }
            // Episodic capture: the same seam records what XENO did into long-term memory. Uses
            // the action's human description (no raw params/secrets), mirroring the audit privacy
            // rule. Pure screen reads are filtered inside the store as noise.
            val episodeId = currentEpisodeId
            if (episodeId.isNotEmpty()) {
                runCatching {
                    memory.recordEvent(episodeId, toolName, action.targetApp, action.description, outcome.name)
                }
            }
        }
    }

    // --- ToolResult → wire response --------------------------------------------------

    /** Convert a [ToolResult] into the [LiveFunctionResponse] the model receives. */
    private fun ToolResult.toFunctionResponse(): LiveFunctionResponse = when (this) {
        is ToolResult.Success -> LiveFunctionResponse(
            id = callId,
            name = toolName,
            response = buildMap {
                put("result", "ok")
                if (message.isNotBlank()) put("message", message)
                screen?.let { put("screen", it.toModelJson()) }
                if (data.isNotEmpty()) put("data", data)
            }
        )

        is ToolResult.Failure -> LiveFunctionResponse(
            id = callId,
            name = toolName,
            response = buildMap {
                put("result", "error")
                put("error", error)
                screen?.let { put("screen", it.toModelJson()) }
            }
        )

        is ToolResult.Completed -> LiveFunctionResponse(
            id = callId,
            name = toolName,
            response = mapOf(
                "result" to if (success) "done" else "failed",
                "summary" to summary
            )
        )
    }

    /** A plain error response (no execution happened) the model can reason over. */
    private fun errorResponse(callId: String, toolName: String, message: String): LiveFunctionResponse =
        LiveFunctionResponse(
            id = callId,
            name = toolName,
            response = mapOf("result" to "error", "error" to message)
        )

    private companion object {
        const val TAG = "AgentCoordinator"
    }
}

/** Placeholder emitted in place of a redacted value so the model knows one exists. */
private const val REDACTED_MARKER = "█REDACTED█"

/**
 * Compact, index-based JSON projection of a [ScreenState] for the model (set-of-marks; see
 * ARCHITECTURE.md §3.3). The screen handed here has already been redacted on the read path,
 * so only structural labels/bounds reach this serialization. Indices are NOT stable across
 * reads — the model must re-observe after any screen-changing action.
 *
 * Defense-in-depth: mirror [com.example.accessibility.ScreenSerializer]'s final redaction
 * gate so a password field's value, or any text on a secure screen (FLAG_SECURE, keyguard,
 * denylisted banking/wallet app, or a trimmed/over-cap password/OTP field folded into
 * `secure` at the read boundary), never leaves the device verbatim through this path even
 * if the upstream ScreenRedactor was bypassed or ran under a weaker policy. Bounds and
 * capability flags are still emitted so the model can ground and act.
 */
private fun ScreenState.toModelJson(): Map<String, Any?> = mapOf(
    "package" to packageName,
    "activity" to activity,
    "secure" to secure,
    "elements" to elements.map { e ->
        buildMap<String, Any?> {
            put("i", e.index)
            put("role", e.role)
            if (e.password || secure) {
                put("text", REDACTED_MARKER)
            } else {
                e.text?.let { put("text", it) }
                e.contentDescription?.let { put("desc", it) }
                e.hint?.let { put("hint", it) }
            }
            put("bounds", listOf(e.bounds.left, e.bounds.top, e.bounds.right, e.bounds.bottom))
            if (e.clickable) put("clickable", true)
            if (e.editable) put("editable", true)
            if (e.scrollable) put("scrollable", true)
            if (e.checkable) put("checked", e.checked)
        }
    }
)
