package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.example.audio.AudioCapture
import com.example.di.ServiceLocator
import com.example.permission.KillReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service (`foregroundServiceType=microphone`) that owns the **single**
 * [AudioCapture] for the whole app and brokers the hands-free voice pipeline
 * (ARCHITECTURE.md §3.5).
 *
 * Responsibilities:
 *  - Own the one mic instance. Callers never construct [AudioCapture] themselves; they bind
 *    here and observe/route the frames this service publishes.
 *  - Hold the authoritative [VoiceMode] via a [VoiceModeController], exposed as [mode].
 *  - Feed every captured PCM frame to a pluggable [WakeWordGate]; when it fires (while IDLE)
 *    invoke [onWake] so the host can open a Live session.
 *  - Republish each frame to a host-supplied [pcmListener] (the WebSocket sender) and the
 *    latest amplitude (for the avatar / VU meter) regardless of mode.
 *  - Post an ongoing notification with a one-tap **STOP** action that trips the shared
 *    [com.example.permission.KillSwitch] and stops the service. The notification channel is
 *    created here.
 *
 * ### Why a started + bound service
 * A mic foreground service **cannot be started from the background** (`RECORD_AUDIO` is
 * while-in-use). The host Activity therefore [start]s it from the foreground via
 * [ContextCompat.startForegroundService] equivalent, then [bind]s for the control surface.
 * The service keeps running (started) so capture survives the Activity, and the binder
 * ([LocalBinder]) hands back this instance for direct, in-process control.
 *
 * ### Threading
 * [AudioCapture] delivers `onPcm`/`onAmplitude` on its own daemon thread. The wake gate runs
 * inline on that thread (it must stay cheap). Host listeners are invoked on that same thread;
 * a host that needs the main thread must hop itself. Mode/amplitude are [StateFlow]s, safe to
 * collect from anywhere.
 *
 * Manifest registration and the `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` /
 * `POST_NOTIFICATIONS` permissions are added by the integration layer; this class only needs
 * to compile against the SDK.
 */
class VoiceService : Service() {

    private val binder = LocalBinder()

    /** The single mic instance for the whole process. */
    private val audioCapture = AudioCapture()

    /** Authoritative voice-mode state machine, exposed via [mode]. */
    private val modeController = VoiceModeController()

    /** Swappable wake detector; an energy stub until a keyword model is wired in. */
    private val wakeGate: WakeWordGate = EnergyWakeWordGate()

    private val _amplitude = MutableStateFlow(0f)

    // --- Host-supplied routing (set via the binder) -------------------------------------

    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null
    @Volatile private var onWake: (() -> Unit)? = null

    /** Removes the kill-switch STOP listener registered in [onCreate]. */
    private var killSwitchUnsubscribe: (() -> Unit)? = null

    // --- Public observable surface ------------------------------------------------------

    /** The active [VoiceMode]; IDLE until a session is started. */
    val mode: StateFlow<VoiceMode> get() = modeController.mode

    /** Latest normalized RMS amplitude (0..1) from the mic, for the avatar / meter. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** True while the mic is actively capturing. */
    val isCapturing: Boolean get() = audioCapture.isRecording()

