package com.example.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class DigitalMouseService : Service() {

    private var windowManager: WindowManager? = null
    private var mouseOverlayView: DigitalMouseView? = null
    private var keyboardOverlayView: View? = null
    private var keyboardTextView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mouseParams: WindowManager.LayoutParams? = null
    private var keyboardParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val notification = buildNotification("Myraa AI Digital Mouse & Screen Operator Active")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("DigitalMouseService", "Failed to start foreground DigitalMouseService", e)
            stopSelf()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            initMouseOverlay()
            initKeyboardOverlay()
        }
    }

    private fun initMouseOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        mouseParams = WindowManager.LayoutParams(
            180,
            180,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = 300
            y = 500
        }

        mouseOverlayView = DigitalMouseView(this)
        try {
            windowManager?.addView(mouseOverlayView, mouseParams)
        } catch (e: Exception) {
            Log.e("DigitalMouseService", "Failed adding mouse overlay view", e)
        }
    }

    private fun computeWindowOriginOffset(overlay: View, params: WindowManager.LayoutParams): Pair<Float, Float> {
        return try {
            if (!overlay.isAttachedToWindow) return 0f to 0f
            val loc = IntArray(2)
            overlay.getLocationOnScreen(loc)
            if (loc[0] == 0 && loc[1] == 0 && (params.x != 0 || params.y != 0)) {
                0f to 0f
            } else {
                (loc[0] - params.x).toFloat() to (loc[1] - params.y).toFloat()
            }
        } catch (_: Exception) {
            0f to 0f
        }
    }

    private fun initKeyboardOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        keyboardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 100
        }

        val frame = FrameLayout(this).apply {
            setPadding(32, 20, 32, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                colors = intArrayOf(0xEE0B0E14.toInt(), 0xEE161F30.toInt())
                setStroke(3, 0xFF00E5FF.toInt())
            }
            visibility = View.GONE
        }

        keyboardTextView = TextView(this).apply {
            text = "Myraa AI Typing..."
            setTextColor(0xFF00E5FF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        frame.addView(keyboardTextView)

        keyboardOverlayView = frame
        try {
            windowManager?.addView(keyboardOverlayView, keyboardParams)
        } catch (e: Exception) {
            Log.e("DigitalMouseService", "Failed adding keyboard overlay view", e)
        }
    }

    fun animateClick(targetX: Float, targetY: Float, onFinished: () -> Unit = {}) {
        mainHandler.post {
            val params = mouseParams
            val overlay = mouseOverlayView
            if (params == null || overlay == null) {
                MyraaAccessibilityService.instance?.dispatchClickAt(targetX, targetY) {
                    onFinished()
                } ?: onFinished()
                return@post
            }

            val (offsetX, offsetY) = computeWindowOriginOffset(overlay, params)
            val destParamX = targetX - DigitalMouseView.TIP_OFFSET_PX - offsetX
            val destParamY = targetY - DigitalMouseView.TIP_OFFSET_PX - offsetY
            val currentX = params.x.toFloat()
            val currentY = params.y.toFloat()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                addUpdateListener { va ->
                    val progress = va.animatedFraction
                    params.x = (currentX + (destParamX - currentX) * progress).toInt()
                    params.y = (currentY + (destParamY - currentY) * progress).toInt()
                    try {
                        windowManager?.updateViewLayout(overlay, params)
                    } catch (_: Exception) {}
                }
            }

            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    params.x = destParamX.toInt()
                    params.y = destParamY.toInt()
                    try {
                        windowManager?.updateViewLayout(overlay, params)
                    } catch (_: Exception) {}
                    overlay.triggerRipple()
                    MyraaAccessibilityService.instance?.dispatchClickAt(targetX, targetY) {
                        mainHandler.postDelayed({ onFinished() }, 180)
                    } ?: onFinished()
                }
            })
            animator.start()
        }
    }

    fun animateTyping(text: String, onFinished: () -> Unit = {}) {
        mainHandler.post {
            val overlay = keyboardOverlayView
            val labelView = keyboardTextView
            if (overlay == null || labelView == null) {
                MyraaAccessibilityService.instance?.typeTextIntoFocused(text)
                onFinished()
                return@post
            }

            overlay.visibility = View.VISIBLE
            labelView.text = "AI Typing: \"$text\""

            var charIndex = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (charIndex <= text.length) {
                        val partial = text.substring(0, charIndex)
                        labelView.text = "AI Typing: \"$partial\"_"
                        charIndex++
                        mainHandler.postDelayed(this, 45)
                    } else {
                        MyraaAccessibilityService.instance?.typeTextIntoFocused(text)
                        mainHandler.postDelayed({
                            overlay.visibility = View.GONE
                            onFinished()
                        }, 500)
                    }
                }
            }
            mainHandler.post(runnable)
        }
    }

    fun animateSwipe(startX: Float, startY: Float, endX: Float, endY: Float, onFinished: () -> Unit = {}) {
        mainHandler.post {
            val params = mouseParams
            val overlay = mouseOverlayView
            if (params == null || overlay == null) {
                MyraaAccessibilityService.instance?.dispatchSwipe(startX, startY, endX, endY) {
                    onFinished()
                } ?: onFinished()
                return@post
            }

            val (offsetX, offsetY) = computeWindowOriginOffset(overlay, params)
            val startParamX = startX - DigitalMouseView.TIP_OFFSET_PX - offsetX
            val startParamY = startY - DigitalMouseView.TIP_OFFSET_PX - offsetY
            val endParamX = endX - DigitalMouseView.TIP_OFFSET_PX - offsetX
            val endParamY = endY - DigitalMouseView.TIP_OFFSET_PX - offsetY

            params.x = startParamX.toInt()
            params.y = startParamY.toInt()
            try {
                windowManager?.updateViewLayout(overlay, params)
            } catch (_: Exception) {}

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                addUpdateListener { va ->
                    val progress = va.animatedFraction
                    params.x = (startParamX + (endParamX - startParamX) * progress).toInt()
                    params.y = (startParamY + (endParamY - startParamY) * progress).toInt()
                    try {
                        windowManager?.updateViewLayout(overlay, params)
                    } catch (_: Exception) {}
                }
            }

            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    MyraaAccessibilityService.instance?.dispatchSwipe(startX, startY, endX, endY) {
                        onFinished()
                    } ?: onFinished()
                }
            })
            animator.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        try {
            mouseOverlayView?.let { windowManager?.removeView(it) }
            keyboardOverlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w("DigitalMouseService", "Cleanup warning", e)
        }
        mouseOverlayView = null
        keyboardOverlayView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Myraa Digital Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            )
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myraa AI Digital Operator")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myraa_digital_mouse_channel"
        const val NOTIFICATION_ID = 2004
        var instance: DigitalMouseService? = null
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, DigitalMouseService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("DigitalMouseService", "Unable to start DigitalMouseService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DigitalMouseService::class.java)
            context.stopService(intent)
        }
    }
}

