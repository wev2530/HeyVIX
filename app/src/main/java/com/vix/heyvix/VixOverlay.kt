package com.vix.heyvix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The VIX overlay: a bottom sheet shown over any app.
 * Switch 1: VIX AI on = ask the AI, off = phone assistant commands.
 * Switch 2 (only when AI is on): web search on or off.
 */
class VixOverlayPanel(
    private val context: Context,
    private val openVix: (String?) -> Unit,
    private val closePanel: () -> Unit
) {
    private enum class Mode { AI, PHONE }

    private val panelColor = Color.rgb(28, 28, 36)
    private val chipColor = Color.rgb(48, 48, 58)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)

    private val handler = Handler(Looper.getMainLooper())
    private val prefs =
        context.getSharedPreferences("vix_overlay", Context.MODE_PRIVATE)

    private var mode = Mode.AI
    private var listening = false
    private var busy = false
    private var updating = false // true while code (not the user) moves a switch

    private var router: LocalCommandRouter? = null
    private var ai: AiApiClient? = null
    private var voice: VoiceController? = null
    private var speaker: VixSpeechOutput? = null

    val root = FrameLayout(context)
    private val status = TextView(context)
    private val aiTitle = TextView(context)
    private val aiSub = TextView(context)
    private val aiSwitch = Switch(context)
    private val searchRow = LinearLayout(context)
    private val searchSwitch = Switch(context)
    private val reply = TextView(context)
    private val typing = TypingIndicatorView(context)
    private val input = EditText(context)
    private val micButton = Button(context)

    init {
        build()
    }

    // ---------------- UI ----------------

    private fun build() {
        root.setBackgroundColor(Color.argb(110, 0, 0, 0))
        root.setOnClickListener { closePanel() }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(panelColor)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // Top row: status, Open VIX, close
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        status.apply {
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(mutedColor)
        }
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallText("Open VIX") { openVix(null) })
        top.addView(smallText("✕") { closePanel() })
        sheet.addView(top)

        // Switch 1: VIX AI on / off (off = phone assistant)
        val aiTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        aiTitle.apply { textSize = 16f; setTextColor(textColor) }
        aiSub.apply { textSize = 12f; setTextColor(mutedColor) }
        aiTexts.addView(aiTitle)
        aiTexts.addView(aiSub)

        styleSwitch(aiSwitch)
        aiSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            setMode(if (checked) Mode.AI else Mode.PHONE, animate = true)
        }

        val aiRow = switchRow(aiTexts, aiSwitch)
        sheet.addView(aiRow, fullWidth(topMargin = 12))

        // Switch 2: web search (only shown while VIX AI is on)
        val searchTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        searchTexts.addView(TextView(context).apply {
            text = "Web search"
            textSize = 14f
            setTextColor(textColor)
        })
        searchTexts.addView(TextView(context).apply {
            text = "Live info from Google"
            textSize = 11f
            setTextColor(mutedColor)
        })

        styleSwitch(searchSwitch)
        searchSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            AiApiClient.setWebSearch(context, checked)
        }

        searchRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(chipColor, 14)
            addView(searchTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchSwitch)
        }
        sheet.addView(searchRow, fullWidth(topMargin = 8))

        // Reply area
        reply.apply {
            textSize = 15f
            setTextColor(textColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            visibility = View.GONE
            background = rounded(Color.rgb(20, 20, 26), 14)
        }
        sheet.addView(reply, fullWidth(topMargin = 10))

        typing.visibility = View.GONE
        sheet.addView(
            typing,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        // Composer
        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            background = rounded(chipColor, 20)
        }

        input.apply {
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(dp(8), dp(10), dp(4), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitTyped(); true
                } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        micButton.apply {
            text = "🎙"
            textSize = 18f
            isAllCaps = false
            setTextColor(textColor)
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = rounded(Color.rgb(64, 64, 76), 14)
            setOnClickListener { toggleMic() }
        }
        composer.addView(micButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })

        val send = Button(context).apply {
            text = "➤"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = rounded(redColor, 14)
            setOnClickListener { submitTyped() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(5) })

        sheet.addView(composer, fullWidth(topMargin = 10))

        root.addView(
            sheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    /** Moves both switches and text to match the mode. */
    private fun setMode(m: Mode, animate: Boolean) {
        mode = m
        prefs.edit().putString("mode", m.name).apply()

        updating = true
        aiSwitch.isChecked = (m == Mode.AI)
        updating = false

        if (m == Mode.AI) {
            aiTitle.text = "VIX AI"
            aiSub.text = "Ask anything"
            input.hint = "Ask VIX AI…"
        } else {
            aiTitle.text = "Phone assistant"
            aiSub.text = "Open apps, flashlight, camera"
            input.hint = "Say a phone command…"
        }

        showSearchRow(m == Mode.AI, animate)
    }

    private fun showSearchRow(show: Boolean, animate: Boolean) {
        searchRow.animate().cancel()

        if (!animate) {
            searchRow.alpha = 1f
            searchRow.translationY = 0f
            searchRow.visibility = if (show) View.VISIBLE else View.GONE
            return
        }

        val shift = dp(8).toFloat()
        if (show) {
            searchRow.alpha = 0f
            searchRow.translationY = -shift
            searchRow.visibility = View.VISIBLE
            searchRow.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            searchRow.animate()
                .alpha(0f)
                .translationY(-shift)
                .setDuration(160)
                .withEndAction { searchRow.visibility = View.GONE }
                .start()
        }
    }

    // ---------------- Lifecycle ----------------

    fun onShown() {
        busy = false
        listening = false
        reply.visibility = View.GONE
        typing.stop(); typing.visibility = View.GONE
        input.setText("")
        status.text = "READY"

        updating = true
        searchSwitch.isChecked = AiApiClient.isWebSearchOn(context)
        updating = false

        val saved = prefs.getString("mode", Mode.AI.name)
        setMode(if (saved == Mode.PHONE.name) Mode.PHONE else Mode.AI, animate = false)

        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            status.text = "MIC NOT ALLOWED · OPEN VIX ONCE TO ALLOW IT"
        }
    }

    fun onHidden() {
        handler.removeCallbacksAndMessages(null)
        searchRow.animate().cancel()
        listening = false
        busy = false
        typing.stop()
        voice?.destroy(); voice = null
        speaker?.stop(); speaker?.shutdown(); speaker = null
        ai?.close(); ai = null
    }

    // ---------------- Voice ----------------

    private fun toggleMic() {
        if (listening) {
            voice?.stop()
            listening = false
            micButton.text = "🎙"
            status.text = "READY"
        } else if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            status.text = "MIC NOT ALLOWED · OPEN VIX ONCE TO ALLOW IT"
        }
    }

    private fun startListening() {
        if (busy) return
        speaker?.stop()

        val v = voice ?: VoiceController(
            context,
            object : VoiceController.Listener {
                override fun onListening() {
                    listening = true
                    micButton.text = "■"
                    status.text = "LISTENING… SPEAK NOW"
                }

                override fun onPartial(text: String) {
                    status.text = "HEARING: $text"
                }

                override fun onFinal(text: String) {
                    listening = false
                    micButton.text = "🎙"
                    submit(text)
                }

                override fun onError(message: String) {
                    listening = false
                    micButton.text = "🎙"
                    status.text = "READY"
                    showReply(message)
                }
            }
        ).also { voice = it }

        v.start()
    }

    // ---------------- Send ----------------

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        submit(text)
    }

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return

        voice?.stop()
        listening = false
        micButton.text = "🎙"
        showReply("You: $text")

        // Screenshots and recording need Android's permission screen,
        // so they continue inside the VIX app.
        if (ScreenCaptureController.parse(text) != null) {
            openVix(text)
            return
        }

        if (mode == Mode.PHONE) {
            runPhoneCommand(text)
        } else {
            askAi(text)
        }
    }

    private fun runPhoneCommand(text: String) {
        val r = (router ?: LocalCommandRouter(context).also { router = it }).handle(text)

        when {
            !r.handled -> {
                status.text = "NEEDS ATTENTION"
                showReply("That isn't a phone command I know. Turn VIX AI on to ask it.")
            }

            r.needsCameraPermission -> openVix(text)

            else -> {
                status.text = if (r.successful) "DONE" else "NEEDS ATTENTION"
                showReply(r.message)
                if (r.successful) {
                    handler.postDelayed({ closePanel() }, 1400)
                }
            }
        }
    }

    private fun askAi(text: String) {
        busy = true
        status.text = if (searchSwitch.isChecked) "VIX AI IS SEARCHING…" else "VIX AI IS THINKING…"
        typing.visibility = View.VISIBLE
        typing.start()

        val client = ai ?: AiApiClient(context).also { ai = it }

        client.sendMessage(text) { result ->
            if (ai == null) return@sendMessage // overlay was closed

            busy = false
            typing.stop()
            typing.visibility = View.GONE

            status.text = when {
                !result.successful -> "NEEDS ATTENTION"
                searchSwitch.isChecked && AiApiClient.searchBlocked ->
                    "DONE · NO WEB SEARCH ON THIS KEY"
                else -> "DONE"
            }
            showReply(result.message)

            if (result.successful) {
                val s = speaker ?: VixSpeechOutput(
                    context,
                    { speaking -> if (speaking) status.text = "VIX AI IS SPEAKING…" },
                    { }
                ).also { speaker = it }
                s.speak(result.message)
            }
        }
    }

    private fun showReply(message: String) {
        reply.text = message
        reply.visibility = View.VISIBLE
    }

    // ---------------- Helpers ----------------

    private fun switchRow(texts: View, sw: Switch) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(sw)
    }

    private fun styleSwitch(s: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        s.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, mutedColor))
        s.trackTintList = ColorStateList(states, intArrayOf(redColor, Color.rgb(70, 70, 82)))
    }

    private fun fullWidth(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = dp(topMargin) }

    private fun smallText(label: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) =
        (v * context.resources.displayMetrics.density).toInt()
}