package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.VioletSecondary
import kotlin.math.sin

@Composable
fun AuroraWaveGlow(
    isActive: Boolean,
    rmsLevel: Float = 0.5f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "aurora_transition")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 2400 else 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aurora_phase"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aurora_pulse"
    )

    val baseHeight = 56.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(baseHeight)
    ) {
        val width = size.width
        val height = size.height

        val activeMultiplier = if (isActive) (0.6f + rmsLevel * 0.8f) * pulseScale else 0.3f

        // First Aurora Wave (Cyan -> Violet)
        val path1 = Path().apply {
            moveTo(0f, height)
            for (x in 0..width.toInt() step 12) {
                val relX = x / width
                val y = height * 0.5f +
                        (sin((relX * 2.5f * Math.PI + phase).toDouble()).toFloat() * (height * 0.32f * activeMultiplier))
                lineTo(x.toFloat(), y)
            }
            lineTo(width, height)
            close()
        }

        drawPath(
            path = path1,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    CyanPrimary.copy(alpha = if (isActive) 0.65f else 0.25f),
                    VioletSecondary.copy(alpha = if (isActive) 0.75f else 0.30f),
                    AuroraPink.copy(alpha = if (isActive) 0.60f else 0.20f)
                )
            )
        )

        // Second Wave (Violet -> Emerald -> Cyan)
        val path2 = Path().apply {
            moveTo(0f, height)
            for (x in 0..width.toInt() step 12) {
                val relX = x / width
                val y = height * 0.45f +
                        (sin((relX * 3.2f * Math.PI - phase * 0.8f).toDouble()).toFloat() * (height * 0.38f * activeMultiplier))
                lineTo(x.toFloat(), y)
            }
            lineTo(width, height)
            close()
        }

        drawPath(
            path = path2,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    AuroraEmerald.copy(alpha = if (isActive) 0.50f else 0.15f),
                    CyanPrimary.copy(alpha = if (isActive) 0.70f else 0.25f),
                    VioletSecondary.copy(alpha = if (isActive) 0.55f else 0.20f)
                )
            )
        )
    }
}