    // --- Service lifecycle --------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // STOP / panic from any source flips the kill switch; mirror it into our state by
        // tearing the mic and session down and dropping to IDLE.
        if (ServiceLocator.isInitialized) {
            killSwitchUnsubscribe = ServiceLocator.killSwitch.addListener { stopEverything() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The notification STOP button: trip the panic latch (reverts autonomy to ASK
                // and halts in-flight accessibility-driven automation via the DI-wired
                // callback), then stop the service.
                //
                // The latch is an in-process, app-context-independent singleton, so STOP must
                // ALWAYS trip it — never silently degrade to a mic-only teardown that leaves
                // agent automation running and autonomy un-reverted. If the locator was not yet
                // initialized, force a cheap, side-effect-free init so the shared KillSwitch is
                // obtainable; only fall back to a local teardown if it genuinely cannot be.
                if (!ServiceLocator.isInitialized) {
                    ServiceLocator.init(application)
                }
                if (ServiceLocator.isInitialized) {
                    ServiceLocator.killSwitch.trigger(KillReason.USER_STOP)
                } else {
                    stopEverything()
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Promote to foreground immediately so the OS does not kill us for holding the mic.
        startForegroundCompat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        killSwitchUnsubscribe?.invoke()
        killSwitchUnsubscribe = null
        stopEverything()
        super.onDestroy()
    }

    // --- Control API (called through the binder) ----------------------------------------

    /**
     * Begin (or resume) microphone capture and promote to the foreground. Idempotent: if the
     * mic is already running this only refreshes the notification.
     *
     * @param mode the mode to enter; defaults to [VoiceMode.CONVERSATION] (the user explicitly
     *             went live). Pass [VoiceMode.IDLE] to capture only for wake-word detection.
     */
    fun startVoice(mode: VoiceMode = VoiceMode.CONVERSATION) {
        applyMode(mode)
        startForegroundCompat()
        if (!audioCapture.isRecording()) {
            wakeGate.reset()
            audioCapture.start(
                onPcm = ::onPcmFrame,
                onAmplitude = { amp -> _amplitude.value = amp }
            )
        }
        refreshNotification()
    }

    /** Switch to [VoiceMode.CONVERSATION] (duplex Live chat), starting the mic if needed. */
    fun startConversation() = startVoice(VoiceMode.CONVERSATION)

    /** Switch to [VoiceMode.COMMAND] (a hands-free task), starting the mic if needed. */
    fun startCommand() {
        modeController.startCommand()
        startForegroundCompat()
        if (!audioCapture.isRecording()) {
            audioCapture.start(
                onPcm = ::onPcmFrame,
                onAmplitude = { amp -> _amplitude.value = amp }
            )
        }
        refreshNotification()
    }

    /** Finish a command, returning to whatever mode preceded it. Mic stays open. */
    fun endCommand() {
        modeController.endCommand()
        refreshNotification()
    }

    /**
     * Stop capture, drop to [VoiceMode.IDLE], and leave the foreground. Keeps the service
     * alive (still bound) so it can be re-armed without a fresh start; use [stopSelf] / unbind
     * to tear it down entirely.
     */
    fun stopVoice() {
        stopEverything()
    }

    /** Register the sink that forwards mic frames to the Live WebSocket. `null` to detach. */
    fun setPcmListener(listener: ((ByteArray) -> Unit)?) {
        pcmListener = listener
    }

    /**
     * Register the callback fired (on the capture thread) when the wake gate detects intent to
     * talk while IDLE. The host typically opens a session and calls [startConversation].
     */
    fun setOnWake(callback: (() -> Unit)?) {
        onWake = callback
    }

    // --- Internals ----------------------------------------------------------------------

    private fun onPcmFrame(pcm: ByteArray) {
        val amp = _amplitude.value
        // While IDLE, only the wake gate consumes audio (the Live socket is closed).
        if (modeController.current == VoiceMode.IDLE) {
            if (wakeGate.accept(pcm, amp)) {
                onWake?.invoke()
            }
            return
        }
        // CONVERSATION / COMMAND: stream frames to the host (WebSocket sender).
        pcmListener?.invoke(pcm)
    }

    private fun applyMode(mode: VoiceMode) {
        when (mode) {
            VoiceMode.IDLE -> modeController.goIdle()
            VoiceMode.CONVERSATION -> modeController.startConversation()
            VoiceMode.COMMAND -> modeController.startCommand()
        }
    }

    private fun stopEverything() {
        audioCapture.stop()
        _amplitude.value = 0f
        wakeGate.reset()
        modeController.goIdle()
        stopForegroundCompat()
    }

    // --- Notification -------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, VoiceService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val stopPending = PendingIntent.getService(this, REQ_STOP, stopIntent, flags)

        val text = when (modeController.current) {
            VoiceMode.COMMAND -> "Running a hands-free task"
            VoiceMode.CONVERSATION -> "Listening"
            VoiceMode.IDLE -> "Waiting for \"${wakeGate.keyword}\""
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Nazim is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "STOP",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Refresh the ongoing notification in place (e.g. on a mode change) without re-promoting. */
    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** In-process binder handing callers this [VoiceService] for direct control. */
    inner class LocalBinder : Binder() {
        val service: VoiceService get() = this@VoiceService
    }

    companion object {
        /** Notification action: user tapped STOP — trip the kill switch and stop the service. */
        const val ACTION_STOP = "com.example.voice.action.STOP"

        private const val CHANNEL_ID = "xeno_voice_service"
        private const val CHANNEL_NAME = "Voice & hands-free control"
        private const val CHANNEL_DESC =
            "Shows when Nazim is listening or running a hands-free task, with a STOP control."

        private const val NOTIFICATION_ID = 4201
        private const val REQ_STOP = 1

        /**
         * Start the service in the foreground so it may hold the mic. MUST be called from a
         * visible Activity (a mic FGS cannot start from the background). Bind afterward for the
         * control surface.
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the service entirely (releases the mic and clears the notification). */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
