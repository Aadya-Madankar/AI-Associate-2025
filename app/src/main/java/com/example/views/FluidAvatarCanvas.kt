package com.example.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.models.Persona
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

enum class CompanionState {
    IDLE, LISTENING, THINKING, SPEAKING
}

@Composable
fun FluidAvatarCanvas(
    state: CompanionState,
    persona: Persona,
    voiceAmplitude: Float, // ranges from 0.0f to 1.0f
    isSilhouetteMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_infinite")
    
    // Core mechanical breathing cycle (bobbing and pulsing)
    val breathing by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Rotational/oscillational angles for gears, sweeping radars, and circuit lines
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831853f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Interactive responsive touch tracking with magnetic elastic drag spring
    var rawTouchOffset by remember { mutableStateOf(Offset.Zero) }
    
    // Smooth responsive animation of touch offset to create a mechanical parallax sway
    val smoothedTouchX by animateFloatAsState(
        targetValue = rawTouchOffset.x,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "touch_spring_x"
    )
    val smoothedTouchY by animateFloatAsState(
        targetValue = rawTouchOffset.y,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "touch_spring_y"
    )
    val smoothedTouchOffset = Offset(smoothedTouchX, smoothedTouchY)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val current = rawTouchOffset + dragAmount
                        val distance = current.getDistance()
                        // Keep a firm rubber-band constraint
                        val maxAllowed = 180f
                        rawTouchOffset = if (distance > maxAllowed) {
                            current * (maxAllowed / distance)
                        } else {
                            current
                        }
                    },
                    onDragEnd = { rawTouchOffset = Offset.Zero },
                    onDragCancel = { rawTouchOffset = Offset.Zero }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dDensity = density
            val surfaceWidth = size.width
            val surfaceHeight = size.height
            
            // Draw Brushed Steel Skeuomorphic Dashboard Panel Backing
            drawDashboardBacking(surfaceWidth, surfaceHeight)

            // Let's draw an inset Physical CRT Computer Terminal Window Frame in the center
            val terminalLeft = surfaceWidth * 0.08f
            val terminalTop = surfaceHeight * 0.08f
            val terminalWidth = surfaceWidth * 0.84f
            val terminalHeight = surfaceHeight * 0.76f
            val terminalRect = Rect(terminalLeft, terminalTop, terminalLeft + terminalWidth, terminalTop + terminalHeight)
            
            // 1. Shadow background behind screen (Depth layer)
            drawRect(
                color = Color(0xFF0C0E14),
                topLeft = Offset(terminalLeft, terminalTop),
                size = Size(terminalWidth, terminalHeight)
            )

            // 2. Tactile Debossed Inset Border surrounding the terminal glass screen
            drawBevelBorder(
                rect = terminalRect,
                highColor = Color.White.copy(alpha = 0.15f),
                shadowColor = Color.Black.copy(alpha = 0.8f),
                width = 3f * dDensity,
                inset = true
            )

            // Draw Phosphor Screen Ambient Glowing Radiation and Scanlines on the glass screen
            drawPhosphorScreen(terminalRect, persona, state)

            // Draw Rivets Screws around the dashboard corners to lock the metal plates
            drawDashboardScrews(surfaceWidth, surfaceHeight, dDensity)

            // Determine Center point of the mechanical head which sways based on touch drags
            val headCenter = Offset(surfaceWidth / 2f, surfaceHeight / 2f) + smoothedTouchOffset

            // Base radius for head scaling
            val baseRadius = size.width.coerceAtMost(size.height) * 0.18f
            
            // Draw mechanical neck collar support joint (Swayed inversely for multi-layered depth)
            drawMetalNeck(headCenter, smoothedTouchOffset, baseRadius, persona, dDensity)

            // Responsive scale driven by state and breathing
            val liveScale = when (state) {
                CompanionState.IDLE -> breathing
                CompanionState.LISTENING -> 1.05f + voiceAmplitude * 0.12f
                CompanionState.THINKING -> 1.0f + sin(orbitAngle * 3.1415927f / 180f) * 0.03f
                CompanionState.SPEAKING -> 0.98f + voiceAmplitude * 0.18f
            }
            val radius = baseRadius * liveScale

            // Left/Right side ear antennas that pivot and stretch based on magnetic touch drag
            drawMechanicalEars(headCenter, smoothedTouchOffset, radius, orbitAngle, persona, dDensity)

            // DRAW CHOSEN MECHANICAL CHARACTER PERSONA
            when (persona.id) {
                "aria" -> drawAriaMathematician(headCenter, smoothedTouchOffset, radius, orbitAngle, state, voiceAmplitude, wavePhase, dDensity)
                "maya" -> drawMayaBotanical(headCenter, smoothedTouchOffset, radius, orbitAngle, state, voiceAmplitude, breathing, dDensity)
                "atlas" -> drawAtlasNavigator(headCenter, smoothedTouchOffset, radius, orbitAngle, state, voiceAmplitude, dDensity)
                else -> drawXenoCyberIntelligence(headCenter, smoothedTouchOffset, radius, orbitAngle, state, voiceAmplitude, dDensity)
            }

            // Draw a protective vintage Convex Glass Glare reflection across the CRT screen
            drawGlassGlareOverlay(terminalRect)

            // Draw a detailed Skeuomorphic Analogue Vintage VU meter at the lower-right dashboard compartment
            val vuX = surfaceWidth * 0.72f
            val vuY = surfaceHeight * 0.88f
            val vuWidth = surfaceWidth * 0.20f
            val vuHeight = surfaceHeight * 0.10f
            drawAnalogVUMeter(vuX, vuY, vuWidth, vuHeight, voiceAmplitude, state, dDensity)

            // Draw modular blinking physical LED diagnostic light bulb lamps at the bottom-left compartment
            val ledX = surfaceWidth * 0.12f
            val ledY = surfaceHeight * 0.93f
            drawDiagnosticLEDs(ledX, ledY, state, orbitAngle, dDensity)
        }
    }
}

// ==========================================
// CORE SKEUOMORPHIC DRAWING FUNCTIONS
// ==========================================

private fun DrawScope.drawDashboardBacking(width: Float, height: Float) {
    // Solid background of dark machined composite material
    drawRect(color = Color(0xFF131722))

    // Brushed metal micro-lines across the entire canvas background
    val step = 12f
    val brushedColor = Color(0xFFFFFFFF).copy(alpha = 0.015f)
    for (y in 0 until height.toInt() step step.toInt()) {
        drawLine(
            color = brushedColor,
            start = Offset(0f, y.toFloat()),
            end = Offset(width, y.toFloat()),
            strokeWidth = 1f
        )
    }

    // Heavy bolted seams / ventilation plates on the sides
    drawRect(
        color = Color(0xFF191D2A),
        topLeft = Offset(0f, height * 0.82f),
        size = Size(width, height * 0.18f)
    )
    
    // Bottom panel beveled splitter divider line
    drawLine(
        color = Color.Black.copy(alpha = 0.8f),
        start = Offset(0f, height * 0.82f),
        end = Offset(width, height * 0.82f),
        strokeWidth = 3f
    )
    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = Offset(0f, height * 0.82f + 3f),
        end = Offset(width, height * 0.82f + 3f),
        strokeWidth = 1f
    )
}

