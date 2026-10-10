
package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
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
        status.text = "CONTACTING VIX AI…"

        aiApiClient.sendMessage(rawCommand) { aiResult ->
            if (isFinishing || isDestroyed) {
                return@sendMessage
            }

            requestInProgress = false

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

    override fun onDestroy() {
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
