package com.vix.heyvix

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Hold either volume button for half a second to open the VIX overlay.
 * A short press still changes the volume like normal.
 *
 * Needs the user to turn on "VIX volume wake" in
 * Settings > Accessibility. It only reads key presses, not the screen.
 */
class VixVolumeService : AccessibilityService() {

    companion object {
        private const val HOLD_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var downKey = -1
    private var fired = false

    private var panel: VixOverlayPanel? = null
    private var overlayView: View? = null

    private val holdRunnable = Runnable {
        fired = true
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ------------------------------------------------------------
    // Volume keys
    // ------------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP &&
            code != KeyEvent.KEYCODE_VOLUME_DOWN
        ) return false

        // While the overlay is open, volume keys work normally.
        if (overlayView != null) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downKey = code
                    fired = false
                    handler.removeCallbacks(holdRunnable)
                    handler.postDelayed(holdRunnable, HOLD_MS)
                }
                return true // swallow, so holding doesn't spam the volume
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                val wasShortPress = downKey == code && !fired
                downKey = -1
                if (wasShortPress) adjustVolume(code)
                return true
            }
        }
        return false
    }

    private fun adjustVolume(code: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.adjustSuggestedStreamVolume(
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            },
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return

        try {
            // Pause the "Hey VIX" listener so the mic is free.
            WakeWordService.setUiVisible(true)

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val p = VixOverlayPanel(
                this,
                { command ->
                    openApp(command)
                    hideOverlay()
                },
                { hideOverlay() }
            )

            val root = p.root
            root.isFocusableInTouchMode = true
            root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    hideOverlay()
                    true
                } else false
            }

            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            windowManager.addView(root, params)
            panel = p
            overlayView = root

            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            slideIn(root)

            p.onShown()
            root.requestFocus()
        } catch (_: Exception) {
            panel = null
            overlayView = null
            WakeWordService.setUiVisible(false)
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val p = panel

        // Mark closed right away so a second call does nothing.
        overlayView = null
        panel = null

        p?.onHidden()
        slideOut(view) {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {
            }
            WakeWordService.setUiVisible(false)
        }
    }

    // Default entrance and exit animation (placeholder until the
    // Figma design arrives): dark backdrop fades, sheet slides up.
    private fun slideIn(root: View) {
        val sheet = (root as? ViewGroup)?.getChildAt(0)
        root.alpha = 0f
        sheet?.translationY = 0f
        root.post {
            val rise = (sheet?.height ?: 0).toFloat().coerceAtLeast(300f)
            sheet?.translationY = rise
            root.animate().alpha(1f).setDuration(180).start()
            sheet?.animate()
                ?.translationY(0f)
                ?.setDuration(280)
                ?.setInterpolator(DecelerateInterpolator(2f))
                ?.start()
        }
    }

    private fun slideOut(root: View, done: () -> Unit) {
        val sheet = (root as? ViewGroup)?.getChildAt(0)
        sheet?.animate()
            ?.translationY((sheet.height).toFloat())
            ?.setDuration(200)
            ?.setInterpolator(AccelerateInterpolator())
            ?.start()
        root.animate().alpha(0f).setDuration(200).withEndAction(done).start()
    }

    private fun openApp(command: String?) {
        try {
            val intent = Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            if (!command.isNullOrBlank()) {
                intent.putExtra(WakeWordService.EXTRA_WAKE, true)
                intent.putExtra(WakeWordService.EXTRA_WAKE_COMMAND, command)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(holdRunnable)
        overlayView?.let {
            panel?.onHidden()
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        panel = null
        WakeWordService.setUiVisible(false)
        return super.onUnbind(intent)
    }
}
