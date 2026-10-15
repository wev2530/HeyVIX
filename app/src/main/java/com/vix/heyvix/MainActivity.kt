package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

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
    private lateinit var tavilyClient: TavilyClient

    private var pendingCommand: String? = null
    private var listening = false
    private var requestInProgress = false

    // A question the phone assistant couldn't handle (AI is off),
    // waiting for the user's yes / no to forward it to the AI.
    private var pendingForward: String? = null

    // The "VIX AI is typing" row shown while waiting for the AI or the web.
    private var typingRow: LinearLayout? = null

    // Screenshot / screen recording waiting for Android's permission dialog.
    private var pendingScreenAction: ScreenCaptureController.Action? = null

    // Wake word ("Hey VIX") toggle.
    private lateinit var wakeButton: Button
    private lateinit var assistButton: Button
    private var wakeNotifAsked = false

    // Voice and speed buttons.
    private lateinit var voiceButton: Button
    private lateinit var speedButton: Button

    // VIX AI mode + web search switches (shared with the overlay).
    private lateinit var aiModeTitle: TextView
    private lateinit var aiModeSub: TextView
    private lateinit var aiModeSwitch: Switch
    private lateinit var searchRow: LinearLayout
    private lateinit var searchSwitch: Switch
    private var updatingSwitches = false

    // Swipe-to-reply: the message the user is replying to, and its bar.
    private var replyingTo: String? = null
    private lateinit var replyBar: LinearLayout
    private lateinit var replyPreview: TextView

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
        private const val SCREEN_CAPTURE_REQUEST = 1003
        private const val NOTIFICATION_PERMISSION_REQUEST = 1004
        private const val WAKE_MIC_REQUEST = 1005
        private const val WAKE_NOTIF_REQUEST = 1006
        private const val WAKE_OVERLAY_REQUEST = 1007

        // Same preference file and key the VIX overlay uses,
        // so the app and the overlay always agree on the mode.
        private const val MODE_PREFS = "vix_overlay"
        private const val MODE_KEY = "mode"
        private const val MODE_PHONE = "PHONE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        commandRouter = LocalCommandRouter(this)
        aiApiClient = AiApiClient(this)
        tavilyClient = TavilyClient(this)

        buildInterface()
        setupVoice()

        updateWakeButton()
        updateAssistButton()
        updateModeSwitches()
        updateVoiceButtons()
        restoreWakeService()
        handleWakeIntent(intent)
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

        wakeButton = Button(this).apply {
            isAllCaps = false
            textSize = 11f
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(panelColor, 16)
            setOnClickListener { toggleWake() }
        }

        root.addView(
            wakeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(6) }
        )

        assistButton = Button(this).apply {
            isAllCaps = false
            textSize = 11f
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(panelColor, 16)
            setOnClickListener { onAssistClicked() }
        }

        root.addView(
            assistButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(6) }
        )

        // Voice and speed of the assistant's spoken replies.
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        voiceButton = Button(this).apply {
            isAllCaps = false
            textSize = 11f
            text = "VOICE · tap to change"
            setTextColor(textColor)
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(panelColor, 16)
            setOnClickListener { onVoiceClicked() }
        }

        speedButton = Button(this).apply {
            isAllCaps = false
            textSize = 11f
            text = "SPEED · Normal"
            setTextColor(textColor)
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(panelColor, 16)
            setOnClickListener { onSpeedClicked() }
        }

        voiceRow.addView(
            voiceButton,
            LinearLayout.LayoutParams(0, dp(38), 1.4f).apply {
                marginEnd = dp(6)
            }
        )
        voiceRow.addView(
            speedButton,
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        root.addView(
            voiceRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        // Switch 1: VIX AI on = ask the AI, off = phone commands only.
        aiModeTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(textColor)
        }
        aiModeSub = TextView(this).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }

        val aiTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(aiModeTitle)
            addView(aiModeSub)
        }

        aiModeSwitch = Switch(this)
        styleSwitch(aiModeSwitch)
        aiModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MODE_KEY, if (checked) "AI" else MODE_PHONE)
                .apply()
            // A pending "forward to AI?" question no longer applies.
            pendingForward = null
            updateModeSwitches()
        }

        val aiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(panelColor, 16)
            addView(
                aiTexts,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(aiModeSwitch)
        }

        root.addView(
            aiRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        )

        // Switch 2: web search (only shown while VIX AI is on).
        val searchTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Web search"
                textSize = 14f
                setTextColor(textColor)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Live info from Google"
                textSize = 11f
                setTextColor(mutedColor)
            })
        }

        searchSwitch = Switch(this)
        styleSwitch(searchSwitch)
        searchSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            AiApiClient.setWebSearch(this, checked)
        }

        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(panelColor, 16)
            addView(
                searchTexts,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(searchSwitch)
        }

        root.addView(
            searchRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

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

        // "Replying to ..." bar, shown above the typing box after a swipe.
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
            background = roundedBackground(Color.rgb(36, 36, 46), 14)
            visibility = View.GONE
        }

        replyBar.addView(
            View(this).apply { setBackgroundColor(redColor) },
            LinearLayout.LayoutParams(
                dp(3),
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = dp(10) }
        )

        replyPreview = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val replyTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Replying to"
                textSize = 11f
                setTextColor(redColor)
            })
            addView(replyPreview)
        }

        replyBar.addView(
            replyTexts,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val replyCancel = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = "Cancel reply"
            setOnClickListener { clearReply() }
        }

        replyBar.addView(replyCancel)

        root.addView(
            replyBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

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

    // ----------------------------------------------------------
    // VIX AI mode and web search switches
    // ----------------------------------------------------------

    private fun isAiMode(): Boolean {
        val saved = getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .getString(MODE_KEY, "AI")
        return saved != MODE_PHONE
    }

    /** Syncs both switches, their labels and the input hint with the saved mode. */
    private fun updateModeSwitches() {
        if (!::aiModeSwitch.isInitialized) return

        val ai = isAiMode()

        updatingSwitches = true
        aiModeSwitch.isChecked = ai
        searchSwitch.isChecked = AiApiClient.isWebSearchOn(this)
        updatingSwitches = false

        if (ai) {
            aiModeTitle.text = "VIX AI"
            aiModeSub.text = "On: questions go to the AI"
            input.hint = "Message VIX AI..."
        } else {
            aiModeTitle.text = "Phone commands only"
            aiModeSub.text = "AI is off: I ask before sending anything to the AI"
            input.hint = "Say a phone command..."
        }

        if (replyingTo != null) {
            input.hint = "Type your reply..."
        }

        searchRow.visibility = if (ai) View.VISIBLE else View.GONE
    }

    private fun styleSwitch(s: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        s.thumbTintList = ColorStateList(
            states,
            intArrayOf(Color.WHITE, mutedColor)
        )
        s.trackTintList = ColorStateList(
            states,
            intArrayOf(redColor, Color.rgb(70, 70, 82))
   