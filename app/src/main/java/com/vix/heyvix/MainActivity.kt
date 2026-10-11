package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val panelColor = Color.rgb(28, 28, 36)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)
    private val userBubbleColor = Color.rgb(48, 31, 36)

    private lateinit var status: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micButton: Button

    private lateinit var commandRouter: LocalCommandRouter
    private lateinit var voiceController: VoiceController
    private lateinit var speechOutput: VixSpeechOutput
    private lateinit var aiApiClient: AiApiClient

    private var pendingCommand: String? = null
    private var listening = false
    private var requestInProgress = false

    // The "VIX AI is typing" row shown while waiting for the AI.
    private var typingRow: LinearLayout? = null

    // Screenshot / screen recording waiting for Android's permission dialog.
    private var pendingScreenAction: ScreenCaptureController.Action? = null

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
        private const val SCREEN_CAPTURE_REQUEST = 1003
        private const val NOTIFICATION_PERMISSION_REQUEST = 1004
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        commandRouter = LocalCommandRouter(this)
        aiApiClient = AiApiClient(this)

        buildInterface()
        setupVoice()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(backgroundColor)
        }

        // VIX branding
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "VIX AI logo"
            adjustViewBounds = true
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )

        val readyDot = TextView(this).apply {
            text = "●"
            textSize = 12f
            setTextColor(Color.rgb(68, 220, 130))
            gravity = Gravity.CENTER
        }

        header.addView(
            readyDot,
            LinearLayout.LayoutParams(dp(18), dp(28))
        )

        val readyLabel = TextView(this).apply {
            text = "VIX AI"
            textSize = 10f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(readyLabel)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        status = TextView(this).apply {
            text = "YOUR PERSONAL ASSISTANT"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(12))
        }

        root.addView(status)

        // Conversation history
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }

        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(conversation)
        }

        root.addView(
            conversationScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        addMessage(
            "Hi, I'm VIX AI. Ask me a question, request coding help, " +
                "or use a supported phone command.",
            false
        )

        addSuggestions()

        // Message composer
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(6), dp(6))
            background = roundedBackground(panelColor, 20)
        }

        input = EditText(this).apply {
            hint = "Message VIX AI..."
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(8), dp(10), dp(4), dp(10))
            background = null
        }

        composer.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        // Inline microphone
        micButton = Button(this).apply {
            text = "🎙"
            textSize = 18f
            isAllCaps = false
            setTextColor(textColor)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = roundedBackground(
                Color.rgb(48, 48, 58),
                14
            )
            contentDescription = "Speak to VIX AI"

            setOnClickListener {
                if (listening) {
                    voiceController.stop()
                    listening = false
                    text = "🎙"
                    status.text = "YOUR PERSONAL ASSISTANT"
                } else {
                    startVoiceInput()
                }
            }
        }

        composer.addView(
            micButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(4)
            }
        )

        // Send button
        val sendButton = Button(this).apply {
            text = "➤"
            textSize = 20f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = roundedBackground(redColor, 14)
            contentDescription = "Send message"

            setOnClickListener {
                submitTypedMessage()
            }
        }

        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(5)
            }
        )

        root.addView(
            composer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        val footer = TextView(this).apply {
            text = "VIX AI • PRIVATE PHONE ASSISTANT"
            textSize = 9f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(2))
        }

        root.addView(footer)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedMessage()
                true
            } else {
                false
            }
        }

        setContentView(root)
    }

    private fun addSuggestions() {
        val heading = TextView(this).apply {
            text = "TRY A QUICK COMMAND"
            textSize = 10f
            setTextColor(mutedColor)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }

        conversation.addView(heading)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        addSuggestion(row, "Flashlight on", "Turn on flashlight")
        addSuggestion(row, "Open camera", "Open camera")

        conversation.addView(row)
    }

    private fun addSuggestion(
        row: LinearLayout,
        label: String,
        command: String
    ) {
        val chip = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(textColor)
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedBackground(panelColor, 16)

            setOnClickListener {
                input.setText(command)
                input.setSelection(input.text.length)
                input.requestFocus()
            }
        }

        row.addView(
            chip,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(6)
            }
        )
    }

    private fun addMessage(
        message: String,
        fromUser: Boolean
    ) {
        val bubble = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(textColor)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = roundedBackground(
                if (fromUser) userBubbleColor else panelColor,
                16
            )
        }

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))

            addView(
                bubble,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0.88f
                )
            )
        }

        conversation.addView(line)

        conversationScroll.post {
            conversationScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ----------------------------------------------------------
    // "VIX AI is typing" indicator (breathing logo + three dots)
    // ----------------------------------------------------------

    private fun showTyping() {
        if (typingRow != null) {
            return
        }

        val indicator = TypingIndicatorView(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(5), 0, dp(5))

            addView(
                indicator,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        conversation.addView(row)
        indicator.start()
        typingRow = row

        conversationScroll.post {
            conversationScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun hideTyping() {
        val row = typingRow ?: return

        (row.getChildAt(0) as? TypingIndicatorView)?.stop()
        conversation.removeView(row)
        typingRow = null
    }

    private fun submitTypedMessage() {
        val command = input.text.toString().trim()

        if (command.isEmpty()) {
            Toast.makeText(
                this,
                "Type a message first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (requestInProgress) {
            Toast.makeText(
                this,
                "Please wait for the current reply.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        input.text.clear()
        addMessage(command, true)
        handleCommand(command)
    }

    private fun setupVoice() {
        voiceController = VoiceController(
            this,
            object : VoiceController.Listener {

                override fun onListening() {
                    listening = true
                    status.text = "LISTENING… SPEAK NOW"
                    micButton.text = "■"
                    addMessage("Listening…", false)
                }

                override fun onPartial(text: String) {
                    status.text = "HEARING: $text"
                }

                override fun onFinal(text: String) {
                    listening = false
                    micButton.text = "🎙"

                    if (text.isBlank()) {
                        status.text = "YOUR PERSONAL ASSISTANT"
                        return
                    }

                    addMessage(text, true)
                    handleCommand(text)
                }

                override fun onError(message: String) {
                    listening = false
                    micButton.text = "🎙"
                    status.text = "YOUR PERSONAL ASSISTANT"
                    addMessage(message, false)
                }
            }
        )

        speechOutput = VixSpeechOutput(
            this,
            { speaking ->
                if (speaking) {
                    status.text = "VIX AI IS SPEAKING…"
                } else if (status.text == "VIX AI IS SPEAKING…") {
                    status.text = "YOUR PERSONAL ASSISTANT"
                }
            },
            { message ->
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    private fun startVoiceInput() {
        if (requestInProgress) {
            Toast.makeText(
                this,
                "Please wait for the current reply.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_REQUEST
            )
            return
        }

        speechOutput.stop()
        voiceController.start()
    }

    private fun handleCommand(rawCommand: String) {
        status.text = "WORKING…"

        // Screenshot and screen recording are handled before the router.
        val screenAction = ScreenCaptureController.parse(rawCommand)
        if (screenAction != null) {
            handleScreenAction(screenAction)
            return
        }

        // Execute recognized local commands without sending them to the AI.
        val localResult = commandRouter.handle(rawCommand)

        if (localResult.handled) {
            if (
                localResult.needsCameraPermission &&
                checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingCommand = rawCommand

                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST
                )
                return
            }

            showReply(
                localResult.message,
                localResult.successful
            )
            return
        }

        // Send general questions to the configured VIX AI API.
        requestInProgress = true

        // No "CONTACTING VIX AI…" text: show the breathing logo
        // and three typing dots in the conversation instead.
        status.text = "YOUR PERSONAL ASSISTANT"
        showTyping()

        aiApiClient.sendMessage(rawCommand) { aiResult ->
            if (isFinishing || isDestroyed) {
                return@sendMessage
            }

            requestInProgress = false
            hideTyping()

            showReply(
                aiResult.message,
                aiResult.successful
            )
        }
    }

    private fun showReply(
        message: String,
        speak: Boolean
    ) {
        addMessage(message, false)

        status.text = if (speak) {
            "DONE"
        } else {
            "NEEDS ATTENTION"
        }

        if (speak) {
            speechOutput.speak(message)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {
            MICROPHONE_PERMISSION_REQUEST -> {
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startVoiceInput()
                } else {
                    addMessage(
                        "Microphone permission was denied. " +
                            "You can still type messages.",
                        false
                    )
                    status.text = "YOUR PERSONAL ASSISTANT"
                }
            }

            NOTIFICATION_PERMISSION_REQUEST -> {
                // The capture works even if notifications are denied.
                if (pendingScreenAction != null) {
                    launchScreenCaptureConsent()
                }
            }

            CAMERA_PERMISSION_REQUEST -> {
                val command = pendingCommand
                pendingCommand = null

                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    command != null
                ) {
                    handleCommand(command)
                } else {
                    showReply(
                        "Camera permission was denied. " +
                            "Allow it in Settings if the requested command needs it.",
                        false
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------
    // Screenshot and screen recording
    // ----------------------------------------------------------

    private fun handleScreenAction(action: ScreenCaptureController.Action) {
        if (action == ScreenCaptureController.Action.STOP_RECORDING) {
            if (ScreenCaptureService.isRecording) {
                startService(
                    Intent(this, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP_RECORDING)
                )
                showReply("Stopping the screen recording…", true)
            } else {
                showReply("No screen recording is running right now.", false)
            }
            return
        }

        if (
            action == ScreenCaptureController.Action.START_RECORDING &&
            ScreenCaptureService.isRecording
        ) {
            showReply(
                "A screen recording is already running. " +
                    "Say \"stop screen recording\" to finish it.",
                false
            )
            return
        }

        pendingScreenAction = action

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }

        launchScreenCaptureConsent()
    }

    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        status.text = "WAITING FOR PERMISSION…"

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQUEST
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != SCREEN_CAPTURE_REQUEST) {
            return
        }

        val action = pendingScreenAction
        pendingScreenAction = null

        if (action == null) {
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            showReply(
                "Screen capture was not allowed, so I didn't capture anything.",
                false
            )
            return
        }

        val screenshot = action == ScreenCaptureController.Action.SCREENSHOT

        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(
                if (screenshot) {
                    ScreenCaptureService.ACTION_SCREENSHOT
                } else {
                    ScreenCaptureService.ACTION_START_RECORDING
                }
            )
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)

        startForegroundService(serviceIntent)

        showReply(
            if (screenshot) {
                "Taking the screenshot. I'll step aside for a moment so it " +
                    "captures your real screen."
            } else {
                "Screen recording started. Say \"stop screen recording\" " +
                    "or tap Stop in the notification."
            },
            false
        )
        status.text = "DONE"

        // Move VIX to the background so it is not in the picture.
        conversationScroll.postDelayed({ moveTaskToBack(true) }, 700)
    }

    override fun onResume() {
        super.onResume()

        // Tell the user what the capture service did while VIX was hidden.
        val report = ScreenCaptureService.takePendingReport()
        if (report != null) {
            addMessage(report, false)
            status.text = "DONE"
        }
    }

    override fun onDestroy() {
        hideTyping()

        if (::voiceController.isInitialized) {
            voiceController.destroy()
        }

        if (::speechOutput.isInitialized) {
            speechOutput.shutdown()
        }

        if (::aiApiClient.isInitialized) {
            aiApiClient.close()
        }

        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

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
