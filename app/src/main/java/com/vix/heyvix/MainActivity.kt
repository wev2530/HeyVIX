package com.vix.heyvix

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(15, 15, 20))
        }

        val title = TextView(this).apply {
            text = "HEY VIX"
            textSize = 36f
            setTextColor(Color.rgb(255, 55, 65))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Your assistant. Your commands. Your AI."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }

        val button = Button(this).apply {
            text = "START VIX"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Hey VIX voice controls are coming next!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val status = TextView(this).apply {
            text = "VIX is ready for setup"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(button)
        layout.addView(status)

        setContentView(layout)
    }
}
