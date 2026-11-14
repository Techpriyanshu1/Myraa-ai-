package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenUiElement(
    val text: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val className: String
)

class MyraaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i("MyraaAccessibility", "Myraa Accessibility Service connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w("MyraaAccessibility", "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
    }

    // Physical tap injection at screen coordinates (x, y)
    fun dispatchClickAt(x: Float, y: Float, onComplete: () -> Unit = {}): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 28)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete()
            }
        }, null)
    }

    // Physical swipe injection (e.g. scroll up/down, next YouTube Short / Reel, or swipe left/right)
    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 260L,
        onComplete: () -> Unit = {}
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete()
            }
        }, null)
    }

    /**
     * Smart screen scroll that works reliably across YouTube Shorts, Reels, feeds, and lists:
     * dispatches a real physical vertical swipe gesture scaled to device dimensions, with node scroll fallback.
     */
    fun scrollSmart(forward: Boolean): Boolean {
        val (w, h) = getScreenDimensions()
        val cx = w * 0.5f
        val startY = if (forward) h * 0.78f else h * 0.25f
        val endY = if (forward) h * 0.22f else h * 0.78f

        val swiped = dispatchSwipe(cx, startY, cx, endY, durationMs = 130L)
        if (swiped) return true
        return scroll(forward)
    }

    fun clampToScreen(x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = getScreenDimensions()
        val clampedX = x.coerceIn(2f, (w - 2f).coerceAtLeast(2f))
        val clampedY = y.coerceIn(2f, (h - 2f).coerceAtLeast(2f))
        return clampedX to clampedY
    }

    fun findBestClickableElement(query: String): ScreenUiElement? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return null
        val (w, h) = getScreenDimensions()
        val candidates = mutableListOf<ScreenUiElement>()

        val root = rootInActiveWindow
        if (root != null) {
            collectBestCandidatesForQuery(root, cleanQuery, w, h, candidates)
        }
        if (candidates.isEmpty()) {
            candidates.addAll(getVisibleScreenElements())
        }
        return pickBestClickableElement(
            elements = candidates,
            query = cleanQuery,
            screenWidth = w.toInt(),
            screenHeight = h.toInt()
        )
    }

    private fun collectBestCandidatesForQuery(
        node: AccessibilityNodeInfo?,
        query: String,
        screenWidth: Float,
        screenHeight: Float,
        out: MutableList<ScreenUiElement>
    ) {
        if (node == null) return
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        if (label.isNotBlank() && (label.contains(query, ignoreCase = true) || query.contains(label, ignoreCase = true))) {
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            var chosenBounds = nodeBounds
            var chosenClickable = node.isClickable || node.isEditable

            if (!chosenClickable) {
                val screenArea = screenWidth * screenHeight
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable || parent.isEditable) {
                        val pBounds = Rect()
                        parent.getBoundsInScreen(pBounds)
                        val pArea = pBounds.width().toFloat() * pBounds.height().toFloat()
                        if (pBounds.width() > 2 && pBounds.height() > 2 && pArea < screenArea * 0.60f) {
                            chosenBounds = pBounds
                            chosenClickable = true
                            break
                        }
                    }
                    parent = parent.parent
                }
            }

            out.add(
                ScreenUiElement(
                    text = label,
                    bounds = chosenBounds,
                    isClickable = chosenClickable,
                    isEditable = node.isEditable,
                    className = node.className?.toString() ?: ""
                )
            )
        }

        for (i in 0 until node.childCount) {
            collectBestCandidatesForQuery(node.getChild(i), query, screenWidth, screenHeight, out)
        }
    }

    private fun getScreenDimensions(): Pair<Float, Float> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width().coerceAtLeast(720).toFloat(), bounds.height().coerceAtLeast(1280).toFloat())
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels.coerceAtLeast(720).toFloat(), metrics.heightPixels.coerceAtLeast(1280).toFloat())
            }
        } catch (_: Exception) {
            Pair(1080f, 2200f)
        }
    }

    // AI Virtual Keyboard text injection into focused field (fast SET_TEXT + clipboard ACTION_PASTE fallback)
    fun typeTextIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return false

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return true
        }
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("MYRAA Input", text))
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFirstEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    // Screen Share Vision: reads all visible on-screen elements for AI reasoning
    fun getVisibleScreenElements(): List<ScreenUiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenUiElement>()
        collectNodes(root, elements)
        return elements
    }

    fun getCompactScreenContextSummary(): String {
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        val labels = getVisibleScreenElements()
            .asSequence()
            .map { it.text.trim() }
            .filter { it.isNotBlank() && it.length <= 60 }
            .distinct()
            .take(18)
            .toList()
        if (activePkg.isBlank() && labels.isEmpty()) return ""
        return buildString {
            if (activePkg.isNotBlank()) append("Active app package: $activePkg. ")
            if (labels.isNotEmpty()) append("Visible UI elements: ${labels.joinToString(" | ")}")
        }.trim()
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, list: MutableList<ScreenUiElement>) {
        if (node == null) return
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotBlank() || node.isClickable || node.isEditable) {
            list.add(
                ScreenUiElement(
                    text = text,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    className = node.className?.toString() ?: ""
                )
            )
        }

        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), list)
        }
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performScrollOnNode(root, action)
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if (node == null) return false
        if (node.actionList.any { it.id == action }) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (performScrollOnNode(child, action)) return true
        }
        return false
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(query)
        for (node in nodes) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
        }
        return false
    }

    companion object {
        var instance: MyraaAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        fun isRunning(): Boolean = instance != null

        fun pickBestClickableElement(
            elements: List<ScreenUiElement>,
            query: String,
            screenWidth: Int = 1080,
            screenHeight: Int = 2200
        ): ScreenUiElement? {
            val q = query.trim().lowercase()
            if (q.isBlank()) return null
            val screenRect = Rect(0, 0, screenWidth.coerceAtLeast(1), screenHeight.coerceAtLeast(1))
            val screenArea = screenWidth.toFloat().coerceAtLeast(1f) * screenHeight.toFloat().coerceAtLeast(1f)
            val wordRegex = Regex("\\b${Regex.escape(q)}\\b")

            data class Scored(val element: ScreenUiElement, val score: Int, val area: Long)

            val scored = elements.mapNotNull { el ->
                val b = el.bounds
                if (b.width() <= 2 || b.height() <= 2) return@mapNotNull null
                if (!Rect.intersects(screenRect, b)) return@mapNotNull null

                val elText = el.text.trim().lowercase()
                if (elText.isBlank()) return@mapNotNull null

                val matchScore = when {
                    elText == q -> 1000
                    wordRegex.containsMatchIn(elText) -> 600
                    elText.contains(q) -> 350
                    elText.length >= 3 && q.contains(elText) -> 220
                    else -> return@mapNotNull null
                }

                var totalScore = matchScore
                if (el.isClickable || el.isEditable) totalScore += 250
                val area = b.width().toLong() * b.height().toLong()
                if (area > screenArea * 0.60f) totalScore -= 450

                Scored(el, totalScore, area)
            }

            return scored
                .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.area })
                .firstOrNull()
                ?.element
        }
    }
}
