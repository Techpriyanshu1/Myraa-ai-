package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class RealScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameCapturedAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjection()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (action == ACTION_STOP) {
            stopProjection()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            _lastError.value = null
            val notification = buildNotification("MYRAA AI can view your screen")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e("RealScreenCapture", "Foreground start failed", e)
                _lastError.value = "Screen share service failed to start: ${e.localizedMessage}"
                _isSharingActive.value = false
                stopSelf()
                return START_NOT_STICKY
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                projectionManager.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                Log.e("RealScreenCapture", "getMediaProjection failed", e)
                null
            }

            if (projection == null) {
                _lastError.value = "MediaProjection authorization was denied or expired."
                _isSharingActive.value = false
                stopSelf()
                return START_NOT_STICKY
            }

            mediaProjection = projection
            // Android 14+ requires registering callback before creating VirtualDisplay
            projection.registerCallback(projectionCallback, mainHandler)

            val startedDisplay = startVirtualDisplay()
            if (!startedDisplay) {
                _lastError.value = "Could not create screen capture display."
                stopProjection()
                stopSelf()
                return START_NOT_STICKY
            }

            _isSharingActive.value = true
            if (Settings.canDrawOverlays(this)) {
                try {
                    DigitalMouseService.start(this)
                } catch (e: Exception) {
                    Log.w("RealScreenCapture", "DigitalMouse overlay skipped: ${e.message}")
                }
            }
            Log.i("RealScreenCapture", "Android MediaProjection Screen Sharing active")
        } else {
            _lastError.value = "Screen Share permission cancelled."
            _isSharingActive.value = false
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startVirtualDisplay(): Boolean {
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (rawW, rawH, density) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                val dpi = resources.displayMetrics.densityDpi
                Triple(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), dpi)
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Triple(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), metrics.densityDpi)
            }

            // Downscale VirtualDisplay so frames stay sharp for Gemini vision while keeping voice+vision fast
            val scale = if (rawW > 540) 540f / rawW else 1f
            val width = (rawW * scale).toInt().coerceAtLeast(320)
            val height = (rawH * scale).toInt().coerceAtLeast(568)

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                var rawBitmap: Bitmap? = null
                var croppedBitmap: Bitmap? = null
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameCapturedAt < 2000L) {
                        return@setOnImageAvailableListener
                    }
                    lastFrameCapturedAt = now

                    val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bmpWidth = image.width + (if (pixelStride > 0) rowPadding / pixelStride else 0)
                    rawBitmap = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    val finalBitmap = if (bmpWidth != image.width) {
                        croppedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                        croppedBitmap
                    } else {
                        rawBitmap
                    }

                    val out = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                    _latestScreenFrameBase64.value = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w("RealScreenCapture", "Frame encode skipped: ${e.message}")
                } finally {
                    croppedBitmap?.recycle()
                    rawBitmap?.recycle()
                    image.close()
                }
            }, mainHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MyraaRealScreenShare",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, mainHandler
            )
            virtualDisplay != null
        } catch (e: Exception) {
            Log.e("RealScreenCapture", "VirtualDisplay creation failed", e)
            false
        }
    }

    private fun stopProjection() {
        try {
            DigitalMouseService.stop(this)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e("RealScreenCapture", "Error releasing projection", e)
        }
        _latestScreenFrameBase64.value = null
        _isSharingActive.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        stopProjection()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRAA Screen Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time screen share with MYRAA AI assistant"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RealScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRAA AI • Screen Share Active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myraa_screen_share_channel"
        const val NOTIFICATION_ID = 3003
        const val ACTION_STOP = "com.example.service.ACTION_STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isSharingActive = MutableStateFlow(false)
        val isSharingActive: StateFlow<Boolean> = _isSharingActive.asStateFlow()

        private val _latestScreenFrameBase64 = MutableStateFlow<String?>(null)
        val latestScreenFrameBase64: StateFlow<String?> = _latestScreenFrameBase64.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RealScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!_isSharingActive.value) return
            val intent = Intent(context, RealScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                _isSharingActive.value = false
                _latestScreenFrameBase64.value = null
            }
        }
    }
}
