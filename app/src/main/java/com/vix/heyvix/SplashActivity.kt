package com.vix.heyvix

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/**
 * The first screen when VIX opens: shows the logo for a moment, checks
 * whether a newer build was released, then goes to the app (logged in)
 * or to the login pages (logged out).
 *
 * If a newer build exists, a dialog that cannot be dismissed asks the user
 * to update before going further.
 */
class SplashActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val handler = Handler(Looper.getMainLooper())

    private var minimumTimePassed = false
    private var checkFinished = false
    private var update: UpdateChecker.Update? = null
    private var routed = false

    private val minimumTime = Runnable {
        minimumTimePassed = true
        proceed()
    }

    // Safety net: never wait more than a few seconds for the update check.
    private val giveUp = Runnable {
        checkFinished = true
        minimumTimePassed = true
        proceed()
    }

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

        UpdateChecker.check(this) { found ->
            if (routed) return@check
            update = found
            checkFinished = true
            proceed()
        }

        handler.postDelayed(minimumTime, 900)
        handler.postDelayed(giveUp, 4500)
    }

    /** Goes on once the logo has been shown long enough and the check is done. */
    private fun proceed() {
        if (routed || isFinishing || isDestroyed) return
        if (!minimumTimePassed || !checkFinished) return

        routed = true
        handler.removeCallbacks(giveUp)

        val found = update
        if (found != null) {
            showUpdateDialog(found)
        } else {
            goNext()
        }
    }

    private fun showUpdateDialog(found: UpdateChecker.Update) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(
                "A new version of VIX is ready (${found.name}).\n\n" +
                    "Please update to continue. When the download finishes, open it " +
                    "and tap Update. Your account and chats stay as they are."
            )
            .setCancelable(false)
            .setPositiveButton("Update", null)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // Replace the click so the dialog stays open after tapping Update.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            openDownload()
        }
    }

    private fun openDownload() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.APK_URL)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "I couldn't open the browser to download the update.",
                Toast.LENGTH_LONG
            ).show()
        }
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
        handler.removeCallbacks(minimumTime)
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}