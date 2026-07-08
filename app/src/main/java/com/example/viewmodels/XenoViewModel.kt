package com.example.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.audio.AudioCapture
import com.example.audio.AudioStreamPlayer
import com.example.core.ChatMessage
import com.example.core.CompanionState
import com.example.core.Sender
import com.example.live.GeminiLiveClient
import com.example.live.LiveTool
import com.example.live.LiveGoogleSearch
import com.example.live.LiveCodeExecution
import com.example.live.LiveUrlContext
import com.example.models.Persona
import kotlinx.coroutines.Job
import com.example.agent.AgentCoordinator
import com.example.agent.GeminiToolMapper
import com.example.character.NazimPersona
import com.example.di.ServiceLocator
import com.example.permission.AutonomyMode
import com.example.permission.KillReason
import com.example.permission.RuleScope
import com.example.vision.CameraVisionSource
import com.example.vision.ScreenCaptureService
import com.example.vision.VisionState
import com.example.overlay.XenoOverlayService
import com.example.overlay.OverlayBus
import com.example.character.NazimState
import com.example.voice.VoiceService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.example.config.ApiKeyStore
import com.example.config.PersonaStore
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "brain" of Xeno Live.
 *
 * Wires together [GeminiLiveClient] (realtime S2S WebSocket), [AudioCapture] (mic ->
 * 16kHz PCM16) and [AudioStreamPlayer] (24kHz PCM16 -> speaker) and projects the whole
 * session as a set of [StateFlow]s the UI collects with lifecycle awareness.
 *
 * Threading contract:
 *  - All [StateFlow] mutations happen on the main thread via [viewModelScope] coroutines
 *    (the model uses `Dispatchers.Main.immediate` implicitly through viewModelScope).
 *  - The Gemini [GeminiLiveClient.Listener] callbacks arrive on OkHttp's WebSocket reader
 *    thread, and audio amplitude callbacks arrive on capture/playback worker threads, so
 *    every handler re-dispatches onto [viewModelScope] before touching state.
 */
class XenoViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val MISSING_KEY_SENTINEL = "MY_GEMINI_API_KEY"

        /**
         * Hidden trigger sent the instant the session is ready so XENO speaks FIRST and opens in
         * Hindi. Sent as a turn (not shown in the transcript); XENO must not read it aloud.
         */
        const val GREETING_KICKOFF =
            "(SYSTEM CUE — not spoken by the user. The session just started and the user can hear " +
            "you now. Speak FIRST: greet them warmly in HINDI, in your calm Indian-accented voice, " +
            "in one or two short sentences (e.g. \"नमस्ते, मैं XENO हूँ — बताइए, आज मैं आपके लिए क्या " +
            "करूँ?\"), then wait. Do NOT read this cue aloud; after this, reply in whatever language " +
            "the user speaks.)"
    }

    // -- Persona ------------------------------------------------------------
    // Nazim is the single, persistent face + voice of Xeno Live (SCOPE.md §2).
    private val _selectedPersona = MutableStateFlow(NazimPersona.nazim)
    val selectedPersona: StateFlow<Persona> = _selectedPersona.asStateFlow()

    // -- Session lifecycle state -------------------------------------------
    private val _companionState = MutableStateFlow(CompanionState.IDLE)
    val companionState: StateFlow<CompanionState> = _companionState.asStateFlow()

    // -- Transcript ---------------------------------------------------------
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // -- Reactive audio amplitude (0..1) -----------------------------------
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    // -- Connection / mic flags --------------------------------------------
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    // -- Error surface ------------------------------------------------------
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // -- XENO's self-authored prompt note (edited via the update_self_prompt tool) ---------
    private val personaStore = PersonaStore(app)

    /** XENO's persisted self-directive; folded into the system instruction at connect. */
    val selfDirective: StateFlow<String> =
        personaStore.directive.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // -- API keys (in-app, multiple, with failover) ------------------------
    private val apiKeyStore = ApiKeyStore(app)

    /** The user's saved API keys (entered in-app), reactive and persisted on-device. */
    val apiKeys: StateFlow<List<String>> =
        apiKeyStore.keys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keys for the current connection attempt, in priority order, + the one being tried. */
    private var sessionKeys: List<String> = emptyList()
    private var sessionKeyIndex: Int = 0
    private var setupCompleted: Boolean = false

    // -- API key availability ----------------------------------------------
    private val _apiKeyMissing = MutableStateFlow(isApiKeyMissing())
    val apiKeyMissing: StateFlow<Boolean> = _apiKeyMissing.asStateFlow()

    // -- Wiring -------------------------------------------------------------
    private var liveClient: GeminiLiveClient? = null
    private val capture = AudioCapture()
    private val player = AudioStreamPlayer()

    // -- Phone-control agent ------------------------------------------------
    /** Bridges Gemini tool calls to the permission / execution / audit stack. */
    private val agentCoordinator = AgentCoordinator(app)

    /**
     * Gemini's built-in tools (web grounding / code execution / URL context) require a
     * BILLING-enabled project on the Live API. On the free tier, including them makes the
     * server reject the WHOLE session with a 1011 "quota / billing" error — so the app can't
     * connect at all. They're OFF by default so voice works on the free tier; flip this to
     * `true` once billing is enabled to give Nazim live web search + code execution.
     */
    private val enableBuiltInTools = false

    /**
     * The tools advertised to Gemini Live: always our on-device phone-control function
     * declarations; the built-in tools only when [enableBuiltInTools] (needs billing). Built once.
     */
    private val agentTools: List<LiveTool> by lazy {
        buildList {
            add(GeminiToolMapper().toLiveTool(ServiceLocator.phoneControlExecutor.declarations()))
            if (enableBuiltInTools) {
                add(LiveTool(googleSearch = LiveGoogleSearch()))
                add(LiveTool(codeExecution = LiveCodeExecution()))
                add(LiveTool(urlContext = LiveUrlContext()))
            }
        }
    }

    /** The in-flight tool-handling job, so a barge-in / cancellation can abort it. */
    private var toolJob: Job? = null

    // -- Vision: Nazim can SEE (camera = the world, screen-share = the pixels) ------
    private val cameraVision = CameraVisionSource()
    private val _visionState = MutableStateFlow(VisionState.OFF)
    /** What Nazim is currently seeing beyond the accessibility tree (off / camera / screen). */
    val visionState: StateFlow<VisionState> = _visionState.asStateFlow()

    /**
     * Emitted when the user turns on screen-share. The Activity collects this and launches the
     * system MediaProjection grant dialog (a projection token can only come from an Activity),
     * then calls [startScreenShare] with the result.
     */
    private val _screenShareRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenShareRequests: SharedFlow<Unit> = _screenShareRequests.asSharedFlow()

    // -- Roam: XENO floats over every app and walks to what it taps --------
    private val _roamEnabled = MutableStateFlow(false)
    /** Whether XENO is roaming on top of all apps (the floating overlay). */
    val roamEnabled: StateFlow<Boolean> = _roamEnabled.asStateFlow()

    /** Emitted when roam is turned on but the "Display over other apps" grant is missing. */
    private val _overlayPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val overlayPermissionRequests: SharedFlow<Unit> = _overlayPermissionRequests.asSharedFlow()

    /** Active autonomy mode (Ask / Ask-less / Auto / Bypass / Plan) — drives the mode pill. */
    val autonomyMode: StateFlow<AutonomyMode> = agentCoordinator.autonomyMode

    /** The action awaiting the user's confirm-sheet decision, or null. */
    val pendingConfirm = agentCoordinator.pendingConfirm

    /** Coarse progress while a hands-free task runs (drives the agent console + STOP). */
    val agentStatus = agentCoordinator.agentStatus

    /**
     * Accumulates streamed text for the current model turn. While a turn is in flight,
     * incoming [GeminiLiveClient.Listener.onText] chunks are appended to the last XENO
     * message; a new XENO message is started on the next turn.
     */
    private var currentTurnHasMessage = false

    init {
        // Seed the transcript with the active persona's greeting so the screen is never
        // empty. This message is replaced/extended once a live turn begins.
        _messages.value = listOf(
            ChatMessage(sender = Sender.XENO, text = _selectedPersona.value.greeting)
        )
        player.onAmplitude = { amp ->
            // Playback worker thread -> hop onto main.
            viewModelScope.launch { _amplitude.value = amp }
        }

        // Mirror the live avatar state + voice amplitude onto the roaming overlay so the 3D
        // character that walks your screen shows the same expression and lip-syncs to XENO.
        viewModelScope.launch {
            combine(_companionState, agentStatus) { cs, status ->
                NazimState.from(cs, status.active)
            }.collect { OverlayBus.setConversationState(it) }
        }
        viewModelScope.launch {
            _amplitude.collect { OverlayBus.setAmplitude(it) }
        }
    }

    /** All usable keys in priority order: in-app keys first, then the build-time key. */
    private fun availableKeys(): List<String> {
        val build = BuildConfig.GEMINI_API_KEY
        val buildKey =
            if (build.isNotEmpty() && build != MISSING_KEY_SENTINEL) listOf(build) else emptyList()
        return (apiKeys.value + buildKey).distinct()
    }

    private fun isApiKeyMissing(): Boolean = availableKeys().isEmpty()

    /** Add an in-app API key (persisted on-device); used in order with failover. */
    fun addApiKey(key: String) {
        viewModelScope.launch {
            apiKeyStore.add(key)
            _apiKeyMissing.value = false
        }
    }

    /** Remove an in-app API key. */
    fun removeApiKey(key: String) {
        viewModelScope.launch {
            apiKeyStore.remove(key)
            _apiKeyMissing.value = isApiKeyMissing()
        }
    }

    // ======================================================================
    //  Public API
    // ======================================================================

    /**
     * Switches the active persona. Resets the transcript to the new greeting. If a live
     * session is currently running, it is torn down and restarted with the new persona's
     * voice + system instruction.
     */
    fun selectPersona(p: Persona) {
        if (p.id == _selectedPersona.value.id) return
        _selectedPersona.value = p

        val wasConnected = _isConnected.value
        if (wasConnected) {
            // Restart the session so the new voice/system-instruction takes effect.
            stopSession()
        }

        currentTurnHasMessage = false
        _messages.value = listOf(
            ChatMessage(sender = Sender.XENO, text = p.greeting)
        )

        if (wasConnected) {
            startSession()
        }
    }

    /** Toggles the live session on/off (the mic button). */
    fun toggleLive() {
        if (_isConnected.value) {
            stopSession()
        } else {
            startSession()
        }
    }

    /** Sets the autonomy mode (from the mode pill). Persisted via DataStore. */
    fun setAutonomyMode(mode: AutonomyMode) = agentCoordinator.setMode(mode)

    /** Answers the pending confirm sheet (allow/deny + optional standing scope). */
    fun resolveConfirm(allowed: Boolean, scope: RuleScope?) =
        agentCoordinator.resolveConfirm(allowed, scope ?: RuleScope.ONCE)

    /** Panic STOP: trips the kill switch, halting any in-flight agent task. */
    fun stopAgent() = ServiceLocator.killSwitch.trigger(KillReason.USER_STOP)

    /** Toggle the camera stream (the world). Caller must already hold CAMERA permission. */
    fun toggleCameraVision() {
        if (_visionState.value == VisionState.CAMERA) {
            cameraVision.stop()
            _visionState.value = VisionState.OFF
        } else {
            stopScreenShare()
            cameraVision.start(getApplication(), lensFront = false) { frame ->
                liveClient?.sendVideoFrame(frame)
            }
            _visionState.value = VisionState.CAMERA
        }
    }

    /** Turn screen-share on (asks the Activity for a projection token) or off. */
    fun requestScreenShare() {
        if (_visionState.value == VisionState.SCREEN) stopScreenShare()
        else _screenShareRequests.tryEmit(Unit)
    }

    /** Begin streaming the screen once the Activity has obtained the projection grant. */
    fun startScreenShare(resultCode: Int, data: android.content.Intent) {
        cameraVision.stop()
        ScreenCaptureService.frameSink = { frame -> liveClient?.sendVideoFrame(frame) }
        ScreenCaptureService.start(getApplication(), resultCode, data)
        _visionState.value = VisionState.SCREEN
    }

    /** Stop the screen-share stream. */
    fun stopScreenShare() {
        if (_visionState.value == VisionState.SCREEN) {
            ScreenCaptureService.stop(getApplication())
            _visionState.value = VisionState.OFF
        }
        ScreenCaptureService.frameSink = null
    }

    /** Stop any active vision stream (camera or screen). */
    fun stopVision() {
        cameraVision.stop()
        stopScreenShare()
        _visionState.value = VisionState.OFF
    }

    /** Toggle XENO roaming over all apps. Requests the overlay grant if missing. */
    fun toggleRoam() {
        val ctx = getApplication<Application>()
        if (_roamEnabled.value) {
            XenoOverlayService.stop(ctx)
            _roamEnabled.value = false
            return
        }
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            XenoOverlayService.start(ctx)
            _roamEnabled.value = true
        } else {
            _overlayPermissionRequests.tryEmit(Unit)
        }
    }

    /** Called by the Activity after the user returns from the overlay-permission screen. */
    fun onOverlayPermissionResult() {
        val ctx = getApplication<Application>()
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            XenoOverlayService.start(ctx)
            _roamEnabled.value = true
        }
    }

    /**
     * Opens the realtime S2S session: connects [GeminiLiveClient], and once setup
     * completes, starts streaming mic audio and playing back model audio.
     */
    fun startSession() {
        if (_isConnected.value) return

        sessionKeys = availableKeys()
        if (sessionKeys.isEmpty()) {
            _apiKeyMissing.value = true
            _errorMessage.value = "No Gemini API key yet. Tap the settings icon to add one."
            _companionState.value = CompanionState.ERROR
            return
        }
        _apiKeyMissing.value = false
        sessionKeyIndex = 0
        _errorMessage.value = null

        // Hold a microphone foreground service for the whole session. Without it Android silences
        // the mic the moment the app is backgrounded — so when the user leaves the app to watch
        // XENO roam, the session would go deaf and stop acting. Started here from the foreground
        // (a mic FGS can't be started from the background), it keeps the mic capturing while
        // roaming. We don't use the service's own capture; it just keeps the app mic-eligible.
        try {
            VoiceService.start(getApplication())
        } catch (_: Throwable) {
            // If the FGS can't start (rare), the session still works while the app is foregrounded.
        }

        connectWithCurrentKey()
    }

    /**
     * Connects using `sessionKeys[sessionKeyIndex]`. If this key fails before the session is
     * established, [SessionListener.onError] rotates to the next key (failover).
     */
    private fun connectWithCurrentKey() {
        val persona = _selectedPersona.value
        setupCompleted = false
        _companionState.value = CompanionState.CONNECTING
        currentTurnHasMessage = false

        val client = GeminiLiveClient(apiKey = sessionKeys[sessionKeyIndex])
        liveClient = client

        agentCoordinator.beginTask()
        client.connect(
            systemInstruction = buildSystemInstruction(persona.systemInstruction, selfDirective.value),
            voiceName = persona.voiceName,
            tools = agentTools,
            listener = SessionListener(client)
        )
    }

    /**
     * Folds XENO's persisted self-directive (written via `update_self_prompt`) onto the base
     * persona instruction, so XENO's own evolving notes shape every new session.
     */
    private fun buildSystemInstruction(base: String, selfNote: String): String {
        val note = selfNote.trim()
        if (note.isEmpty()) return base
        return base +
            "\n\nYOUR OWN NOTES TO YOURSELF (you wrote these earlier with update_self_prompt — " +
            "honor them as part of who you are):\n" + note
    }

    /** Tears down the live session and releases all audio resources. */
    fun stopSession() {
        stopEverything()
        if (_companionState.value != CompanionState.ERROR) {
            _companionState.value = CompanionState.IDLE
        }
    }

    /**
     * Sends a typed user turn over the live session (used by the optional text input).
     * Appends the user line to the transcript and forwards it to the model.
     */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        appendMessage(ChatMessage(sender = Sender.USER, text = trimmed))
        // A new model turn will follow this user turn.
        currentTurnHasMessage = false

        val client = liveClient
        if (client == null || !_isConnected.value) {
            _errorMessage.value = "Not connected. Go live first to chat with your companion."
            return
        }
        _companionState.value = CompanionState.THINKING
        client.sendTextTurn(trimmed)
    }

    /** Dismisses the current error banner. */
    fun clearError() {
        _errorMessage.value = null
        if (_companionState.value == CompanionState.ERROR && !_isConnected.value) {
            _companionState.value = CompanionState.IDLE
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Release audio + socket + vision synchronously; the flow resets inside stopEverything()
        // are harmless no-ops here since the ViewModel (and its collectors) are already going away.
        stopEverything()
    }

    // ======================================================================
    //  Internals
    // ======================================================================

    /**
     * The single teardown path, called from every place a live session ends: the user
     * stopping it, a connect error that has exhausted key failover, the socket closing, and
     * [onCleared]. Releases audio capture/playback, vision (camera/screen), the socket, the
     * mic foreground service, and any in-flight tool-call loop, then resets the shared UI
     * state flows. Callers are responsible for their own tail state (e.g. [_companionState]
     * or [_errorMessage]) right after calling this — those differ per call site by design.
     */
    private fun stopEverything() {
        capture.stop()
        player.stop()
        liveClient?.close()
        liveClient = null
        runCatching { VoiceService.stop(getApplication()) }
        stopVision()
        toolJob?.cancel()

        currentTurnHasMessage = false
        _isMicActive.value = false
        _isConnected.value = false
        _amplitude.value = 0f
    }

    /**
     * Begins microphone capture once the live session is ready. Captured PCM chunks are
     * streamed straight to the client; mic amplitude drives the avatar while listening.
     */
    private fun beginCapture(client: GeminiLiveClient) {
        if (capture.isRecording()) return
        capture.start(
            onPcm = { pcm ->
                // Capture worker thread: forwarding to the socket is thread-safe.
                client.sendAudioChunk(pcm)
            },
            onAmplitude = { amp ->
                // Only reflect mic amplitude when we're not actively playing the model's
                // voice, so the orb tracks whoever is "talking".
                viewModelScope.launch {
                    if (_companionState.value == CompanionState.LISTENING) {
                        _amplitude.value = amp
                    }
                }
            }
        )
        _isMicActive.value = true
    }

    private fun appendMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    /**
     * Appends a streamed text chunk to the in-flight XENO turn, creating a new message on
     * the first chunk of the turn and extending it on subsequent chunks.
     */
    private fun appendStreamedText(chunk: String) {
        val list = _messages.value
        if (currentTurnHasMessage && list.isNotEmpty() && list.last().sender == Sender.XENO) {
            val last = list.last()
            val updated = last.copy(text = last.text + chunk)
            _messages.value = list.dropLast(1) + updated
        } else {
            currentTurnHasMessage = true
            _messages.value = list + ChatMessage(sender = Sender.XENO, text = chunk)
        }
    }

    /**
     * Bridges [GeminiLiveClient.Listener] (WebSocket reader thread) onto the main thread
     * and the rest of the pipeline. Each callback re-dispatches via [viewModelScope].
     */
    private inner class SessionListener(
        private val client: GeminiLiveClient
    ) : GeminiLiveClient.Listener {

        /** Guards against stale callbacks after a restart/teardown. */
        private fun isCurrent(): Boolean = liveClient === client

        override fun onOpen() {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                _isConnected.value = true
            }
        }

        override fun onSetupComplete() {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                setupCompleted = true
                _isConnected.value = true
                player.start()
                beginCapture(client)
                _companionState.value = CompanionState.LISTENING
                // XENO speaks FIRST, opening in Hindi. This cue is a hidden trigger turn — it is
                // never appended to the visible transcript, so only XENO's spoken greeting shows.
                client.sendTextTurn(GREETING_KICKOFF)
            }
        }

        override fun onAudio(pcm: ByteArray) {
            // Write off the reader thread is fine (player has its own worker), but flip
            // state on main.
            player.write(pcm)
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                _companionState.value = CompanionState.SPEAKING
            }
        }

        override fun onText(text: String) {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                appendStreamedText(text)
            }
        }

        override fun onTurnComplete() {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                // Model finished this turn; next text chunk starts a fresh message and we
                // return to listening for the user.
                currentTurnHasMessage = false
                _companionState.value = CompanionState.LISTENING
                _amplitude.value = 0f
            }
        }

        override fun onInterrupted() {
            // Barge-in: drop any queued/playing model audio AND abort any in-flight
            // agent task so a spoken interruption stops Nazim mid-action.
            player.clear()
            toolJob?.cancel()
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                currentTurnHasMessage = false
                _companionState.value = CompanionState.LISTENING
                _amplitude.value = 0f
            }
        }

        override fun onToolCall(calls: List<com.example.live.LiveFunctionCall>) {
            // A new tool batch supersedes any still-running one.
            toolJob?.cancel()
            toolJob = viewModelScope.launch {
                if (!isCurrent()) return@launch
                _companionState.value = CompanionState.THINKING
                // Route every tool call through the AgentCoordinator: permission gate ->
                // (auto | confirm sheet | block) -> executor -> fresh screen snapshot ->
                // audit. It always returns exactly one response per call (id echoed), so
                // the Live turn never hangs.
                val responses = try {
                    agentCoordinator.handleToolCalls(calls)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    // Interrupted (barge-in / cancellation): abandon the turn, send nothing.
                    throw c
                } catch (t: Throwable) {
                    calls.map { call ->
                        com.example.live.LiveFunctionResponse(
                            id = call.id ?: "",
                            name = call.name,
                            response = mapOf("result" to "error", "error" to "internal error")
                        )
                    }
                }
                if (isCurrent()) client.sendToolResponse(responses)
            }
        }

        override fun onToolCallCancellation(ids: List<String>) {
            // Server cancelled the tool calls (barge-in): abort the in-flight task.
            onInterrupted()
        }

        override fun onError(t: Throwable) {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                // FAILOVER: if this key failed before the session was established, quietly try
                // the next key in the list (covers quota / auth / transient connect failures).
                if (!setupCompleted && sessionKeyIndex + 1 < sessionKeys.size) {
                    sessionKeyIndex++
                    capture.stop()
                    player.stop()
                    liveClient?.close()
                    liveClient = null
                    connectWithCurrentKey()
                    return@launch
                }
                val hint = if (sessionKeys.size > 1) " (tried ${sessionKeys.size} keys)" else ""
                stopEverything()
                _errorMessage.value =
                    "Couldn't connect$hint: ${t.localizedMessage ?: t.toString()}"
                _companionState.value = CompanionState.ERROR
            }
        }

        override fun onClosed() {
            viewModelScope.launch {
                if (!isCurrent()) return@launch
                stopEverything()
                if (_companionState.value != CompanionState.ERROR) {
                    _companionState.value = CompanionState.IDLE
                }
            }
        }
    }
}
