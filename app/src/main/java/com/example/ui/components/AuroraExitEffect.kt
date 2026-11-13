package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.AuroraPink
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.VioletSecondary

/**
 * Aurora Exit Effect
 * Brief, high-polish ChatGPT/Aurora-inspired light bloom shown during transitions or minimizes.
 * Never conceals audio state and never delays service release.
 */
@Composable
fun AuroraExitEffect(
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(350, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.9f),
        exit = fadeOut(tween(500, easing = FastOutSlowInEasing)) + scaleOut(targetScale = 1.15f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxDim = size.maxDimension * 0.7f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CyanPrimary.copy(alpha = 0.45f),
                            VioletSecondary.copy(alpha = 0.35f),
                            AuroraPink.copy(alpha = 0.20f),
                            AuroraEmerald.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxDim
                    ),
                    radius = maxDim,
                    center = center
                )
            }
        }
    }
}
