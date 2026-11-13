package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.VioletSecondary
import com.example.voice.CompanionState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium Animated AI Orb
 * States:
 * - IDLE: Slow breathing ambient glow
 * - LISTENING: Reactive waveform with concentric sound pulses
 * - THINKING: Rotating aurora chromatic gradient rings
 * - SPEAKING: Dynamic audio-reactive expanding orb with fluid ripples
 * - ERROR: Subtle warning pulse
 */
@Composable
fun AnimatedAiOrb(
    companionState: CompanionState,
    rmsLevel: Float = 0.5f,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_anim")

    // Slow breathing animation (Idle & Speaking)
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Rapid pulse for listening
    val listeningPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_pulse"
    )

    // Rotation angle for thinking state (4.5s loop repeating cleanly until answer arrives)
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_rotation"
    )

    // Ring expansion
    val ringPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_phase"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension * 0.36f

            when (companionState) {
                CompanionState.IDLE -> {
                    val currentRadius = baseRadius * breathingScale
                    // Outer soft ambient aura
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                CyanPrimary.copy(alpha = 0.25f),
                                VioletSecondary.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = currentRadius * 1.55f
                        ),
                        radius = currentRadius * 1.55f,
                        center = center
                    )

                    // Core gradient orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.85f),
                                CyanPrimary,
                                VioletSecondary,
                                Color(0xFF1E1B4B)
                            ),
                            center = center,
                            radius = currentRadius
                        ),
                        radius = currentRadius,
                        center = center
                    )

                    // Subtle glass specular ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = currentRadius * 0.92f,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                CompanionState.LISTENING -> {
                    val currentRadius = baseRadius * listeningPulse * (1f + rmsLevel * 0.25f)

                    // Concentric audio reaction waves
                    for (i in 1..3) {
                        val rippleRadius = currentRadius + (i * 26.dp.toPx() * ringPhase)
                        val rippleAlpha = (1f - (rippleRadius - currentRadius) / (100.dp.toPx())).coerceIn(0f, 0.45f)
                        drawCircle(
                            color = AuroraEmerald.copy(alpha = rippleAlpha),
                            radius = rippleRadius,
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }

                    // Intense energetic core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                AuroraEmerald,
                                CyanPrimary,
                                Color(0xFF064E3B)
                            ),
                            center = center,
                            radius = currentRadius
                        ),
                        radius = currentRadius,
                        center = center
                    )

                    // Specular ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.6f),
                        radius = currentRadius * 0.95f,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                CompanionState.THINKING -> {
                    val currentRadius = baseRadius * 1.02f

                    // Rotating celestial gradient
                    val rad = Math.toRadians(rotationAngle.toDouble())
                    val offsetX = (cos(rad) * currentRadius * 0.5f).toFloat()
                    val offsetY = (sin(rad) * currentRadius * 0.5f).toFloat()

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AuroraPink.copy(alpha = 0.4f),
                                VioletSecondary.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = currentRadius * 1.6f
                        ),
                        radius = currentRadius * 1.6f,
                        center = center
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                VioletSecondary,
                                AuroraPink,
                                CyanPrimary
                            ),
                            center = Offset(center.x + offsetX, center.y + offsetY),
                            radius = currentRadius
                        ),
                        radius = currentRadius,
                        center = center
                    )

                    // Orbital ring
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                CyanPrimary,
                                AuroraPink,
                                VioletSecondary,
                                CyanPrimary
                            ),
                            center = center
                        ),
                        radius = currentRadius * 1.15f,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                CompanionState.TALKING -> {
                    val currentRadius = baseRadius * (1f + rmsLevel * 0.35f) * breathingScale

                    // Dynamic sound ripples
                    for (i in 1..2) {
                        val expandingR = currentRadius * (1f + i * 0.22f * ringPhase)
                        drawCircle(
                            color = AuroraPink.copy(alpha = 0.35f * (1f - ringPhase)),
                            radius = expandingR,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }

                    // Warm expressive pink-violet core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                AuroraPink,
                                VioletSecondary,
                                Color(0xFF4C0519)
                            ),
                            center = center,
                            radius = currentRadius
                        ),
                        radius = currentRadius,
                        center = center
                    )

                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f),
                        radius = currentRadius * 0.94f,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }

                CompanionState.ERROR -> {
                    val currentRadius = baseRadius * (0.95f + 0.08f * ringPhase)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                DangerRed.copy(alpha = 0.45f),
                                AuroraPink.copy(alpha = 0.15f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = currentRadius * 1.55f
                        ),
                        radius = currentRadius * 1.55f,
                        center = center
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.85f),
                                DangerRed,
                                Color(0xFF7F1D1D)
                            ),
                            center = center,
                            radius = currentRadius
                        ),
                        radius = currentRadius,
                        center = center
                    )

                    drawCircle(
                        color = DangerRed.copy(alpha = 0.8f),
                        radius = currentRadius * 1.12f,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }
        }
    }
}
