package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.pref.SecurePreferences
import com.example.voice.CompanionState
import com.example.voice.MyraaSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MyraaOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var overlayLabelView: TextView? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildForegroundNotification("MYRAA AI voice is active over apps")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_OVERLAY) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = SecurePreferences(this)
        if (prefs.isOverlayEnabled() && Settings.canDrawOverlays(this)) {
            showFloatingAurora()
            isRunning = true
        }

        return START_STICKY
    }

    private fun showFloatingAurora() {
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        val frame = FrameLayout(this).apply {
            setPadding(34, 18, 34, 18)
            background = createAuroraPillDrawable(0xFF00E5FF.toInt())
        }

        val textView = TextView(this).apply {
            text = "✨ MYRAA • Sun rahi hoon"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        frame.addView(textView)
        overlayLabelView = textView

        val coordinator = MyraaSessionCoordinator.getInstance(applicationContext)
        serviceScope.launch {
            combine(
                coordinator.isSessionActive,
                coordinator.isListeningPaused,
                coordinator.companionState
            ) { active, paused, state ->
                Triple(active, paused, state)
            }.collect { (active, paused, state) ->
                val (label, accentColor) = when {
                    active && paused -> "✨ MYRAA • Paused (Tap=Open)" to 0xFFFF4D9D.toInt()
                    active && state == CompanionState.LISTENING -> "🎙️ MYRAA • Sun rahi hoon (Tap=Send)" to 0xFF00E5FF.toInt()
                    active && state == CompanionState.THINKING -> "⚡ MYRAA • Soch rahi hoon..." to 0xFFB388FF.toInt()
                    active && state == CompanionState.TALKING -> "🔊 MYRAA • Bol rahi hoon" to 0xFF00E676.toInt()
                    active && state == CompanionState.ERROR -> "⚠️ MYRAA • Tap to open" to 0xFFFF5252.toInt()
                    else -> "✨ MYRAA AI • Active" to 0xFF00E5FF.toInt()
                }
                overlayLabelView?.text = label
                overlayLabelView?.setTextColor(accentColor)
                overlayView?.background = createAuroraPillDrawable(accentColor)
            }
        }

        // Drag & click listener
        frame.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY - (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = kotlin.math.abs(event.rawX - initialTouchX)
                    val diffY = kotlin.math.abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        if (coordinator.isSessionActive.value &&
                            !coordinator.isListeningPaused.value &&
                            coordinator.companionState.value == CompanionState.LISTENING
                        ) {
                            coordinator.finishUtteranceNow()
                        } else {
                            val launchIntent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(launchIntent)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(frame, params)
            overlayView = frame
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createAuroraPillDrawable(strokeColor: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f
            colors = intArrayOf(
                0xE60D1117.toInt(),
                0xE6161F30.toInt()
            )
            setStroke(3, strokeColor)
        }
    }

    private fun removeFloatingAurora() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
                overlayLabelView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Myraa AI Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Myraa AI voice listening and automation active in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myraa AI Assistant")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        removeFloatingAurora()
    }

    companion object {
        const val CHANNEL_ID = "myraa_ai_active_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_OVERLAY = "com.example.service.ACTION_STOP_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, MyraaOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            isRunning = false
            try {
                val intent = Intent(context, MyraaOverlayService::class.java).apply {
                    action = ACTION_STOP_OVERLAY
                }
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