// Glowing Cyber Arrow & Tap Ripple Cursor View (tip aligned cleanly inside bounds so ripple never clips)
class DigitalMouseView(context: Context) : View(context) {

    companion object {
        const val TIP_OFFSET_PX = 48f
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.FILL
        setShadowLayer(16f, 0f, 0f, 0xFF00E5FF.toInt())
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF2E93.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var rippleRadius = 0f
    private var rippleAlpha = 0

    private val cursorPath = Path().apply {
        moveTo(TIP_OFFSET_PX, TIP_OFFSET_PX)
        lineTo(TIP_OFFSET_PX + 42f, TIP_OFFSET_PX + 28f)
        lineTo(TIP_OFFSET_PX + 25f, TIP_OFFSET_PX + 30f)
        lineTo(TIP_OFFSET_PX + 34f, TIP_OFFSET_PX + 50f)
        lineTo(TIP_OFFSET_PX + 23f, TIP_OFFSET_PX + 55f)
        lineTo(TIP_OFFSET_PX + 14f, TIP_OFFSET_PX + 34f)
        lineTo(TIP_OFFSET_PX, TIP_OFFSET_PX + 44f)
        close()
    }

    fun triggerRipple() {
        val anim = ValueAnimator.ofFloat(0f, 44f).apply {
            duration = 350
            addUpdateListener { va ->
                rippleRadius = va.animatedValue as Float
                rippleAlpha = ((1f - va.animatedFraction) * 255).toInt()
                invalidate()
            }
        }
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rippleRadius > 0f && rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(TIP_OFFSET_PX, TIP_OFFSET_PX, rippleRadius, ripplePaint)
        }
        canvas.drawPath(cursorPath, cursorPaint)
        canvas.drawPath(cursorPath, borderPaint)
    }
}
