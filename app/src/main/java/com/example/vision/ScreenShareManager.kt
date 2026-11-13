package com.example.vision

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.example.service.DigitalMouseService
import com.example.service.MyraaAccessibilityService
import com.example.service.ScreenUiElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenShareManager(private val context: Context) {

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    fun startScreenShare() {
        _isScreenSharing.value = true
        DigitalMouseService.start(context)
        Log.i("ScreenShareManager", "Screen share and AI vision started")
    }

    fun stopScreenShare() {
        _isScreenSharing.value = false
        DigitalMouseService.stop(context)
        Log.i("ScreenShareManager", "Screen share stopped")
    }

    fun toggleScreenShare() {
        if (_isScreenSharing.value) stopScreenShare() else startScreenShare()
    }

    // AI scans on-screen elements to find matching target and physically operates it
    fun executeHandsFreeScreenAction(
        targetQuery: String,
        actionType: String, // "CLICK", "TYPE", "SCROLL_DOWN", "SCROLL_UP"
        textToType: String = "",
        onCompleted: (Boolean, String) -> Unit
    ) {
        val accessibility = MyraaAccessibilityService.instance
        if (accessibility == null) {
            onCompleted(false, "Accessibility Service is not enabled. Please enable it in Settings.")
            return
        }

        when (actionType) {
            "SCROLL_DOWN" -> {
                DigitalMouseService.instance?.animateSwipe(500f, 1400f, 500f, 600f) {
                    onCompleted(true, "Scrolled down screen")
                } ?: run {
                    accessibility.scroll(true)
                    onCompleted(true, "Scrolled down screen")
                }
            }
            "SCROLL_UP" -> {
                DigitalMouseService.instance?.animateSwipe(500f, 600f, 500f, 1400f) {
                    onCompleted(true, "Scrolled up screen")
                } ?: run {
                    accessibility.scroll(false)
                    onCompleted(true, "Scrolled up screen")
                }
            }
            "TYPE" -> {
                val elements = accessibility.getVisibleScreenElements()
                val target = elements.firstOrNull { it.isEditable }
                val targetPoint = target?.bounds ?: Rect(300, 800, 700, 900)

                DigitalMouseService.instance?.animateClick(
                    targetPoint.centerX().toFloat(),
                    targetPoint.centerY().toFloat()
                ) {
                    DigitalMouseService.instance?.animateTyping(textToType) {
                        onCompleted(true, "Typed \"$textToType\" into screen field")
                    }
                } ?: run {
                    accessibility.typeTextIntoFocused(textToType)
                    onCompleted(true, "Typed \"$textToType\"")
                }
            }
            "CLICK" -> {
                val elements = accessibility.getVisibleScreenElements()
                val match = elements.firstOrNull {
                    it.text.contains(targetQuery, ignoreCase = true)
                }

                if (match != null) {
                    val x = match.bounds.centerX().toFloat()
                    val y = match.bounds.centerY().toFloat()
                    DigitalMouseService.instance?.animateClick(x, y) {
                        onCompleted(true, "Clicked on \"${match.text}\" at ($x, $y)")
                    } ?: run {
                        accessibility.dispatchClickAt(x, y) {
                            onCompleted(true, "Clicked on \"${match.text}\"")
                        }
                    }
                } else {
                    // Try clicking by query text directly
                    val clicked = accessibility.clickByText(targetQuery)
                    onCompleted(clicked, if (clicked) "Clicked element \"$targetQuery\"" else "Element \"$targetQuery\" not found on screen")
                }
            }
            else -> onCompleted(false, "Unknown screen action")
        }
    }
}
