package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.di.ServiceLocator
import com.example.permission.KillReason

/**
 * Minimal foreground service (`foregroundServiceType=microphone`) whose only job is to hold a
 * mic-type foreground-service notification so Android does not kill the app's mic capture when
 * it is minimized during a hands-free session.
 *
 * The actual microphone (`AudioCapture`) and the Gemini Live WebSocket are owned by
 * `XenoViewModel`, not this service — this class never touches audio itself. It is started when
 * a session begins and stopped when it ends; the notification's STOP action trips the shared
 * kill switch the same way any other panic source does.
 */
class VoiceService : Service() {

    /** Removes the kill-switch STOP listener registered in [onCreate]. */
    private var killSwitchUnsubscribe: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // STOP / panic from any source flips the kill switch; mirror it by stopping ourselves.
        if (ServiceLocator.isInitialized) {
            killSwitchUnsubscribe = ServiceLocator.killSwitch.addListener { stopForegroundCompat() }
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
                // ALWAYS trip it — never silently degrade to a no-op that leaves agent
                // automation running and autonomy un-reverted. If the locator was not yet
                // initialized, force a cheap, side-effect-free init so the shared KillSwitch is
                // obtainable; only fall back to a local teardown if it genuinely cannot be.
                if (!ServiceLocator.isInitialized) {
                    ServiceLocator.init(application)
                }
                if (ServiceLocator.isInitialized) {
                    ServiceLocator.killSwitch.trigger(KillReason.USER_STOP)
                } else {
                    stopForegroundCompat()
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Promote to foreground immediately so the OS does not kill us for holding the mic.
        startForegroundCompat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        killSwitchUnsubscribe?.invoke()
        killSwitchUnsubscribe = null
        stopForegroundCompat()
        super.onDestroy()
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

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Nazim is active")
            .setContentText("Xeno is listening — tap to stop")
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

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
         * Start the service in the foreground so it may hold the mic notification. MUST be
         * called from a visible Activity (a mic FGS cannot start from the background).
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the service entirely (releases the notification). */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