private fun DrawScope.drawBevelBorder(
    rect: Rect,
    highColor: Color,
    shadowColor: Color,
    width: Float,
    inset: Boolean = false
) {
    val topL = rect.topLeft
    val botR = rect.bottomRight

    val topLColor = if (inset) shadowColor else highColor
    val botRColor = if (inset) highColor else shadowColor

    // Top edge Bevel
    drawLine(
        color = topLColor,
        start = Offset(topL.x - width, topL.y - width / 2f),
        end = Offset(botR.x + width, topL.y - width / 2f),
        strokeWidth = width
    )
    // Left edge Bevel
    drawLine(
        color = topLColor,
        start = Offset(topL.x - width / 2f, topL.y - width),
        end = Offset(topL.x - width / 2f, botR.y + width),
        strokeWidth = width
    )
    // Bottom edge Bevel
    drawLine(
        color = botRColor,
        start = Offset(topL.x - width, botR.y + width / 2f),
        end = Offset(botR.x + width, botR.y + width / 2f),
        strokeWidth = width
    )
    // Right edge Bevel
    drawLine(
        color = botRColor,
        start = Offset(botR.x + width / 2f, topL.y - width),
        end = Offset(botR.x + width / 2f, botR.y + width),
        strokeWidth = width
    )
}

private fun DrawScope.drawPhosphorScreen(
    rect: Rect,
    persona: Persona,
    state: CompanionState
) {
    val center = rect.center
    val pColor = persona.primaryColors[0]
    
    // Screen base back-ambient glow matching the active persona's color frequency
    val radialAura = Brush.radialGradient(
        colors = listOf(
            pColor.copy(alpha = if (state == CompanionState.IDLE) 0.08f else 0.18f),
            Color.Transparent
        ),
        center = center,
        radius = rect.width * 0.65f
    )
    drawRect(
        brush = radialAura,
        topLeft = rect.topLeft,
        size = rect.size
    )

    // Parallel CRT phosphor scan lines drawn over the screen
    val scanColor = Color.Black.copy(alpha = 0.22f)
    val spacing = 8f
    for (y in rect.top.toInt() until rect.bottom.toInt() step spacing.toInt()) {
        drawLine(
            color = scanColor,
            start = Offset(rect.left, y.toFloat()),
            end = Offset(rect.right, y.toFloat()),
            strokeWidth = 2.5f
        )
    }

    // Grid matrix backing lines (creates technical sci-fi blueprint depth)
    val gridColor = pColor.copy(alpha = 0.02f)
    val gridW = 50f
    for (x in rect.left.toInt() until rect.right.toInt() step gridW.toInt()) {
        drawLine(
            color = gridColor,
            start = Offset(x.toFloat(), rect.top),
            end = Offset(x.toFloat(), rect.bottom),
            strokeWidth = 1f
        )
    }
}

private fun DrawScope.drawGlassGlareOverlay(rect: Rect) {
    // Glass surface glare sweep reflection
    val glareGrad = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.15f),
            Color.White.copy(alpha = 0.05f),
            Color.Transparent,
            Color.White.copy(alpha = 0.02f)
        ),
        start = rect.topLeft,
        end = Offset(rect.right * 0.7f, rect.bottom)
    )
    drawRect(
        brush = glareGrad,
        topLeft = rect.topLeft,
        size = rect.size
    )

    // Subtle edge highlight around screen boundary mimicking physical optical refraction
    drawRect(
        color = Color.White.copy(alpha = 0.05f),
        topLeft = Offset(rect.left + 1f, rect.top + 1f),
        size = Size(rect.width - 2f, rect.height - 2f),
        style = Stroke(width = 1.5f)
    )
}

private fun DrawScope.drawDashboardScrews(width: Float, height: Float, dDensity: Float) {
    val sizePx = 7f * dDensity
    val marginX = width * 0.04f
    val marginY = height * 0.04f

    val positions = listOf(
        Offset(marginX, marginY),
        Offset(width - marginX, marginY),
        Offset(marginX, height * 0.84f),
        Offset(width - marginX, height * 0.84f),
        Offset(marginX, height - 15f * dDensity),
        Offset(width - marginX, height - 15f * dDensity)
    )

    positions.forEach { pos ->
        // Debossed screw socket basin shadow circle
        drawCircle(
            color = Color(0xFF07090C),
            radius = sizePx * 1.25f,
            center = pos
        )

        // Screw metal body head
        val screwGlow = Brush.radialGradient(
            colors = listOf(Color(0xFF90A4AE), Color(0xFF455A64)),
            center = pos - Offset(1.5f * dDensity, 1.5f * dDensity),
            radius = sizePx
        )
        drawCircle(
            brush = screwGlow,
            radius = sizePx,
            center = pos
        )

        // Screw outer thin bevel shadow and highlight ring
        drawCircle(
            color = Color.White.copy(alpha = 0.3f),
            radius = sizePx,
            center = pos,
            style = Stroke(width = 1f)
        )

        // Flat head slot line with shaded slot depth
        val slotStart = pos - Offset(sizePx * 0.65f, -sizePx * 0.15f)
        val slotEnd = pos + Offset(sizePx * 0.65f, -sizePx * 0.15f)
        
        // Shadow line
        drawLine(
            color = Color.Black.copy(alpha = 0.7f),
            start = slotStart,
            end = slotEnd,
            strokeWidth = 2.5f * dDensity
        )
        // Highlighting edge catch line
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = slotStart + Offset(0f, 1f * dDensity),
            end = slotEnd + Offset(0f, 1f * dDensity),
            strokeWidth = 1f
        )
    }
}

private fun DrawScope.drawMetalNeck(
    headCenter: Offset,
    touchOffset: Offset,
    baseRadius: Float,
    persona: Persona,
    dDensity: Float
) {
    val neckWidth = baseRadius * 0.75f
    val neckHeight = baseRadius * 0.8f
    val neckTop = headCenter.y + baseRadius * 0.65f
    
    // Apply parallax offset: sways slightly slower, sliding the plates inside
    val parallaxOffset = touchOffset * 0.4f
    val neckCenter = Offset(headCenter.x - parallaxOffset.x, neckTop)

    val colors = persona.primaryColors
    val metallicNeckBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF37474F), Color(0xFF90A4AE), Color(0xFF263238)),
        start = Offset(neckCenter.x - neckWidth / 2f, neckTop),
        end = Offset(neckCenter.x + neckWidth / 2f, neckTop)
    )

    // Segment 1: Beveled trapezoidal collar base structure
    val neckPath = Path().apply {
        moveTo(neckCenter.x - neckWidth * 0.5f, neckTop)
        lineTo(neckCenter.x + neckWidth * 0.5f, neckTop)
        lineTo(neckCenter.x + neckWidth * 0.8f, neckTop + neckHeight)
        lineTo(neckCenter.x - neckWidth * 0.8f, neckTop + neckHeight)
        close()
    }
    
    drawPath(
        path = neckPath,
        brush = metallicNeckBrush
    )

    // Neck structural mechanical overlay stripes (hydraulic tubes look)
    drawPath(
        path = neckPath,
        color = Color.Black.copy(alpha = 0.45f),
        style = Stroke(width = 2f * dDensity)
    )

    // Segment 2: Central dark pivot slot (spherical joint)
    drawCircle(
        color = Color(0xFF0C0E14),
        radius = neckWidth * 0.3f,
        center = Offset(neckCenter.x, neckTop + neckHeight * 0.4f)
    )
    
    drawCircle(
        color = colors[0].copy(alpha = 0.4f),
        radius = neckWidth * 0.3f,
        center = Offset(neckCenter.x, neckTop + neckHeight * 0.4f),
        style = Stroke(width = 1.5f * dDensity)
    )
}

