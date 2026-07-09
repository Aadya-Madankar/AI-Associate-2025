package com.example.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.character.NazimPersona
import com.example.character.NazimState
import com.example.character.NazimView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Makes XENO leave the app and **roam your whole Android screen as the real 3D avatar** — the
 * exact `assets/nazim.glb` character that lives inside the app, not an abstract dot.
 *
 * A foreground service that hosts the live [NazimView] renderer inside a [ComposeView] placed in a
 * `TYPE_APPLICATION_OVERLAY` window, so the avatar sits on top of every app. The window is
 * transparent, draggable, and (the magic) collects [OverlayBus.taps] from the accessibility control
 * engine: each time XENO taps the screen, the little 3D person **walks or runs** to that exact spot,
 * faces the right way, and plays a "hit / press" (Punch) animation as the tap lands — so you watch a
 * person operate your phone. When it is not walking it mirrors the live session's expression and
 * lip-syncs to XENO's voice via [OverlayBus.conversationState] / [OverlayBus.amplitude].
 *
 * Hosting Compose outside an Activity requires this service to act as the [LifecycleOwner],
 * [ViewModelStoreOwner] and [SavedStateRegistryOwner] for the window's view tree. Needs the
 * "Display over other apps" grant. The live session is kept alive in the background by the
 * microphone foreground service (see `XenoViewModel`), so XENO keeps hearing you while it roams.
 */
class XenoOverlayService : Service(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    // --- Compose hosting owners ---------------------------------------------------------
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // --- Window ----------------------------------------------------------------------------
    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var sizePx = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- Avatar drive (collected by the Compose content) -----------------------------------
    /** The clip the avatar plays right now: walk/run while travelling, else the live state. */
    private val effectiveState = MutableStateFlow(NazimState.IDLE)
    /** Lip-sync amplitude, mirrored from the live session. */
    private val amplitude = MutableStateFlow(0f)
    /** Horizontal flip so the avatar faces its direction of travel. */
    private val facingLeft = MutableStateFlow(false)

    // --- Motion ----------------------------------------------------------------------------
    private var moveAnimator: ValueAnimator? = null
    private var settleJob: Job? = null
    private var moving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sizePx = (168 * resources.displayMetrics.density).toInt()

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@XenoOverlayService)
            setViewTreeViewModelStoreOwner(this@XenoOverlayService)
            setViewTreeSavedStateRegistryOwner(this@XenoOverlayService)
            setContent {
                MyApplicationTheme {
                    RoamingAvatar(
                        stateFlow = effectiveState,
                        amplitudeFlow = amplitude,
                        facingLeftFlow = facingLeft,
                        onDrag = ::onDrag
                    )
                }
            }
        }
        view = composeView

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * resources.displayMetrics.density).toInt()
            y = (260 * resources.displayMetrics.density).toInt()
        }

        try {
            wm.addView(composeView, params)
        } catch (t: Throwable) {
            stopSelf(); return
        }

        // Mirror the live session into the avatar whenever it is not mid-walk.
        scope.launch {
            OverlayBus.conversationState.collect { s ->
                if (!moving && settleJob?.isActive != true) effectiveState.value = s
            }
        }
        scope.launch { OverlayBus.amplitude.collect { amplitude.value = it } }

        // The heart of roaming: walk/run to every spot the control engine taps.
        scope.launch {
            OverlayBus.taps.collect { tap -> walkTo(tap.x, tap.y) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // --- Motion logic ----------------------------------------------------------------------

    /** Animate the window from where the avatar stands to ([tx],[ty]), walking or running. */
    private fun walkTo(tx: Int, ty: Int) {
        val v = view ?: return
        settleJob?.cancel()
        moveAnimator?.cancel()

        val startX = params.x
        val startY = params.y
        val targetX = tx - sizePx / 2
        val targetY = ty - sizePx / 2
        val dx = (targetX - startX).toFloat()
        val dy = (targetY - startY).toFloat()
        val dist = hypot(dx, dy)

        if (dist < 12f) { punchThenSettle(); return }

        facingLeft.value = dx < 0f
        val running = dist > RUN_DISTANCE_PX
        moving = true
        effectiveState.value = if (running) NazimState.RUNNING else NazimState.WALKING

        val speed = if (running) RUN_SPEED else WALK_SPEED // px per ms
        val duration = (dist / speed).toLong().coerceIn(220L, 900L)

        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                params.x = (startX + dx * f).toInt()
                params.y = (startY + dy * f).toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (moving) punchThenSettle()
                }
            })
            start()
        }
    }

    /** On arrival, throw the "press" (Punch) animation, then settle back to the live state. */
    private fun punchThenSettle() {
        moving = false
        effectiveState.value = NazimState.ACTING // resolves to the GLB's Punch clip
        settleJob = scope.launch {
            delay(620)
            effectiveState.value = OverlayBus.conversationState.value
        }
    }

    /** Manual drag: the user can pick the avatar up and move it; cancels any auto-walk. */
    private fun onDrag(dx: Float, dy: Float) {
        val v = view ?: return
        moveAnimator?.cancel()
        settleJob?.cancel()
        moving = false
        params.x += dx.toInt()
        params.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, params) }
    }

    // --- Foreground notification -----------------------------------------------------------

    private fun startInForeground() {
        val ch = "xeno_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(ch) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(ch, "XENO on screen", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification =
            (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch)
            else @Suppress("DEPRECATION") Notification.Builder(this))
                .setContentTitle("XENO is roaming your screen")
                .setContentText("Tap to return to the app")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, notif, 0)
            else -> startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        moveAnimator?.cancel()
        settleJob?.cancel()
        scope.cancel()
        runCatching { view?.let { wm.removeView(it) } }
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 8123

        /** Beyond this travel distance the avatar runs instead of walks. */
        private const val RUN_DISTANCE_PX = 620f
        private const val WALK_SPEED = 0.9f // px / ms
        private const val RUN_SPEED = 2.0f  // px / ms

        fun start(context: Context) {
            val i = Intent(context, XenoOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XenoOverlayService::class.java))
        }
    }
}

/**
 * The roaming avatar surface: the real [NazimView] 3D renderer filling the overlay window, flipped
 * to face its travel direction, with a transparent top layer that owns dragging (and keeps the
 * SceneView's own camera gestures from hijacking touches).
 */
@Composable
private fun RoamingAvatar(
    stateFlow: MutableStateFlow<NazimState>,
    amplitudeFlow: MutableStateFlow<Float>,
    facingLeftFlow: MutableStateFlow<Boolean>,
    onDrag: (Float, Float) -> Unit
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val amp by amplitudeFlow.collectAsStateWithLifecycle()
    val facingLeft by facingLeftFlow.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        NazimView(
            state = state,
            amplitude = amp,
            persona = NazimPersona.nazim,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = if (facingLeft) -1f else 1f }
        )
        // Transparent drag/touch shield on top of the GL surface.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
        )
    }
}
