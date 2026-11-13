package com.example.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
        startForeground(NOTIFICATION_ID, buildNotification("Myraa AI Digital Mouse & Screen Operator Active"))
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
            160,
            160,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 300
            y = 500
        }

        mouseOverlayView = DigitalMouseView(this)
        try {
            windowManager?.addView(mouseOverlayView, mouseParams)
        } catch (e: Exception) {
            e.printStackTrace()
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
            text = "⌨️ Myraa AI Typing..."
            setTextColor(0xFF00E5FF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        frame.addView(keyboardTextView)

        keyboardOverlayView = frame
        try {
            windowManager?.addView(keyboardOverlayView, keyboardParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun animateClick(targetX: Float, targetY: Float, onFinished: () -> Unit = {}) {
        val params = mouseParams ?: return
        val currentX = params.x.toFloat()
        val currentY = params.y.toFloat()

        // 1. Smoothly glide cursor to target
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            addUpdateListener { va ->
                val progress = va.animatedFraction
                params.x = (currentX + (targetX - currentX) * progress).toInt()
                params.y = (currentY + (targetY - currentY) * progress).toInt()
                try {
                    windowManager?.updateViewLayout(mouseOverlayView, params)
                } catch (ignored: Exception) {}
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 2. Trigger ripple click effect on cursor view
                mouseOverlayView?.triggerRipple()

                // 3. Physically inject tap into underlying screen
                MyraaAccessibilityService.instance?.dispatchClickAt(targetX, targetY) {
                    mainHandler.postDelayed({ onFinished() }, 200)
                }
            }
        })
        animator.start()
    }

    fun animateTyping(text: String, onFinished: () -> Unit = {}) {
        keyboardOverlayView?.visibility = View.VISIBLE
        keyboardTextView?.text = "⌨️ AI Typing: \"$text\""

        var charIndex = 0
        val runnable = object : Runnable {
            override fun run() {
                if (charIndex <= text.length) {
                    val partial = text.substring(0, charIndex)
                    keyboardTextView?.text = "⌨️ AI Typing: \"$partial\"▌"
                    charIndex++
                    mainHandler.postDelayed(this, 50)
                } else {
                    // Inject real text into screen
                    MyraaAccessibilityService.instance?.typeTextIntoFocused(text)
                    mainHandler.postDelayed({
                        keyboardOverlayView?.visibility = View.GONE
                        onFinished()
                    }, 600)
                }
            }
        }
        mainHandler.post(runnable)
    }

    fun animateSwipe(startX: Float, startY: Float, endX: Float, endY: Float, onFinished: () -> Unit = {}) {
        val params = mouseParams ?: return
        params.x = startX.toInt()
        params.y = startY.toInt()
        try {
            windowManager?.updateViewLayout(mouseOverlayView, params)
        } catch (ignored: Exception) {}

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { va ->
                val progress = va.animatedFraction
                params.x = (startX + (endX - startX) * progress).toInt()
                params.y = (startY + (endY - startY) * progress).toInt()
                try {
                    windowManager?.updateViewLayout(mouseOverlayView, params)
                } catch (ignored: Exception) {}
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                MyraaAccessibilityService.instance?.dispatchSwipe(startX, startY, endX, endY) {
                    onFinished()
                }
            }
        })
        animator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            if (mouseOverlayView != null) windowManager?.removeView(mouseOverlayView)
            if (keyboardOverlayView != null) windowManager?.removeView(keyboardOverlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        const val NOTIFICATION_ID = 2002
        var instance: DigitalMouseService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, DigitalMouseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DigitalMouseService::class.java)
            context.stopService(intent)
        }
    }
}

// Glowing Cyber Arrow & Tap Ripple Cursor View
class DigitalMouseView(context: Context) : View(context) {

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
        moveTo(0f, 0f)
        lineTo(40f, 26f)
        lineTo(24f, 28f)
        lineTo(32f, 48f)
        lineTo(22f, 52f)
        lineTo(14f, 32f)
        lineTo(0f, 42f)
        close()
    }

    fun triggerRipple() {
        val anim = ValueAnimator.ofFloat(0f, 50f).apply {
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
        // Draw ripple click wave
        if (rippleRadius > 0f && rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(0f, 0f, rippleRadius, ripplePaint)
        }
        // Draw cyber mouse cursor
        canvas.drawPath(cursorPath, cursorPaint)
        canvas.drawPath(cursorPath, borderPaint)
    }
}
