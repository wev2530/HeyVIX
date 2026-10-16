package com.vix.heyvix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The first screen when VIX opens: shows the logo for a moment,
 * then goes to the app (logged in) or to the login pages (logged out).
 */
class SplashActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val handler = Handler(Looper.getMainLooper())
    private val route = Runnable { goNext() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val root = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "VIX logo"
            startAnimation(
                AlphaAnimation(0f, 1f).apply {
                    duration = 600
                    fillAfter = true
                }
            )
        }

        root.addView(
            logo,
            FrameLayout.LayoutParams(dp(240), dp(130), Gravity.CENTER)
        )

        setContentView(root)

        handler.postDelayed(route, 900)
    }

    private fun goNext() {
        val target = if (SupabaseAuth.isLoggedIn(this)) {
            MainActivity::class.java
        } else {
            AuthActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(route)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}