private fun DrawScope.drawMechanicalEars(
    headCenter: Offset,
    touchOffset: Offset,
    radius: Float,
    orbitAngle: Float,
    persona: Persona,
    dDensity: Float
) {
    val earOffsetDist = radius * 0.96f
    val stretchX = touchOffset.x * 0.12f
    val stretchY = touchOffset.y * 0.12f

    val colors = persona.primaryColors
    val earLeftCenter = Offset(headCenter.x - earOffsetDist + stretchX, headCenter.y + stretchY * 0.8f)
    val earRightCenter = Offset(headCenter.x + earOffsetDist + stretchX, headCenter.y + stretchY * 0.8f)

    val isRoughAtlas = persona.id == "atlas"
    val isMayaOrganic = persona.id == "maya"

    // Fix: Explicit non-destructured iteration prevents any type mismatches
    val earList = listOf(earLeftCenter to -1f, earRightCenter to 1f)
    for (pair in earList) {
        val earPos = pair.first
        val sideSign = pair.second

        if (isMayaOrganic) {
            // Draw beautiful hand-carved leaves branching as decorative antennas
            val leafMainPath = Path().apply {
                moveTo(earPos.x, earPos.y)
                quadraticTo(
                    earPos.x + sideSign * radius * 0.45f, earPos.y - radius * 0.4f,
                    earPos.x + sideSign * radius * 0.75f, earPos.y - radius * 0.75f
                )
                quadraticTo(
                    earPos.x + sideSign * radius * 0.9f, earPos.y + radius * 0.1f,
                    earPos.x, earPos.y
                )
            }
            drawPath(
                path = leafMainPath,
                color = Color(0xFF388E3C)
            )
            drawPath(
                path = leafMainPath,
                color = Color(0xFFFFF9C4).copy(alpha = 0.35f),
                style = Stroke(width = 1.5f * dDensity)
            )
            // Center leaf stem line
            drawLine(
                color = Color(0xFF1B5E20),
                start = earPos,
                end = Offset(earPos.x + sideSign * radius * 0.75f, earPos.y - radius * 0.75f),
                strokeWidth = 2.5f
            )
        } else if (isRoughAtlas) {
            // Draw heavy machinery copper ears
            val earSize = radius * 0.32f
            drawCircle(
                color = Color(0xFFD84315),
                radius = earSize,
                center = earPos
            )
            drawCircle(
                color = Color(0xFFBF360C),
                radius = earSize * 0.7f,
                center = earPos
            )
            drawCircle(
                color = Color(0xFFFFCC80),
                radius = earSize,
                center = earPos,
                style = Stroke(width = 2.5f)
            )
            // Heavy connector rod anchor
            drawLine(
                color = Color(0xFF4E342E),
                start = Offset(earPos.x - sideSign * earSize, earPos.y),
                end = Offset(earPos.x - sideSign * radius * 0.2f, earPos.y),
                strokeWidth = 8f
            )
        } else {
            // High-tech electronic cyber antennas with blinking tip LEDs
            val stemWidth = 6f * dDensity
            val stemHeight = radius * 0.75f
            
            // Connective support pivot knob
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(Color(0xFF90A4AE), Color(0xFF263238)), center = earPos, radius = stemWidth * 2.2f),
                radius = stemWidth * 2.2f,
                center = earPos
            )

            // Beveled anchor rings
            drawCircle(
                color = colors[0],
                radius = stemWidth * 2.2f,
                center = earPos,
                style = Stroke(width = 1.5f * dDensity)
            )

            // Antenna stem rods sliding out - pure Float math (0.017453292f is rads mapping factor)
            val rawAngleDeg = if (sideSign < 0) 225f else -45f
            val rads = (rawAngleDeg + stretchY * 0.4f) * 0.017453292f
            
            val needleTip = earPos + Offset(cos(rads) * stemHeight, sin(rads) * stemHeight)

            drawLine(
                brush = Brush.linearGradient(colors = listOf(Color(0xFF90A4AE), Color(0xFF263238))),
                start = earPos,
                end = needleTip,
                strokeWidth = stemWidth,
                cap = StrokeCap.Round
            )

            // Blinking physical fluid antenna bead tip
            val blinkAlpha = 0.5f + 0.5f * sin((orbitAngle * 0.12f) + if (sideSign < 0) 0f else 3.1415927f)
            val tipColor = colors[1 % colors.size].copy(alpha = blinkAlpha)
            
            // Soft neon glowing radius underlay
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(tipColor, Color.Transparent), center = needleTip, radius = stemWidth * 3.5f),
                radius = stemWidth * 3.5f,
                center = needleTip
            )

            // Inner solid bulb core
            drawCircle(
                color = Color.White,
                radius = stemWidth * 1.1f,
                center = needleTip
            )
            drawCircle(
                color = colors[1 % colors.size],
                radius = stemWidth * 1.1f,
                center = needleTip,
                style = Stroke(width = 1f)
            )
        }
    }
}

