package com.vix.heyvix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

enum class VixState {
    IDLE, LISTENING, PROCESSING, SPEAKING, ERROR
}

/** Animated microphone button drawn in plain Kotlin. */
class MicrophoneView(context: Context) : View(context) {

    var state: VixState = VixState.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = when (value) {
                VixState.IDLE -> "Microphone. Tap to talk."
                VixState.LISTENING -> "Listening. Tap to stop."
                VixState.PROCESSING -> "Thinking."
                VixState.SPEAKING -> "VIX is speaking. Tap to stop."
                VixState.ERROR -> "Problem. Tap to try again."
            }
            updateAnimation()
            invalidate()
        }

    private val red = Color.rgb(255, 55, 65)
    private val dimRed = Color.rgb(120, 30, 36)
    private val twoPi = (2.0 * Math.PI).toFloat()

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var paused = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val bodyRect = RectF()
    private val arcRect = RectF()

    init {
        isClickable = true
        contentDescription = "Microphone. Tap to talk."
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val s = dp(160)
        setMeasuredDimension(
            resolveSize(s, widthMeasureSpec),
            resolveSize(s, heightMeasureSpec)
        )
    }

    fun pauseAnimation() {
        paused = true
        stopAnimator()
    }

    fun resumeAnimation() {
        paused = false
        updateAnimation()
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    private fun updateAnimation() {
        stopAnimator()

        if (paused || !isAttachedToWindow) return
        if (state == VixState.IDLE || state == VixState.ERROR) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (state) {
                VixState.LISTENING -> 1400L
                VixState.PROCESSING -> 1100L
                else -> 1800L
            }

            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()

            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }

            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.52f
        var r = base

        ringPaint.strokeWidth = dp(3).toFloat()

        when (state) {
            VixState.LISTENING -> {
                for (i in 0..1) {
                    val p = (phase + i * 0.5f) % 1f
                    ringPaint.color = red
                    ringPaint.alpha = ((1f - p) * 170).toInt()
                    canvas.drawCircle(
                        cx, cy, base + p * base * 0.65f, ringPaint
                    )
                }
            }

            VixState.SPEAKING -> {
                r = base * (1f + 0.07f * sin(phase * twoPi))
                ringPaint.color = red
                ringPaint.alpha = 90
                canvas.drawCircle(cx, cy, r * 1.25f, ringPaint)
            }

            VixState.PROCESSING -> {
                arcRect.set(
                    cx - base * 1.3f,
                    cy - base * 1.3f,
                    cx + base * 1.3f,
                    cy + base * 1.3f
                )
                ringPaint.color = red
                ringPaint.alpha = 220
                canvas.drawArc(
                    arcRect, phase * 360f, 110f, false, ringPaint
                )
            }

            else -> Unit
        }

        fillPaint.color =
            if (state == VixState.ERROR) dimRed else red

        canvas.drawCircle(cx, cy, r, fillPaint)
        drawMic(canvas, cx, cy, r)

        if (state == VixState.ERROR) {
            iconStroke.strokeWidth = r * 0.09f
            canvas.drawLine(
                cx - r * 0.5f,
                cy - r * 0.5f,
                cx + r * 0.5f,
                cy + r * 0.5f,
                iconStroke
            )
        }
    }

    private fun drawMic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float
    ) {
        val bw = r * 0.34f

        bodyRect.set(
            cx - bw / 2,
            cy - r * 0.50f,
            cx + bw / 2,
            cy + r * 0.10f
        )

        canvas.drawRoundRect(
            bodyRect, bw / 2, bw / 2, whiteFill
        )

        iconStroke.strokeWidth = r * 0.085f

        arcRect.set(
            cx - r * 0.38f,
            cy - r * 0.28f,
            cx + r * 0.38f,
            cy + r * 0.38f
        )

        canvas.drawArc(arcRect, 0f, 180f, false, iconStroke)

        canvas.drawLine(
            cx, cy + r * 0.38f,
            cx, cy + r * 0.58f,
            iconStroke
        )

        canvas.drawLine(
            cx - r * 0.2f, cy + r * 0.58f,
            cx + r * 0.2f, cy + r * 0.58f,
            iconStroke
        )
    }
}
