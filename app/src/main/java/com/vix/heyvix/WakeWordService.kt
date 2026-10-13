package com.vix.heyvix

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast

/**
 * Listens in the background for "Hey VIX", "Hi VIX" or "Hello VIX".
 * When it hears one, it opens VIX (and passes along anything said
 * after the greeting, like "hey VIX open camera").
 *
 * It only listens while the VIX screen is NOT open, because the
 * microphone can only be used by one recognizer at a time.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.vix.heyvix.action.WAKE_STOP"
        const val EXTRA_WAKE = "vix_wake"
        const val EXTRA_WAKE_COMMAND = "vix_wake_command"

        const val PREFS = "vix_prefs"
        const val KEY_ENABLED = "wake_enabled"

        private const val CHANNEL_ID = "vix_wake_word"
        private const val NOTIFICATION_ID = 4202

        // The speech engine often hears "VIX" as something similar.
        // Add more spellings here if VIX misses you.
        private val wakeRegex = Regex(
            "\\b(hey|hi|hello|okay|ok)\\s+" +
                "(vix|vics|vicks|vic|fix|bix|wicks|six)\\b"
        )

        @Volatile
        var uiVisible = false
            private set

        private var instance: WakeWordService? = null

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** MainActivity calls this when the VIX screen shows or hides. */
        fun setUiVisible(visible: Boolean) {
            uiVisible = visible
            val service = instance ?: return
            service.mainHandler.post { service.refreshListening() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var waking = false

    // The VIX overlay panel shown over other apps after "Hey VIX".
    private var overlayPanel: VixOverlayPanel? = null
    private var overlayView: View? = null

    private val restartRunnable = Runnable { startRecognizer() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        showForeground()
        active = true
        refreshListening()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------
    // Listening loop
    // ------------------------------------------------------------

    private fun refreshListening() {
        if (!active) return

        if (uiVisible || waking) {
            stopRecognizer()
        } else if (recognizer == null) {
            startRecognizer()
        }
    }

    private fun startRecognizer() {
        if (!active || uiVisible || waking) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            disableSelf("This phone has no speech recognition service, so wake words can't work.")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            disableSelf("Microphone permission is missing, so the wake word was turned off.")
            return
        }

        stopRecognizer()

        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = newRecognizer
        newRecognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

        try {
            newRecognizer.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(2000)
        }
    }

    private fun stopRecognizer() {
        mainHandler.removeCallbacks(restartRunnable)

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    disableSelf("Microphone permission is missing, so the wake word was turned off.")

                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    scheduleRestart(300)

                else -> scheduleRestart(2000)
            }
        }

        override fun onResults(results: Bundle?) {
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()

            for (phrase in heard) {
                val text = phrase.lowercase()
                    .replace(Regex("[^a-z0-9\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val match = wakeRegex.find(text)
                if (match != null) {
                    val rest = text.substring(match.range.last + 1).trim()
                    onWake(rest)
                    return
                }
            }

            scheduleRestart(200)
        }
    }

    // ------------------------------------------------------------
    // Wake
    // ------------------------------------------------------------

    private fun onWake(command: String) {
        waking = true
        stopRecognizer()

        // "Hey VIX" on its own: show the overlay over the current app.
        if (command.isEmpty() && Settings.canDrawOverlays(this)) {
            if (showOverlay()) {
                // Listening resumes when the overlay is closed.
                return
            }
        }

        // "Hey VIX open camera" (or no overlay permission): open the app.
        openApp(command)

        // If VIX didn't open, start listening again after a few seconds.
        mainHandler.postDelayed({
            waking = false
            refreshListening()
        }, 8000)
    }

    private fun openApp(command: String?) {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )

            if (!command.isNullOrBlank()) {
                intent.putExtra(EXTRA_WAKE, true)
                intent.putExtra(EXTRA_WAKE_COMMAND, command)
            } else if (command == "") {
                // Plain wake: open VIX and start listening.
                intent.putExtra(EXTRA_WAKE, true)
            }

            startActivity(intent)
        } catch (_: Exception) {
            // If Android blocks it, we resume listening.
        }
    }

    // ------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------

    private fun showOverlay(): Boolean {
        if (overlayView != null) return true

        return try {
            val windowManager = getSystemService(WINDOW_SERVICE)
                as WindowManager

            val panel = VixOverlayPanel(
                this,
                { command ->
                    // "Open VIX" button, or a command that needs the app.
                    if (command.isNullOrBlank()) {
                        openApp(null)
                    } else {
                        openApp(command)
                    }
                    hideOverlay()
                },
                { hideOverlay() }
            )

            val root = panel.root
            root.isFocusableInTouchMode = true
            root.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    hideOverlay()
                    true
                } else {
                    false
                }
            }

            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            windowManager.addView(root, params)

            overlayPanel = panel
            overlayView = root

            panel.onShown()
            root.requestFocus()
            true
        } catch (_: Exception) {
            overlayPanel = null
            overlayView = null
            false
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return

        overlayPanel?.onHidden()
        overlayPanel = null
        overlayView = null

        try {
            val windowManager = getSystemService(WINDOW_SERVICE)
                as WindowManager
            windowManager.removeView(view)
        } catch (_: Exception) {
        }

        waking = false
        refreshListening()
    }

    private fun disableSelf(message: String) {
        setEnabled(this, false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopRecognizer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------

    private fun showForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE)
            as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VIX wake word",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = Intent(this, WakeWordService::class.java)
            .setAction(ACTION_STOP)

        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VIX")
            .setContentText("Listening for \"Hey VIX\"")
            .setOngoing(true)

        @Suppress("DEPRECATION")
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Turn off",
            stopPending
        )

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        active = false
        hideOverlay()
        mainHandler.removeCallbacksAndMessages(null)
        stopRecognizer()
        instance = null
        super.onDestroy()
    }
}