// ------------------------------------------
// PERSONA 1: XENO (CYBER-NEURAL INTELLIGENCE)
// ------------------------------------------
private fun DrawScope.drawXenoCyberIntelligence(
    center: Offset,
    touchOffset: Offset,
    radius: Float,
    orbitAngle: Float,
    state: CompanionState,
    speechAmp: Float,
    dDensity: Float
) {
    // Cyber-Neural Head Shape: Bold Beveled Futuristic Pentagonal Helmet
    val helmetPath = Path().apply {
        moveTo(center.x - radius * 0.85f, center.y - radius * 0.6f)
        lineTo(center.x, center.y - radius * 1.1f) // Pointy Apex dome
        lineTo(center.x + radius * 0.85f, center.y - radius * 0.6f)
        lineTo(center.x + radius * 0.7f, center.y + radius * 0.7f)
        lineTo(center.x - radius * 0.7f, center.y + radius * 0.7f)
        close()
    }

    // Brushed Deep Obsidian Steel Helmet
    val steelBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF2A2E3D), Color(0xFF141722)),
        center = center - Offset(radius * 0.2f, radius * 0.3f),
        radius = radius * 1.4f
    )
    drawPath(path = helmetPath, brush = steelBrush)

    // Outstanding Embossed Outer Edge Highlights (Skeuomorphism)
    drawPath(
        path = helmetPath,
        color = Color(0xE6E040FB).copy(alpha = 0.4f), // Neon Magenta trace line
        style = Stroke(width = 3.5f * dDensity, join = StrokeJoin.Round)
    )

    // Metallic beveled lip borders
    drawPath(
        path = helmetPath,
        color = Color.White.copy(alpha = 0.2f),
        style = Stroke(width = 1f * dDensity)
    )

    // Cyber-Neon Lateral Circuit Plates (Swaying with parallax effect)
    val pOff = touchOffset * 0.25f
    val innerPlate = Rect(
        center.x - radius * 0.65f - pOff.x,
        center.y - radius * 0.45f - pOff.y,
        center.x + radius * 0.65f - pOff.x,
        center.y + radius * 0.5f - pOff.y
    )

    // Central Glass Visor Panel
    val visorPath = Path().apply {
        moveTo(innerPlate.left + radius * 0.1f, innerPlate.top + radius * 0.15f)
        lineTo(innerPlate.right - radius * 0.1f, innerPlate.top + radius * 0.15f)
        lineTo(innerPlate.right, innerPlate.bottom - radius * 0.2f)
        lineTo(innerPlate.left, innerPlate.bottom - radius * 0.2f)
        close()
    }

    val visorGlow = Brush.linearGradient(
        colors = listOf(Color(0xFF4A148C), Color(0xFF006064), Color(0xFF01579B)),
        start = innerPlate.topLeft,
        end = innerPlate.bottomRight
    )
    drawPath(path = visorPath, brush = visorGlow)
    drawPath(path = visorPath, color = Color(0xFF00FFCC).copy(alpha = 0.35f), style = Stroke(width = 2f * dDensity))

    // Visor Inset Shading
    drawPath(
        path = visorPath,
        color = Color.Black.copy(alpha = 0.6f),
        style = Stroke(width = 4f * dDensity, join = StrokeJoin.Bevel)
    )

    // Glowing Cyber-Plates Network inside visor: Neon Radar Eyes
    val eyeCenterY = innerPlate.top + radius * 0.3f
    val leftEyeCenter = Offset(innerPlate.left + radius * 0.35f, eyeCenterY)
    val rightEyeCenter = Offset(innerPlate.right - radius * 0.35f, eyeCenterY)

    listOf(leftEyeCenter, rightEyeCenter).forEachIndexed { i, eyePos ->
        val sweepAngle = (orbitAngle * 1.5f + (i * 180f)) % 360f

        // Solid background retina
        drawCircle(color = Color(0xFF090D1A), radius = radius * 0.18f, center = eyePos)

        // Radar line sweep sweeps visually
        drawArc(
            color = Color(0xFF00FFCC).copy(alpha = 0.5f),
            startAngle = sweepAngle,
            sweepAngle = 45f,
            useCenter = true,
            topLeft = eyePos - Offset(radius * 0.18f, radius * 0.18f),
            size = Size(radius * 0.36f, radius * 0.36f)
        )

        // Outer LED aperture blades
        drawCircle(
            color = Color(0xE6E040FB),
            radius = radius * 0.18f,
            center = eyePos,
            style = Stroke(
                width = 2f * dDensity,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 6f), orbitAngle)
            )
        )

        // Glowing center core bulb
        val eyeBlink = if (abs(sin(orbitAngle * 0.04f)) < 0.12f) 0.15f else 1.0f
        val lensRadius = radius * 0.06f * (1.0f + speechAmp * 0.3f) * eyeBlink
        if (lensRadius > 1.5f) {
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(Color.White, Color(0xFF00FFCC), Color.Transparent), center = eyePos, radius = lensRadius * 2.2f),
                radius = lensRadius * 2.2f,
                center = eyePos
            )
            drawCircle(color = Color.White, radius = lensRadius * 0.7f, center = eyePos)
        }
    }

    // Active Sparking Cathode-Tube Mouth Grille
    val mouthY = innerPlate.bottom - radius * 0.15f
    val mouthW = radius * 0.42f
    val mouthRect = Rect(center.x - mouthW / 2f - pOff.x, mouthY - 14f * dDensity, center.x + mouthW / 2f - pOff.x, mouthY + 14f * dDensity)

    // Draw dark protective grill slots cage background
    drawRoundRect(
        color = Color(0xFF090D14),
        topLeft = mouthRect.topLeft,
        size = mouthRect.size,
        cornerRadius = CornerRadius(6f * dDensity, 6f * dDensity)
    )

    // Skeuomorphic inset bevel borders for mouth cage
    drawBevelBorder(
        rect = mouthRect,
        highColor = Color.White.copy(alpha = 0.15f),
        shadowColor = Color.Black.copy(alpha = 0.7f),
        width = 1.5f * dDensity,
        inset = true
    )

    // Cathode filament rods grid
    val gridCount = 7
    val barW = mouthRect.width / (gridCount + 1)
    for (bar in 1..gridCount) {
        val barX = mouthRect.left + (bar * barW)
        val ampFactor = if (state == CompanionState.SPEAKING || state == CompanionState.LISTENING) speechAmp else 0.15f
        val osc = abs(sin((bar * 1.1f) + orbitAngle * 0.08f))
        val barHeight = mouthRect.height * 0.75f * (0.15f + ampFactor * 0.85f * osc)

        val filamentColor = Color(0xFF00FFCC)
        // Fix: Use correct DrawScope signature parameter named 'cap' instead of 'strokeCap'
        drawLine(
            brush = Brush.linearGradient(colors = listOf(filamentColor.copy(alpha = 0.4f), filamentColor, filamentColor.copy(alpha = 0.4f))),
            start = Offset(barX, mouthRect.center.y - barHeight / 2f),
            end = Offset(barX, mouthRect.center.y + barHeight / 2f),
            strokeWidth = 3f * dDensity,
            cap = StrokeCap.Round
        )

        // Draw hot incandescence center plasma glow core (bright white)
        drawLine(
            color = Color.White,
            start = Offset(barX, mouthRect.center.y - barHeight / 3f),
            end = Offset(barX, mouthRect.center.y + barHeight / 3f),
            strokeWidth = 1f * dDensity,
            cap = StrokeCap.Round
        )

        // Flare mechanical electric sparks at random bounds when talking actively
        if (speechAmp > 0.45f && bar % 3 == 0) {
            val sparkLen = 12f * dDensity
            drawLine(
                color = Color.White,
                start = Offset(barX, mouthRect.center.y),
                end = Offset(barX + (if (bar == 3) -sparkLen else sparkLen), mouthRect.center.y - sparkLen * 0.8f),
                strokeWidth = 1f * dDensity
            )
        }
    }
}

