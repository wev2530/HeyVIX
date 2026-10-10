package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)

    private lateinit var status: TextView
    private lateinit var response: TextView
    private lateinit var input: EditText
    private lateinit var voiceButton: Button

    private lateinit var commandRouter: LocalCommandRouter
    private lateinit var voiceController: VoiceController
    private lateinit var speechOutput: VixSpeechOutput

    private var pendingCommand: String? = null

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        commandRouter = LocalCommandRouter(this)

        buildInterface()
        setupVoice()
    }

    private fun buildInterface() {
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
                dp(150)
            ).apply {
                bottomMargin = dp(16)
            }
        )

        logo.startAnimation(
            AlphaAnimation(0.88f, 1.0f).apply {
                duration = 1600
                repeatMode = AlphaAnimation.REVERSE
                repeatCount = AlphaAnimation.INFINITE
            }
        )

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
                bottomMargin = dp(24)
            }
        )

        status = TextView(this).apply {
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

        input = EditText(this).apply {
            hint = "Type a command to VIX..."
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBackground(
                Color.rgb(30, 30, 38),
                14
            )
        }

        layout.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val sendButton = Button(this).apply {
            text = "SEND COMMAND"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(redColor, 14)

            setOnClickListener {
                val command = input.text.toString().trim()

                if (command.isNotEmpty()) {
                    input.text.clear()
                    handleCommand(command)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Type a command first.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        layout.addView(
            sendButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else {
                false
            }
        }

        voiceButton = Button(this).apply {
            text = "TALK TO VIX"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBackground(redColor, 14)
            isAllCaps = false

            setOnClickListener {
                startVoiceInput()
            }
        }

        layout.addView(
            voiceButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(18)
            }
        )

        response = TextView(this).apply {
            text = "Your responses will appear here."
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }

        val responseScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(response)
        }

        layout.addView(
            responseScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val footer = TextView(this).apply {
            text = "VIX PERSONAL ASSISTANT"
            textSize = 11f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }

        layout.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        setContentView(layout)
    }

    private fun setupVoice() {
        voiceController = VoiceController(
            this,
            object : VoiceController.Listener {

                override fun onListening() {
                    status.text = "LISTENING..."
                    voiceButton.text = "LISTENING..."
                }

                override fun onPartial(text: String) {
                    response.text = "I heard: $text"
                }

                override fun onFinal(text: String) {
                    voiceButton.text = "TALK TO VIX"
                    input.setText(text)
                    handleCommand(text)
                }

                override fun onError(message: String) {
                    status.text = "VIX IS READY"
                    voiceButton.text = "TALK TO VIX"
                    response.text = message
                }
            }
        )

        speechOutput = VixSpeechOutput(
            this,
            { speaking ->
                if (speaking) {
                    status.text = "VIX IS SPEAKING..."
                } else if (status.text == "VIX IS SPEAKING...") {
                    status.text = "VIX IS READY"
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
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
        val result = commandRouter.handle(rawCommand)

        if (!result.handled) {
            val message =
                "The online VIX AI connection is the next step. " +
                "For now, try a local command such as " +
                "turn on flashlight, open camera, or open Free Fire."

            showReply(message, false)
            return
        }

        if (result.needsCameraPermission &&
            checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCommand = rawCommand

            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
            return
        }

        showReply(result.message, true)
    }

    private fun showReply(message: String, speak: Boolean) {
        response.text = message
        status.text = if (speak) {
            "COMMAND PROCESSED"
        } else {
            "VIX IS READY"
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
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startVoiceInput()
                } else {
                    response.text =
                        "Microphone permission was denied. You can still type commands."
                    status.text = "VIX IS READY"
                }
            }

            CAMERA_PERMISSION_REQUEST -> {
                val command = pendingCommand
                pendingCommand = null

                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    command != null
                ) {
                    handleCommand(command)
                } else {
                    showReply(
                        "Camera permission was denied. Allow it in Settings to use the flashlight.",
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

        super.onDestroy()
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
