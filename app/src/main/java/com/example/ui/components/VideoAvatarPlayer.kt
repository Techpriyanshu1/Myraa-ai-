package com.example.ui.components

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.ui.theme.AuroraEmerald
import com.example.ui.theme.CyanPrimary
import com.example.voice.CompanionState

/**
 * High-Performance Single-ExoPlayer (Media3) Video Avatar Player.
 * Avoids android.media.MediaPlayer completely (preventing MediaPlayer-JNI -38 errors),
 * disables audio tracks on avatar loops so voice AudioRecord/AudioTrack never contend,
 * and renders borderless center-cropped animations on a hardware-accelerated TextureView.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoAvatarPlayer(
    companionState: CompanionState,
    isScreenShareActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val targetAsset = when (companionState) {
        CompanionState.TALKING -> "talking.mp4"
        CompanionState.THINKING -> "thinking.mp4"
        CompanionState.IDLE, CompanionState.LISTENING, CompanionState.ERROR -> "idle.mp4"
    }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var currentVideoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }

    fun applyCenterCropTransform(tv: TextureView?, videoSize: VideoSize) {
        if (tv == null || tv.width <= 0 || tv.height <= 0 || videoSize.width <= 0 || videoSize.height <= 0) {
            return
        }
        val viewWidth = tv.width.toFloat()
        val viewHeight = tv.height.toFloat()
        val viewAspect = viewWidth / viewHeight
        val videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()

        val scaleX: Float
        val scaleY: Float
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        }

        val matrix = Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        }
        tv.setTransform(matrix)
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext)
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                currentVideoSize = videoSize
                applyCenterCropTransform(textureViewRef, videoSize)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            textureViewRef?.let { tv ->
                exoPlayer.clearVideoTextureView(tv)
            }
            exoPlayer.release()
        }
    }

    LaunchedEffect(targetAsset, exoPlayer) {
        exoPlayer.setMediaItem(MediaItem.fromUri("asset:///$targetAsset"))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Keep THINKING animation cycling every ~4.5 seconds until answer arrives
    LaunchedEffect(companionState, exoPlayer) {
        if (companionState == CompanionState.THINKING) {
            while (companionState == CompanionState.THINKING) {
                kotlinx.coroutines.delay(4500L)
                if (companionState != CompanionState.THINKING) break
                exoPlayer.seekTo(0L)
                exoPlayer.playWhenReady = true
            }
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
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        applyCenterCropTransform(v as? TextureView, currentVideoSize)
                    }
                    textureViewRef = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { tv ->
                if (textureViewRef !== tv) {
                    textureViewRef = tv
                    exoPlayer.setVideoTextureView(tv)
                }
                applyCenterCropTransform(tv, currentVideoSize)
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
