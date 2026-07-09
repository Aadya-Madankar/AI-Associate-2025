package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.example.accessibility.AccessibilityAvailability
import com.example.character.NazimPersona
import com.example.character.NazimState
import com.example.character.NazimView
import com.example.core.ChatMessage
import com.example.core.CompanionState
import com.example.core.Sender
import com.example.models.Persona
import com.example.permission.AutonomyMode
import com.example.ui.agent.ConfirmSheet
import com.example.ui.agent.KillSwitchOverlay
import com.example.ui.agent.SettingsSheet
import com.example.ui.components.GlassShatter
import com.example.ui.components.PersonaSwitcher
import com.example.ui.theme.XenoWarm
import com.example.viewmodels.XenoViewModel
import com.example.vision.VisionState

/**
 * Xeno Live — the single companion surface, in the **Warm Light** language.
 *
 * A calm cream-and-peach canvas with a faint iridescent shimmer, frosted-white glass chrome,
 * warm-charcoal copy, and an iridescent mic as the one hero affordance — the Apple-Intelligence /
 * Pixel-search aesthetic. XENO (the 3D avatar) sits centered as the presence; everything else is
 * quiet, airy, and subordinate. The whole screen is self-contained light styling so it stays
 * cohesive; the only shared pieces reused are the modal sheets and the panic STOP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(viewModel: XenoViewModel) {
    val persona by viewModel.selectedPersona.collectAsStateWithLifecycle()
    val companionState by viewModel.companionState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val apiKeyMissing by viewModel.apiKeyMissing.collectAsStateWithLifecycle()
    val autonomyMode by viewModel.autonomyMode.collectAsStateWithLifecycle()
    val pendingConfirm by viewModel.pendingConfirm.collectAsStateWithLifecycle()
    val agentStatus by viewModel.agentStatus.collectAsStateWithLifecycle()
    val visionState by viewModel.visionState.collectAsStateWithLifecycle()
    val roamEnabled by viewModel.roamEnabled.collectAsStateWithLifecycle()
    val speakerLoud by viewModel.speakerLoud.collectAsStateWithLifecycle()

    var showPersonaSwitcher by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Glass-shatter burst when XENO breaks out of the app to roam (roam false -> true).
    var shatterKey by remember { mutableStateOf(0) }
    var prevRoam by remember { mutableStateOf(false) }
    LaunchedEffect(roamEnabled) {
        if (roamEnabled && !prevRoam) shatterKey++
        prevRoam = roamEnabled
    }

    // Accessibility ("control access") status, refreshed each time the user returns to the app
    // (e.g. from the system Accessibility settings the chip deep-links to).
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var a11yEnabled by remember { mutableStateOf(AccessibilityAvailability.isServiceEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = AccessibilityAvailability.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1) Warm light canvas + breathing presence glow.
        WarmBackground(
            state = companionState,
            amplitude = amplitude,
            modifier = Modifier.fillMaxSize()
        )

        // 2) XENO — the presence at the top (3D GLB; transparent scene over the warm wash).
        //    While roaming, the character has "left" the app to roam your screen as the floating
        //    overlay, so only ONE character is visible. We move the 3D view OFF-SCREEN rather than
        //    removing it: disposing the SceneView mid-session use-after-frees in libgltfio and
        //    segfaults, so the engine must stay alive (off-screen) until roaming ends.
        NazimView(
            state = NazimState.from(companionState, agentStatus.active),
            amplitude = amplitude,
            persona = persona,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = if (roamEnabled) (-420).dp else 0.dp)
                .fillMaxWidth()
                .padding(top = 36.dp)
                .height(320.dp)
        )

        // 3) Foreground — ONE vertical flow (avatar headroom → prompt → flexible gap →
        //    transcript → status chips → vision → mic → caption). A single Column means
        //    these never overlap regardless of which pieces are visible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Headroom so the copy sits just under XENO, never on top of it.
            Spacer(Modifier.height(348.dp))

            ModeChip(mode = autonomyMode, onCycle = { newMode ->
                viewModel.setAutonomyMode(newMode)
                // Auto/Bypass let XENO act on its own — those need the accessibility grant to
                // tap & type. If it isn't on yet, deep-link the user to grant it right away.
                if ((newMode == AutonomyMode.AUTO || newMode == AutonomyMode.BYPASS) && !a11yEnabled) {
                    runCatching {
                        context.startActivity(
                            AccessibilityAvailability.settingsIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            })
            Spacer(Modifier.height(18.dp))
            Text(
                text = promptFor(companionState),
                color = XenoWarm.TextPrimary,
                textAlign = TextAlign.Center,
                fontSize = 24.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Medium
            )

            // Flexible gap — pushes the transcript + controls to the bottom.
            Spacer(Modifier.weight(1f))

            // Transcript — a quiet band above the controls (only when there's real dialog).
            TranscriptBand(
                messages = messages,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 188.dp)
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 10.dp)
            )

            // Control-access nudge — the accessibility service must be ON for XENO to tap/type/
            // scroll. Opening apps now works regardless, but full control needs this grant.
            AnimatedVisibility(visible = !a11yEnabled) {
                AccessibilityChip(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    runCatching {
                        context.startActivity(
                            AccessibilityAvailability.settingsIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = apiKeyMissing && errorMessage == null) {
                InfoChip(
                    text = "Add a Gemini API key to start — tap the settings icon.",
                    tint = XenoWarm.Warning,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 2 }
            ) {
                ErrorChip(
                    message = errorMessage.orEmpty(),
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            // Live task progress (light).
            AnimatedVisibility(visible = agentStatus.active && agentStatus.currentStep.isNotBlank()) {
                InfoChip(
                    text = agentStatus.currentStep,
                    tint = XenoWarm.TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Vision + roam — small, quiet warm chips.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToggleChip(
                    icon = Icons.Rounded.Videocam,
                    label = "See",
                    selected = visionState == VisionState.CAMERA,
                    onClick = viewModel::toggleCameraVision
                )
                ToggleChip(
                    icon = Icons.Rounded.ScreenShare,
                    label = "Screen",
                    selected = visionState == VisionState.SCREEN,
                    onClick = viewModel::requestScreenShare
                )
                // No manual Roam chip: XENO leaves the app to roam on its own whenever it is
                // operating the phone for you, and returns when the task is done.
            }

            Spacer(Modifier.height(18.dp))

            // The one hero affordance: the iridescent mic.
            WarmMic(
                state = companionState,
                amplitude = amplitude,
                onToggle = viewModel::toggleLive
            )

            Spacer(Modifier.height(14.dp))
            // While live, STOP replaces the caption: one tap halts the agent AND closes the
            // connection. In the flow (not a floating pill), so it never overlaps other content.
            if (isConnected || agentStatus.active) {
                KillSwitchOverlay(visible = true, onStop = viewModel::panicStop)
            } else {
                Text(
                    text = captionFor(companionState, isConnected),
                    color = XenoWarm.TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(26.dp))
        }

        // 4) Top bar (drawn last so its icon buttons stay on top and tappable).
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            XenoGlyph()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassIconButton(
                    if (speakerLoud) Icons.Rounded.VolumeUp else Icons.Rounded.Hearing,
                    if (speakerLoud) "Voice on loud speaker — tap for earpiece"
                    else "Voice on earpiece — tap for loud speaker"
                ) { viewModel.toggleSpeaker() }
                GlassIconButton(Icons.Rounded.Settings, "Settings") { showSettings = true }
                GlassIconButton(Icons.Rounded.People, "Companion") { showPersonaSwitcher = true }
            }
        }

        // 5) Glass shatter — plays over everything when XENO breaks out to roam.
        GlassShatter(trigger = shatterKey, modifier = Modifier.fillMaxSize())

        // -- Modal sheets / overlays (kept) --
        if (showPersonaSwitcher) {
            PersonaSwitcher(
                personas = listOf(NazimPersona.nazim) + Persona.Personas,
                selectedId = persona.id,
                onSelect = { selected ->
                    viewModel.selectPersona(selected)
                    showPersonaSwitcher = false
                },
                onDismiss = { showPersonaSwitcher = false }
            )
        }
        if (showSettings) {
            SettingsSheet(onDismiss = { showSettings = false }, viewModel = viewModel)
        }
        ConfirmSheet(
            request = pendingConfirm,
            onDecision = { scope, allowed -> viewModel.resolveConfirm(allowed, scope) }
        )
    }
}

// =====================================================================================
//  Warm background
// =====================================================================================

@Composable
private fun WarmBackground(
    state: CompanionState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "warmbg")
    val breath by inf.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath"
    )
    val intensity = when (state) {
        CompanionState.SPEAKING -> 1.15f
        CompanionState.LISTENING -> 0.95f
        CompanionState.THINKING, CompanionState.CONNECTING -> 0.85f
        CompanionState.ERROR -> 0.6f
        CompanionState.IDLE -> 0.72f
    } + amplitude * 0.3f

    Canvas(modifier = modifier) {
        // Base warm wash, top-left → bottom-right.
        drawRect(
            Brush.linearGradient(
                colors = XenoWarm.Canvas,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
        // Warm presence glow behind XENO.
        val wc = Offset(size.width * 0.5f, size.height * 0.40f)
        val wr = size.minDimension * 0.62f * breath * (1f + amplitude * 0.18f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    XenoWarm.GlowWarm.copy(alpha = 0.55f * intensity.coerceIn(0f, 1.3f)),
                    XenoWarm.GlowWarm.copy(alpha = 0f)
                ),
                center = wc,
                radius = wr
            ),
            radius = wr,
            center = wc
        )
        // Cool iridescent shimmer, lower-left.
        val cc = Offset(size.width * 0.18f, size.height * 0.82f)
        val cr = size.minDimension * 0.5f * (2f - breath)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(XenoWarm.GlowCool.copy(alpha = 0.30f), XenoWarm.GlowCool.copy(alpha = 0f)),
                center = cc,
                radius = cr
            ),
            radius = cr,
            center = cc
        )
        // Lilac pearl, upper-right.
        val lc = Offset(size.width * 0.86f, size.height * 0.16f)
        val lr = size.minDimension * 0.42f * breath
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(XenoWarm.GlowLilac.copy(alpha = 0.28f), XenoWarm.GlowLilac.copy(alpha = 0f)),
                center = lc,
                radius = lr
            ),
            radius = lr,
            center = lc
        )
    }
}

// =====================================================================================
//  Chrome pieces
// =====================================================================================

/** The XENO mark: a frosted circle with a small hexagon outline. */
@Composable
private fun XenoGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(XenoWarm.Surface.copy(alpha = 0.55f))
            .border(1.dp, XenoWarm.Hairline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val pts = (0 until 6).map { i ->
                val a = Math.toRadians((60.0 * i - 30.0))
                Offset(cx + r * kotlin.math.cos(a).toFloat(), cy + r * kotlin.math.sin(a).toFloat())
            }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color = XenoWarm.TextPrimary.copy(alpha = 0.78f), style = Stroke(width = 2.2f))
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(XenoWarm.Surface.copy(alpha = 0.55f))
            .border(1.dp, XenoWarm.Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = XenoWarm.TextPrimary.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
    }
}

