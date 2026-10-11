package com.vix.heyvix

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * "VIX AI is typing" bubble: the VIX logo gently grows and shrinks
 * (like breathing) next to three dots that bounce one after another,
 * similar to the typing indicator in WhatsApp.
 *
 * Uses only Android framework classes, no extra libraries.
 */
class TypingIndicatorView(context: Context) : LinearLayout(context) {

    private val logo: ImageView
    private val dots = ArrayList<View>()
    private val animators = ArrayList<Animator>()

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        contentDescription = "VIX AI is typing"

        background = GradientDrawable().apply {
            setColor(Color.rgb(28, 28, 36))
            cornerRadius = dp(16).toFloat()
        }

        logo = ImageView(context).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        addView(logo, LinearLayout.LayoutParams(dp(56), dp(24)))

        for (index in 0 until 3) {
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(165, 165, 175))
                }
                alpha = 0.35f
            }

            dots.add(dot)

            addView(
                dot,
                LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(if (index == 0) 10 else 5)
                }
            )
        }
    }

    /** Start the breathing logo and the bouncing dots. */
    fun start() {
        stop()

        // Logo "breathing": grow and shrink slowly, forever.
        animators.add(loop(ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.86f, 1.08f), 900L, 0L))
        animators.add(loop(ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.86f, 1.08f), 900L, 0L))
        animators.add(loop(ObjectAnimator.ofFloat(logo, View.ALPHA, 0.55f, 1f), 900L, 0L))

        // Three dots: each one jumps slightly after the previous one.
        dots.forEachIndexed { index, dot ->
            val delay = index * 160L
            animators.add(
                loop(
                    ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(5).toFloat()),
                    420L,
                    delay
                )
            )
            animators.add(
                loop(ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f), 420L, delay)
            )
        }

        animators.forEach { it.start() }
    }

    /** Stop all animations and reset the views. */
    fun stop() {
        animators.forEach { it.cancel() }
        animators.clear()

        logo.scaleX = 1f
        logo.scaleY = 1f
        logo.alpha = 1f

        dots.forEach {
            it.translationY = 0f
            it.alpha = 0.35f
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun loop(
        animator: ObjectAnimator,
        durationMs: Long,
        delayMs: Long
    ): ObjectAnimator {
        return animator.apply {
            duration = durationMs
            startDelay = delayMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
