package com.example.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service for Continuous Voice Listening.
 * Adheres strictly to Android 14/15 Foreground Service types (microphone).
 * Notification includes:
 * - Listening status
 * - Pause Listening
 * - Resume Listening
 * - Stop AI
 * - Open App
 */
class ContinuousVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "myraa_voice_continuous_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START_LISTENING = "com.example.service.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.example.service.ACTION_STOP_LISTENING"
        const val ACTION_PAUSE_LISTENING = "com.example.service.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.example.service.ACTION_RESUME_LISTENING"

        private val _isListeningLive = MutableStateFlow(false)
        val isListeningLive: StateFlow<Boolean> = _isListeningLive.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ContinuousVoiceService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContinuousVoiceService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, ContinuousVoiceService::class.java).apply {
                action = ACTION_PAUSE_LISTENING
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, ContinuousVoiceService::class.java).apply {
                action = ACTION_RESUME_LISTENING
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = com.example.voice.MyraaSessionCoordinator.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                coordinator.stopSessionFromService()
                terminateService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_LISTENING -> {
                _isPaused.value = true
                coordinator.pauseListeningFromService()
                updateNotification("Listening paused by user")
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                _isPaused.value = false
                coordinator.resumeListeningFromService()
                updateNotification("Listening continuously in Hindi and English...")
                return START_STICKY
            }
            ACTION_START_LISTENING, null -> {
                _isListeningLive.value = true
                _isPaused.value = false

                val notification = buildForegroundNotification("MYRAA AI is listening in background (YouTube & apps ready)...")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.w("ContinuousVoiceService", "Foreground microphone start fallback", e)
                }
                return START_STICKY
            }
        }

        return START_STICKY
    }

    private fun terminateService() {
        _isListeningLive.value = false
        _isPaused.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRAA AI Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing voice listening status and controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Pause / Resume
        val toggleActionIntent = Intent(this, ContinuousVoiceService::class.java).apply {
            action = if (_isPaused.value) ACTION_RESUME_LISTENING else ACTION_PAUSE_LISTENING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            2,
            toggleActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Stop AI
        val stopIntent = Intent(this, ContinuousVoiceService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (_isPaused.value) "Resume Listening" else "Pause Listening"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRAA AI • Voice Active")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, togglePendingIntent)
            .addAction(0, "Stop AI", stopPendingIntent)
            .addAction(0, "Open App", openAppPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isListeningLive.value = false
        _isPaused.value = false
        serviceScope.cancel()
    }
}
