package com.example.ui.components

import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.CyanPrimary
import com.example.voice.CompanionState

/**
 * High-Performance Single-MediaPlayer Video Avatar Player.
 * Completely eliminates lag by running only ONE active hardware video decoder at a time.
 * Displays BIG with NO BORDER as explicitly requested by the user.
 */
@Composable
fun VideoAvatarPlayer(
    companionState: CompanionState,
    isScreenShareActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Determine target asset based on companion state
    val targetAsset = when (companionState) {
        CompanionState.TALKING -> "talking.mp4"
        CompanionState.THINKING -> "thinking.mp4"
        CompanionState.IDLE, CompanionState.LISTENING, CompanionState.ERROR -> "idle.mp4"
    }

    // Holder for the single active MediaPlayer instance
    val playerHolder = remember { arrayOf<MediaPlayer?>(null) }
    val surfaceHolder = remember { arrayOf<Surface?>(null) }
    val currentAssetHolder = remember { arrayOf("") }

    // Helper to safely play an asset on the existing prepared surface
    fun switchVideo(assetName: String) {
        if (currentAssetHolder[0] == assetName && playerHolder[0]?.isPlaying == true) {
            return
        }
        val surface = surfaceHolder[0] ?: return
        try {
            val existingMp = playerHolder[0]
            if (existingMp != null) {
                try {
                    existingMp.reset()
                } catch (ignored: Exception) {}
            }
            val mp = existingMp ?: MediaPlayer()
            playerHolder[0] = mp

            val afd: AssetFileDescriptor = context.assets.openFd(assetName)
            mp.setSurface(surface)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mp.setOnPreparedListener { prepared ->
                prepared.start()
                currentAssetHolder[0] = assetName
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("VideoAvatarPlayer", "Error switching video to $assetName", e)
        }
    }

    // Trigger video switch when state changes
    LaunchedEffect(targetAsset) {
        switchVideo(targetAsset)
    }

    // Keep THINKING animation strictly to ~4.5 seconds per cycle and repeat until answer arrives
    LaunchedEffect(companionState) {
        if (companionState == CompanionState.THINKING) {
            while (companionState == CompanionState.THINKING) {
                kotlinx.coroutines.delay(4500L)
                if (companionState != CompanionState.THINKING) break
                try {
                    val mp = playerHolder[0]
                    if (mp != null && currentAssetHolder[0] == "thinking.mp4") {
                        mp.seekTo(0)
                        if (!mp.isPlaying) {
                            mp.start()
                        }
                    } else {
                        switchVideo("thinking.mp4")
                    }
                } catch (e: Exception) {
                    Log.w("VideoAvatarPlayer", "Thinking loop restart warning", e)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                playerHolder[0]?.stop()
                playerHolder[0]?.release()
                playerHolder[0] = null
                surfaceHolder[0]?.release()
                surfaceHolder[0] = null
            } catch (ignored: Exception) {}
        }
    }

    // BIG, Clean Hero Video Container with NO BORDER
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(370.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF070B12)),
        contentAlignment = Alignment.Center
    ) {
        // Single, ultra-smooth TextureView with hardware acceleration
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            surfaceHolder[0] = Surface(st)
                            switchVideo(targetAsset)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {}

                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            try {
                                playerHolder[0]?.stop()
                                playerHolder[0]?.release()
                                playerHolder[0] = null
                                surfaceHolder[0]?.release()
                                surfaceHolder[0] = null
                            } catch (ignored: Exception) {}
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    }
                }
            }
        )

        // Overlay status indicators (Floating badges without borders)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Companion State Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC050810))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (companionState) {
                                        CompanionState.TALKING -> Color(0xFFFF2E93)
                                        CompanionState.THINKING -> CyanPrimary
                                        CompanionState.LISTENING -> AuroraEmerald
                                        CompanionState.IDLE -> Color(0xFF6B7280)
                                        CompanionState.ERROR -> Color(0xFFEF4444)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = when (companionState) {
                                CompanionState.LISTENING -> "MYRAA • LISTENING"
                                CompanionState.THINKING -> "MYRAA • THINKING"
                                CompanionState.TALKING -> "MYRAA • SPEAKING"
                                CompanionState.IDLE -> "MYRAA • READY"
                                CompanionState.ERROR -> "MYRAA • ATTENTION"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Screen Share Live Indicator
                if (isScreenShareActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xE6FF2E93))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SCREEN SHARE LIVE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