// ------------------------------------------
// PERSONA 2: ARIA (QUANTUM ANALYTICS)
// ------------------------------------------
private fun DrawScope.drawAriaMathematician(
    center: Offset,
    touchOffset: Offset,
    radius: Float,
    orbitAngle: Float,
    state: CompanionState,
    speechAmp: Float,
    wavePhase: Float,
    dDensity: Float
) {
    // Structural Head Frame: Perfect Symmetrical Chrome Frame
    val headRect = Rect(center.x - radius * 0.85f, center.y - radius * 0.95f, center.x + radius * 0.85f, center.y + radius * 0.85f)
    
    // Fill with machine silver block plate
    val chromeBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFCFD8DC), Color(0xFF78909C)),
        center = center - Offset(radius * 0.25f, radius * 0.25f),
        radius = radius * 1.5f
    )
    drawRoundRect(
        brush = chromeBrush,
        topLeft = headRect.topLeft,
        size = headRect.size,
        cornerRadius = CornerRadius(16f * dDensity, 16f * dDensity)
    )

    // Tactile Raised Edge Beveling Highlight (Chrome gloss)
    drawBevelBorder(
        rect = headRect,
        highColor = Color.White,
        shadowColor = Color.Black.copy(alpha = 0.6f),
        width = 3f * dDensity,
        inset = false
    )

    val pOff = touchOffset * 0.2f
    val internalY = center.y - radius * 0.15f

    // Top Header Plate for specs readouts
    val specArea = Rect(headRect.left + 12f * dDensity, headRect.top + 12f * dDensity, headRect.right - 12f * dDensity, headRect.top + radius * 0.32f)
    drawRect(color = Color(0xFF263238), topLeft = specArea.topLeft, size = specArea.size)
    drawRect(color = Color(0xFF00B0FF).copy(alpha = 0.3f), topLeft = specArea.topLeft, size = specArea.size, style = Stroke(width = 1f))

    // Tiny spec screen binary line guides
    val codeColor = Color(0xFF00B0FF).copy(alpha = 0.45f)
    drawLine(color = codeColor, start = Offset(specArea.left + 10f * dDensity, specArea.top + 14f * dDensity), end = Offset(specArea.left + 50f * dDensity, specArea.top + 14f * dDensity), strokeWidth = 3f)
    drawLine(color = codeColor, start = Offset(specArea.left + 10f * dDensity, specArea.top + 24f * dDensity), end = Offset(specArea.left + 35f * dDensity, specArea.top + 24f * dDensity), strokeWidth = 3f)
    
    // Binary code glyph matrices as eyes: LED square monitors
    val leftEyeRect = Rect(center.x - radius * 0.52f - pOff.x, internalY - radius * 0.38f - pOff.y, center.x - radius * 0.14f - pOff.x, internalY + radius * 0.02f - pOff.y)
    val rightEyeRect = Rect(center.x + radius * 0.14f - pOff.x, internalY - radius * 0.38f - pOff.y, center.x + radius * 0.52f - pOff.x, internalY + radius * 0.02f - pOff.y)

    listOf(leftEyeRect, rightEyeRect).forEachIndexed { eyeIdx, rect ->
        drawRect(color = Color(0xFF0A0F1D), topLeft = rect.topLeft, size = rect.size)
        drawBevelBorder(rect = rect, highColor = Color.White.copy(alpha = 0.1f), shadowColor = Color.Black, width = 1.5f * dDensity, inset = true)

        val cellsX = 5
        val cellsY = 5
        val cw = rect.width / cellsX
        val ch = rect.height / cellsY

        val tPhase = (orbitAngle * 0.05f).toInt()
        for (gx in 0 until cellsX) {
            for (gy in 0 until cellsY) {
                val active = ((gx * 7 + gy * 13 + tPhase + eyeIdx * 17) % 3 == 0)
                if (active) {
                    val ledColor = Color(0xFF00E676)
                    val cellRect = Rect(rect.left + gx * cw + 2f, rect.top + gy * ch + 2f, rect.left + (gx + 1) * cw - 2f, rect.top + (gy + 1) * ch - 2f)
                    drawRect(color = ledColor, topLeft = cellRect.topLeft, size = cellRect.size)
                    drawRect(color = Color.White.copy(alpha = 0.6f), topLeft = cellRect.topLeft, size = Size(cellRect.width, 2f))
                }
            }
        }
    }

    // Oscilloscope Green Soundscreen as mouth
    val oscRect = Rect(center.x - radius * 0.55f - pOff.x, center.y + radius * 0.22f - pOff.y, center.x + radius * 0.55f - pOff.x, center.y + radius * 0.68f - pOff.y)
    drawRect(color = Color(0xFF101C17), topLeft = oscRect.topLeft, size = oscRect.size)
    drawBevelBorder(rect = oscRect, highColor = Color.White.copy(alpha = 0.1f), shadowColor = Color.Black, width = 2f * dDensity, inset = true)

    val scopeGrid = Color(0xFF00E676).copy(alpha = 0.12f)
    val divCount = 4
    for (i in 1 until divCount) {
        val gy = oscRect.top + (i * oscRect.height / divCount)
        val gx = oscRect.left + (i * oscRect.width / divCount)
        drawLine(color = scopeGrid, start = Offset(oscRect.left, gy), end = Offset(oscRect.right, gy), strokeWidth = 1f)
        drawLine(color = scopeGrid, start = Offset(gx, oscRect.top), end = Offset(gx, oscRect.bottom), strokeWidth = 1f)
    }

    val oscPath = Path()
    val midY = oscRect.center.y
    oscPath.moveTo(oscRect.left, midY)

    val steps = 40
    val segW = oscRect.width / steps
    for (i in 0..steps) {
        val px = oscRect.left + i * segW
        val ampLimit = oscRect.height * 0.4f
        val volumeFactor = if (state == CompanionState.SPEAKING || state == CompanionState.LISTENING) speechAmp else 0.1f
        val theta = i * 0.45f + wavePhase
        val shapeOsc = sin(theta) * cos(theta * 0.5f)
        val py = midY + shapeOsc * ampLimit * (0.05f + volumeFactor * 0.95f)
        
        oscPath.lineTo(px, py)
    }

    drawPath(
        path = oscPath,
        color = Color(0xFF00E676),
        style = Stroke(width = 2f * dDensity, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    drawPath(
        path = oscPath,
        color = Color(0xFF00E676).copy(alpha = 0.28f),
        style = Stroke(width = 6f * dDensity, cap = StrokeCap.Round)
    )
}

// ------------------------------------------
// PERSONA 3: MAYA (BOTANICAL GUIDE)
// ------------------------------------------
private fun DrawScope.drawMayaBotanical(
    center: Offset,
    touchOffset: Offset,
    radius: Float,
    orbitAngle: Float,
    state: CompanionState,
    speechAmp: Float,
    breathing: Float,
    dDensity: Float
) {
    // Structural Head Frame: Carved Wood and Brass Dome
    val helmetPath = Path().apply {
         addOval(Rect(center.x - radius * 0.82f, center.y - radius * 0.9f, center.x + radius * 0.82f, center.y + radius * 0.82f))
    }

    // Wood Walnut core back layer
    drawPath(
        path = helmetPath,
        brush = Brush.radialGradient(colors = listOf(Color(0xFF8D6E63), Color(0xFF4E342E)), center = center, radius = radius * 1.3f)
    )

    // Brass overlay plates surrounding wood
    drawPath(
        path = helmetPath,
        brush = Brush.linearGradient(colors = listOf(Color(0xFFFFD54F), Color(0xFF8D6E63), Color(0xFFFFB300))),
        style = Stroke(width = 6f * dDensity)
    )

    // Botanical etching flowers (Cheek rivets)
    val flowerLeft = Offset(center.x - radius * 0.65f, center.y + radius * 0.35f)
    val flowerRight = Offset(center.x + radius * 0.65f, center.y + radius * 0.35f)
    listOf(flowerLeft, flowerRight).forEach { pos ->
        drawCircle(color = Color(0xFF81C784).copy(alpha = 0.5f), radius = 6f, center = pos)
        drawCircle(color = Color(0xFFFFB300), radius = 3f, center = pos)
    }

    // Shutter Lens camera eyes
    val pOff = touchOffset * 0.15f
    val leftEyeCenter = Offset(center.x - radius * 0.38f - pOff.x, center.y - radius * 0.18f - pOff.y)
    val rightEyeCenter = Offset(center.x + radius * 0.38f - pOff.x, center.y - radius * 0.18f - pOff.y)

    listOf(leftEyeCenter, rightEyeCenter).forEachIndexed { i, eyePos ->
        drawCircle(
            brush = Brush.sweepGradient(colors = listOf(Color(0xFFFFD54F), Color(0xFF8D6E63), Color(0xFFFFD54F)), center = eyePos),
            radius = radius * 0.24f,
            center = eyePos
        )
        drawCircle(
            color = Color(0xFF3E2723),
            radius = radius * 0.21f,
            center = eyePos,
            style = Stroke(width = 2f * dDensity)
        )

        drawCircle(
            color = Color(0xFF0F1E19),
            radius = radius * 0.19f,
            center = eyePos
        )

        // Shutter blades (pure Float conversions avoid Double casting complexity)
        val blades = 6
        val rotationAngle = orbitAngle * 0.15f + i * 45f
        for (b in 0 until blades) {
            val baseAngle = rotationAngle + b * (360f / blades)
            val rad1 = baseAngle * 0.017453292f
            val bladeTip = eyePos + Offset(cos(rad1) * radius * 0.19f, sin(rad1) * radius * 0.19f)
            
            drawLine(
                color = Color(0xFFFFB300).copy(alpha = 0.35f),
                start = eyePos,
                end = bladeTip,
                strokeWidth = 2f
            )
        }

        // Dilating copper leaf core
        val pupilBase = radius * 0.07f
        val pupilScale = 0.85f + (speechAmp * 0.45f) + (breathing - 1.0f) * 1.5f
        val finalPupilRadius = (pupilBase * pupilScale).coerceAtLeast(4f)
        
        drawCircle(
            brush = Brush.radialGradient(colors = listOf(Color(0xFF81C784), Color.Transparent), center = eyePos, radius = finalPupilRadius * 2.8f),
            radius = finalPupilRadius * 2.8f,
            center = eyePos
        )
        drawCircle(
            color = Color.White,
            radius = finalPupilRadius * 0.8f,
            center = eyePos
        )
        drawCircle(
            color = Color(0xFF4CAF50),
            radius = finalPupilRadius * 0.8f,
            center = eyePos,
            style = Stroke(width = 1f * dDensity)
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.65f),
            radius = 3.5f,
            center = eyePos - Offset(finalPupilRadius * 0.55f, finalPupilRadius * 0.55f)
        )
    }

    // Incandescent vacuum tube mouth filaments
    val mouthCenter = Offset(center.x - pOff.x, center.y + radius * 0.35f - pOff.y)
    val bulbW = radius * 0.40f
    val bulbH = radius * 0.20f
    val bulbRect = Rect(mouthCenter.x - bulbW / 2f, mouthCenter.y - bulbH / 2f, mouthCenter.x + bulbW / 2f, mouthCenter.y + bulbH / 2f)

    drawRoundRect(
        color = Color(0xFF1E1100),
        topLeft = bulbRect.topLeft,
        size = bulbRect.size,
        cornerRadius = CornerRadius(10f * dDensity, 10f * dDensity)
    )

    drawBevelBorder(
        rect = bulbRect,
        highColor = Color.White.copy(alpha = 0.12f),
        shadowColor = Color.Black.copy(alpha = 0.8f),
        width = 1.5f * dDensity,
        inset = true
    )

    val baseFilamentColor = Color(0xFFFFB300)
    val filamentLuminance = if (state == CompanionState.SPEAKING) 0.5f + speechAmp * 0.5f else 0.45f + 0.15f * sin(orbitAngle * 0.08f)
    val wireGlowColor = baseFilamentColor.copy(alpha = filamentLuminance)

    val filamentPath = Path().apply {
        moveTo(bulbRect.left + bulbW * 0.22f, bulbRect.bottom - bulbH * 0.25f)
        quadraticTo(
            bulbRect.left + bulbW * 0.35f, bulbRect.top + bulbH * 0.1f,
            bulbRect.left + bulbW * 0.5f, bulbRect.bottom - bulbH * 0.3f
        )
        quadraticTo(
            bulbRect.left + bulbW * 0.65f, bulbRect.top + bulbH * 0.1f,
            bulbRect.right - bulbW * 0.22f, bulbRect.bottom - bulbH * 0.25f
        )
    }

    drawPath(
        path = filamentPath,
        color = wireGlowColor.copy(alpha = filamentLuminance * 0.35f),
        style = Stroke(width = 8f * dDensity, cap = StrokeCap.Round)
    )

    // Fix: line cap attribute renamed to standard 'cap' parameter in drawLine inside DrawScope
    drawPath(
        path = filamentPath,
        color = Color.White.copy(alpha = filamentLuminance),
        style = Stroke(width = 1.8f * dDensity, cap = StrokeCap.Round)
    )
    drawPath(
        path = filamentPath,
        color = baseFilamentColor,
        style = Stroke(width = 2.8f * dDensity, cap = StrokeCap.Round)
    )
}

