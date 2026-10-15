package com.vix.heyvix

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A chat row you can swipe to the right (like WhatsApp) to reply to it.
 * Vertical scrolling and text selection still work.
 */
class SwipeReplyLayout(
    context: Context,
    private val onSwipeReply: () -> Unit
) : LinearLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private val triggerPx = 64 * density
    private val maxPx = 90 * density

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var fired = false

    private fun startsDrag(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        return dx > slop && dx > abs(dy) * 1.5f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                fired = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && startsDrag(ev)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                fired = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && startsDrag(ev)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val dx = (ev.rawX - downX).coerceIn(0f, maxPx)
                    translationX = dx
                    if (!fired && dx >= triggerPx) {
                        fired = true
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                animate().translationX(0f).setDuration(160).start()
                if (ev.actionMasked == MotionEvent.ACTION_UP && wasDragging && fired) {
                    onSwipeReply()
                }
                return wasDragging
            }
        }
        return dragging
    }
}