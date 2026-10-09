package com.vix.heyvix

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "VIX logo"
            adjustViewBounds = true
        }

        layout.addView(
            logo,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(175)
            ).apply {
                bottomMargin = dp(20)
            }
        )

        val breathing = AlphaAnimation(0.88f, 1.0f).apply {
            duration = 1600
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = AlphaAnimation.INFINITE
        }
        logo.startAnimation(breathing)

        val subtitle = TextView(this).apply {
            text = "YOUR ASSISTANT. YOUR COMMANDS. YOUR AI."
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }
        layout.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(34)
            }
        )

        val status = TextView(this).apply {
            text = "VIX IS READY"
            textSize = 13f
            setTextColor(redColor)
            gravity = Gravity.CENTER
        }
        layout.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )

        val input = EditText(this).apply {
            hint = "Type a message to VIX..."
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBackground(Color.rgb(30, 30, 38), 14)
        }
        layout.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        )

        val voiceButton = Button(this).apply {
            text = "START VIX"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBackground(redColor, 14)
            isAllCaps = false
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Voice controls will be added next.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(
            voiceButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(22)
            }
        )

        val footer = TextView(this).apply {
            text = "VIX PERSONAL ASSISTANT"
            textSize = 11f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }
        layout.addView(footer)

        setContentView(layout)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }
}