// ------------------------------------------
// PERSONA 4: ATLAS (COSMIC NAVIGATOR)
// ------------------------------------------
private fun DrawScope.drawAtlasNavigator(
    center: Offset,
    touchOffset: Offset,
    radius: Float,
    orbitAngle: Float,
    state: CompanionState,
    speechAmp: Float,
    dDensity: Float
) {
    // Angular Boiler Octagonal Casing
    val headPath = Path().apply {
        moveTo(center.x - radius * 0.85f, center.y - radius * 0.45f)
        lineTo(center.x - radius * 0.45f, center.y - radius * 0.95f)
        lineTo(center.x + radius * 0.45f, center.y - radius * 0.95f)
        lineTo(center.x + radius * 0.85f, center.y - radius * 0.45f)
        lineTo(center.x + radius * 0.72f, center.y + radius * 0.80f)
        lineTo(center.x - radius * 0.72f, center.y + radius * 0.80f)
        close()
    }

    val heavyPlateBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF8E24AA).copy(alpha = 0.05f), Color(0xFFD84315), Color(0xFF3E2723)),
        center = center - Offset(radius * 0.3f, radius * 0.3f),
        radius = radius * 1.5f
    )
    drawPath(path = headPath, brush = heavyPlateBrush)

    // Boiler seam plate joints
    drawLine(
        color = Color.Black.copy(alpha = 0.7f),
        start = Offset(center.x, center.y - radius * 0.95f),
        end = Offset(center.x, center.y + radius * 0.8f),
        strokeWidth = 3f
    )
    drawLine(
        color = Color.White.copy(alpha = 0.15f),
        start = Offset(center.x + 1.5f, center.y - radius * 0.95f),
        end = Offset(center.x + 1.5f, center.y + radius * 0.8f),
        strokeWidth = 1f
    )

    drawPath(
        path = headPath,
        color = Color(0xFFFFD54F).copy(alpha = 0.28f),
        style = Stroke(width = 3f * dDensity, join = StrokeJoin.Round)
    )
    drawPath(
        path = headPath,
        color = Color.Black.copy(alpha = 0.6f),
        style = Stroke(width = 1f * dDensity)
    )

    val pOff = touchOffset * 0.18f
    val internalY = center.y - radius * 0.15f

    // LEFT EYE: Astrological Telescope Lens
    val leftEyeCenter = Offset(center.x - radius * 0.36f - pOff.x, internalY - radius * 0.15f - pOff.y)
    drawCircle(
        color = Color(0xFF4E342E),
        radius = radius * 0.26f,
        center = leftEyeCenter
    )
    drawCircle(
        color = Color(0xFFFF8F00),
        radius = radius * 0.26f,
        center = leftEyeCenter,
        style = Stroke(width = 2f * dDensity)
    )
    drawCircle(
        color = Color(0xFF09142A),
        radius = radius * 0.21f,
        center = leftEyeCenter
    )

    // Target crosshair indicators
    val scopeColor = Color(0xFFFF7043).copy(alpha = 0.45f)
    val rLen = radius * 0.21f
    drawLine(color = scopeColor, start = leftEyeCenter - Offset(rLen, 0f), end = leftEyeCenter + Offset(rLen, 0f), strokeWidth = 1f)
    drawLine(color = scopeColor, start = leftEyeCenter - Offset(0f, rLen), end = leftEyeCenter + Offset(0f, rLen), strokeWidth = 1f)
    drawCircle(color = scopeColor, radius = radius * 0.1f, center = leftEyeCenter, style = Stroke(width = 1f))

    // Laser indicator optic spotlight
    val searchBlink = if (abs(cos(orbitAngle * 0.05f)) < 0.1f) 0.05f else 1.0f
    val laserRadius = radius * 0.045f * (1.0f + speechAmp * 0.4f) * searchBlink
    if (laserRadius > 1f) {
        drawCircle(
            brush = Brush.radialGradient(colors = listOf(Color.White, Color(0xFFFF3D00).copy(alpha = searchBlink), Color.Transparent), center = leftEyeCenter, radius = laserRadius * 3.5f),
            radius = laserRadius * 3.5f,
            center = leftEyeCenter
        )
        drawCircle(color = Color.White, radius = laserRadius * 0.8f, center = leftEyeCenter)
    }

    // RIGHT EYE: Pressure Meter Gauge
    val rightEyeCenter = Offset(center.x + radius * 0.38f - pOff.x, internalY - radius * 0.15f - pOff.y)
    val gaugeRadius = radius * 0.18f
    
    drawCircle(color = Color(0xFFECEFF1), radius = gaugeRadius, center = rightEyeCenter)
    drawCircle(color = Color(0xFF455A64), radius = gaugeRadius, center = rightEyeCenter, style = Stroke(width = 2f * dDensity))

    // Gauge tick hash divisions
    val gTicks = 8
    for (t in 0 until gTicks) {
        val angle = -150f + t * (300f / (gTicks - 1))
        val rads = angle * 0.017453292f
        drawLine(
            color = Color(0xFF37474F),
            start = rightEyeCenter + Offset(cos(rads) * gaugeRadius * 0.7f, sin(rads) * gaugeRadius * 0.7f),
            end = rightEyeCenter + Offset(cos(rads) * gaugeRadius * 0.9f, sin(rads) * gaugeRadius * 0.9f),
            strokeWidth = 1.5f
        )
    }

    // Needle mechanical swing meter (Float angle math prevents ComplexDouble errors)
    val gaugeAngleDev = -135f + (270f * (0.1f + speechAmp * 0.85f))
    val needleRads = (gaugeAngleDev + abs(sin(orbitAngle * 0.18f) * 8f)) * 0.017453292f
    
    drawLine(
        color = Color(0xFFC62828),
        start = rightEyeCenter,
        end = rightEyeCenter + Offset(cos(needleRads) * gaugeRadius * 0.80f, sin(needleRads) * gaugeRadius * 0.80f),
        strokeWidth = 2.5f
    )
    drawCircle(color = Color(0xFF37474F), radius = 4f, center = rightEyeCenter)

    // Steam iron vent slot mouth
    val mouthW = radius * 0.44f
    val mouthH = radius * 0.22f
    val mouthPos = Offset(center.x - pOff.x, center.y + radius * 0.38f - pOff.y)
    val mouthRect = Rect(mouthPos.x - mouthW / 2f, mouthPos.y - mouthH / 2f, mouthPos.x + mouthW / 2f, mouthPos.y + mouthH / 2f)

    drawRect(color = Color(0xFF1E110A), topLeft = mouthRect.topLeft, size = mouthRect.size)
    drawBevelBorder(rect = mouthRect, highColor = Color.White.copy(alpha = 0.1f), shadowColor = Color.Black, width = 1.5f * dDensity, inset = true)

    val slots = 6
    val sw = mouthRect.width / slots
    for (s in 1 until slots) {
        val barX = mouthRect.left + s * sw
        drawLine(
            color = Color.Black,
            start = Offset(barX, mouthRect.top + 2f),
            end = Offset(barX, mouthRect.bottom - 2f),
            strokeWidth = 4f
        )

        val fireAmp = if (state == CompanionState.SPEAKING) speechAmp else 0.15f
        val flareYLen = mouthRect.height * 0.65f * (0.15f + fireAmp * 0.85f * abs(sin((s * 1.5f) + orbitAngle * 0.1f)))
        val fireColor = Color(0xFFFF5722)

        if (flareYLen > 1f) {
            // Fix: Use correct DrawScope signature parameter named 'cap' instead of 'strokeCap'
            drawLine(
                brush = Brush.linearGradient(colors = listOf(Color.White, fireColor, Color.Transparent)),
                start = Offset(barX, mouthRect.center.y - flareYLen / 2f),
                end = Offset(barX, mouthRect.center.y + flareYLen / 2f),
                strokeWidth = 2f * dDensity,
                cap = StrokeCap.Round
            )
        }
    }
}

