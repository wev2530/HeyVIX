package com.vix.heyvix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The developer business card, used in the app and in the overlay.
 *
 * Optional images (drop a file with this name into res/drawable):
 *   gcv_logo     the GCV Designs logo (placeholder "GCV" until then)
 *   ic_whatsapp  the WhatsApp icon (placeholder bubble until then)
 */
object DeveloperCard {

    private const val GCV_LOGO = "gcv_logo"
    private const val WHATSAPP_ICON = "ic_whatsapp"

    fun build(context: Context, onWhatsApp: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

        fun drawableId(name: String) =
            context.resources.getIdentifier(name, "drawable", context.packageName)

        val muted = Color.rgb(165, 165, 175)
        val white = Color.rgb(245, 245, 248)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(36, 36, 46))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(90, 255, 55, 65))
            }
        }

        // VIX logo
        card.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.vix_logo_lockup_dark)
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                contentDescription = "VIX AI logo"
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        // Divider
        card.addView(
            View(context).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        )

        // Logo slot + name
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val gcvId = drawableId(GCV_LOGO)
        val slot: View = if (gcvId != 0) {
            ImageView(context).apply {
                setImageResource(gcvId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = rounded(Color.rgb(48, 48, 58), 14)
                contentDescription = "GCV Designs logo"
            }
        } else {
            TextView(context).apply {
                text = "GCV"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                setTextColor(white)
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(48, 48, 58), 14)
            }
        }
        row.addView(slot, LinearLayout.LayoutParams(dp(60), dp(60)).apply {
            marginEnd = dp(14)
        })

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = DeveloperInfo.NAME
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        })
        texts.addView(TextView(context).apply {
            text = "Developer of VIX AI"
            textSize = 12f
            setTextColor(muted)
        })
        texts.addView(TextView(context).apply {
            text = "Upcoming apps and AI"
            textSize = 12f
            setTextColor(muted)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        card.addView(row)

        // WhatsApp button
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(37, 211, 102), 24)
            isClickable = true
            contentDescription = "Message the developer on WhatsApp"
            setOnClickListener { onWhatsApp() }
        }

        val waId = drawableId(WHATSAPP_ICON)
        if (waId != 0) {
            button.addView(
                ImageView(context).apply {
                    setImageResource(waId)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
            )
        } else {
            button.addView(
                TextView(context).apply {
                    text = "💬"
                    textSize = 18f
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
        button.addView(TextView(context).apply {
            text = "Message on WhatsApp"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(7, 30, 16))
        })

        card.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(16) }
        )

        return card
    }
}