/** Frosted pill showing the autonomy mode; tap to cycle Ask → Ask-less → Auto → Bypass. */
@Composable
private fun ModeChip(mode: AutonomyMode, onCycle: (AutonomyMode) -> Unit) {
    val (label, dot) = modeMeta(mode)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(XenoWarm.Surface.copy(alpha = 0.6f))
            .border(1.dp, XenoWarm.Hairline, CircleShape)
            .clickable { onCycle(nextMode(mode)) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, color = XenoWarm.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) XenoWarm.TextPrimary.copy(alpha = 0.9f) else XenoWarm.Surface.copy(alpha = 0.55f)
    val fg = if (selected) XenoWarm.TextOnDark else XenoWarm.TextSecondary
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, XenoWarm.Hairline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccessibilityChip(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.7f))
            .border(1.dp, XenoWarm.DotBypass.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.AccessibilityNew, null, tint = XenoWarm.DotBypass, modifier = Modifier.size(18.dp))
        Text(
            "Turn on control access so XENO can tap & type — tap here",
            color = XenoWarm.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoChip(text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.66f))
            .border(1.dp, XenoWarm.Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = tint, fontSize = 13.sp)
    }
}

@Composable
private fun ErrorChip(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XenoWarm.Surface.copy(alpha = 0.82f))
            .border(1.dp, XenoWarm.Error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, color = XenoWarm.Error, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = XenoWarm.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A light, fading band of the most recent dialog lines (kept minimal). */
@Composable
private fun TranscriptBand(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val recent = messages.takeLast(4)
    if (recent.isEmpty()) {
        Spacer(modifier)
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        recent.forEachIndexed { i, m ->
            val fade = 0.4f + 0.6f * (i + 1f) / recent.size
            val isUser = m.sender == Sender.USER
            Text(
                text = m.text,
                color = (if (isUser) XenoWarm.TextSecondary else XenoWarm.TextPrimary).copy(alpha = fade),
                fontSize = if (isUser) 14.sp else 15.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (isUser) FontWeight.Normal else FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            )
        }
    }
}

// =====================================================================================
//  The iridescent mic — the one hero affordance
// =====================================================================================

@Composable
private fun WarmMic(
    state: CompanionState,
    amplitude: Float,
    onToggle: () -> Unit
) {
    val live = state != CompanionState.IDLE && state != CompanionState.ERROR
    val inf = rememberInfiniteTransition(label = "mic")
    val spin by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by inf.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    val ringScale = (if (live) pulse else 1f) * (1f + amplitude * 0.12f) * pressScale

    Box(
        modifier = Modifier
            .size(108.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        // Iridescent sweep ring.
        Canvas(
            modifier = Modifier
                .size(108.dp)
                .scale(ringScale)
                .rotate(spin)
        ) {
            val stroke = if (live) 9f else 6f
            drawCircle(
                brush = Brush.sweepGradient(XenoWarm.Iridescent + XenoWarm.Iris1),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(width = stroke)
            )
        }
        // Soft outer glow when live.
        if (live) {
            Canvas(Modifier.size(108.dp).scale(ringScale)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(XenoWarm.Iris3.copy(alpha = 0.22f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        // Frosted white center with the mic glyph.
        Box(
            modifier = Modifier
                .size(74.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(XenoWarm.Surface.copy(alpha = 0.92f))
                .border(1.dp, XenoWarm.Hairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = "Toggle live",
                tint = if (live) XenoWarm.Iris3 else XenoWarm.TextPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

// =====================================================================================
//  Copy + mode helpers
// =====================================================================================

private fun promptFor(state: CompanionState): String = when (state) {
    CompanionState.IDLE -> "Tap to talk — I can open apps, search, and run your phone."
    CompanionState.CONNECTING -> "Connecting…"
    CompanionState.LISTENING -> "I'm listening…"
    CompanionState.THINKING -> "On it…"
    CompanionState.SPEAKING -> ""
    CompanionState.ERROR -> "Something went wrong — tap the mic to retry."
}

private fun captionFor(state: CompanionState, connected: Boolean): String = when {
    state == CompanionState.LISTENING -> "Listening — tap to end"
    state == CompanionState.SPEAKING -> "Speaking — tap to end"
    state == CompanionState.THINKING -> "Thinking…"
    connected -> "Connected — tap to end"
    else -> "Tap to go live"
}

private fun modeMeta(mode: AutonomyMode): Pair<String, Color> = when (mode) {
    AutonomyMode.ASK -> "Ask" to XenoWarm.DotAsk
    AutonomyMode.ASK_LESS -> "Ask less" to XenoWarm.DotAskLess
    AutonomyMode.AUTO -> "Auto" to XenoWarm.DotAuto
    AutonomyMode.BYPASS -> "Bypass" to XenoWarm.DotBypass
}

/** Cycle through the four user-facing modes. */
private fun nextMode(mode: AutonomyMode): AutonomyMode {
    val order = listOf(AutonomyMode.ASK, AutonomyMode.ASK_LESS, AutonomyMode.AUTO, AutonomyMode.BYPASS)
    val i = order.indexOf(mode)
    return order[(i + 1).mod(order.size)]
}