// ------------------------------------------
// ANALOG RETRO SKEUOMORPHIC VU NEEDLE METER
// ------------------------------------------
private fun DrawScope.drawAnalogVUMeter(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    amp: Float,
    state: CompanionState,
    dDensity: Float
) {
    val meterRect = Rect(x, y, x + width, y + height)

    // Vintage Ivory/Aged Cream dashboard paper card backing
    drawRoundRect(
        color = Color(0xFFF1E9D2),
        topLeft = meterRect.topLeft,
        size = meterRect.size,
        cornerRadius = CornerRadius(5f * dDensity, 5f * dDensity)
    )

    // Debossed steel bezel boundary for VU casing frame split
    drawBevelBorder(
        rect = meterRect,
        highColor = Color.White.copy(alpha = 0.35f),
        shadowColor = Color.Black.copy(alpha = 0.75f),
        width = 2.5f * dDensity,
        inset = true
    )

    val pivot = Offset(meterRect.center.x, meterRect.bottom - 4f)
    val scaleRadius = height * 0.85f

    // Draw the gauge indicator dial curved arc line
    val startAngleDeg = -140f
    val sweepAngleDeg = 100f
    
    val baseDialColor = Color(0xFF455A64)
    val peakZoneColor = Color(0xFFD32F2F)

    // Draw tick markings along the volume curve scale
    val tickCount = 13
    for (t in 0 until tickCount) {
        val progress = t.toFloat() / (tickCount - 1)
        val angle = startAngleDeg + (progress * sweepAngleDeg)
        val rads = angle * 0.017453292f
        
        val tickColor = if (progress >= 0.8f) peakZoneColor else baseDialColor
        val tickSize = if (t % 3 == 0) 6f * dDensity else 3.5f * dDensity

        val p1 = pivot + Offset(cos(rads) * (scaleRadius - tickSize), sin(rads) * (scaleRadius - tickSize))
        val p2 = pivot + Offset(cos(rads) * scaleRadius, sin(rads) * scaleRadius)

        drawLine(
            color = tickColor,
            start = p1,
            end = p2,
            strokeWidth = if (t % 3 == 0) 1.5f * dDensity else 0.8f * dDensity
        )
    }

    // Labels dB indicator lines
    val textY = meterRect.top + height * 0.52f
    val symbolColor = Color(0xFF455A64).copy(alpha = 0.75f)
    drawLine(color = symbolColor, start = Offset(pivot.x - 12f * dDensity, textY), end = Offset(pivot.x - 9f * dDensity, textY + 5f * dDensity), strokeWidth = 1.3f * dDensity)
    drawLine(color = symbolColor, start = Offset(pivot.x - 9f * dDensity, textY + 5f * dDensity), end = Offset(pivot.x - 6f * dDensity, textY), strokeWidth = 1.3f * dDensity)
    drawLine(color = symbolColor, start = Offset(pivot.x + 6f * dDensity, textY), end = Offset(pivot.x + 6f * dDensity, textY + 5f * dDensity), strokeWidth = 1.3f * dDensity)
    drawLine(color = symbolColor, start = Offset(pivot.x + 6f * dDensity, textY + 5f * dDensity), end = Offset(pivot.x + 12f * dDensity, textY + 5f * dDensity), strokeWidth = 1.3f * dDensity)
    drawLine(color = symbolColor, start = Offset(pivot.x + 12f * dDensity, textY + 5f * dDensity), end = Offset(pivot.x + 12f * dDensity, textY), strokeWidth = 1.3f * dDensity)

    // Bouncing volume indicator steel needle
    val liveDbAmp = if (state == CompanionState.SPEAKING || state == CompanionState.LISTENING) amp else 0.05f
    val needleAngleDeg = startAngleDeg + (liveDbAmp * sweepAngleDeg)
    val needleRads = needleAngleDeg * 0.017453292f

    val needleTip = pivot + Offset(cos(needleRads) * scaleRadius * 0.95f, sin(needleRads) * scaleRadius * 0.95f)
    
    // Fix: line cap attribute renamed to standard 'cap' parameter in drawLine inside DrawScope
    drawLine(
        color = Color(0xFF212121),
        start = pivot,
        end = needleTip,
        strokeWidth = 1.3f * dDensity,
        cap = StrokeCap.Round
    )

    // Center pivot secure metal knot
    drawCircle(
        color = Color(0xFF37474F),
        radius = 4f * dDensity,
        center = pivot
    )
    drawCircle(
        color = Color(0xFF90A4AE),
        radius = 2f * dDensity,
        center = pivot
    )
}

// ------------------------------------------
// MODULAR BLINKING DIAGNOSTIC PHYSICAL LEDs
// ------------------------------------------
private fun DrawScope.drawDiagnosticLEDs(
    x: Float,
    y: Float,
    state: CompanionState,
    orbitAngle: Float,
    dDensity: Float
) {
    val cellSpacing = 24f * dDensity
    val ledRadius = 4f * dDensity

    val indicators = listOf(
        // POWER LED: Solid warm green always on
        Triple("POWER", Color(0xFF4CAF50), 1.0f),
        // REC/LIVE LED: Red blinker during active listening
        Triple("LIVE", Color(0xFFE53935), if (state == CompanionState.LISTENING) (0.5f + 0.5f * sin(orbitAngle * 0.22f)) else 0.08f),
        // SYNC LED: Amber loading sync flashes during assistant thinking
        Triple("SYNC", Color(0xFFFFB300), if (state == CompanionState.THINKING) (0.4f + 0.6f * sin(orbitAngle * 0.45f)) else if (state == CompanionState.SPEAKING) 0.5f else 0.08f)
    )

    indicators.forEachIndexed { i, (label, color, alpha) ->
        val ledPos = Offset(x + i * cellSpacing, y)

        drawCircle(
            color = Color(0xFF0C0E14),
            radius = ledRadius * 1.5f,
            center = ledPos
        )
        drawCircle(
            color = Color(0xFF78909C),
            radius = ledRadius * 1.55f,
            center = ledPos,
            style = Stroke(width = 0.8f * dDensity)
        )

        if (alpha > 0.15f) {
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(color.copy(alpha = alpha * 0.65f), Color.Transparent), center = ledPos, radius = ledRadius * 3.2f),
                radius = ledRadius * 3.2f,
                center = ledPos
            )
        }

        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = ledRadius,
            center = ledPos
        )
        drawCircle(
            color = color.copy(alpha = alpha.coerceAtLeast(0.18f)),
            radius = ledRadius,
            center = ledPos,
            style = Stroke(width = 1f * dDensity)
        )
        drawCircle(
            color = color.copy(alpha = alpha.coerceAtLeast(0.15f)),
            radius = ledRadius * 0.85f,
            center = ledPos
        )

        // Reflection highlight bulb point
        drawCircle(
            color = Color.White,
            radius = 1f,
            center = ledPos - Offset(ledRadius * 0.4f, ledRadius * 0.4f)
        )
    }
}
