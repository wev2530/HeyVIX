package com.vix.heyvix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import android.widget.TextView

/**
 * The VIX overlay: a bottom sheet shown over any app.
 * Type or speak, then choose VIX AI or Phone command.
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

    private var router: LocalCommandRouter? = null
    private var ai: AiApiClient? = null
    private var voice: VoiceController? = null
    private var speaker: VixSpeechOutput? = null

    val root = FrameLayout(context)
    private val status = TextView(context)
    private val aiChip = Button(context)
    private val phoneChip = Button(context)
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

        val openBtn = smallText("Open VIX") { openVix(null) }
        top.addView(openBtn)
        val closeBtn = smallText("✕") { closePanel() }
        top.addView(closeBtn)
        sheet.addView(top)

        // Mode chips
        val modes = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        styleChip(aiChip, "VIX AI") { setMode(Mode.AI) }
        styleChip(phoneChip, "Phone command") { setMode(Mode.PHONE) }
        modes.addView(aiChip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        modes.addView(phoneChip, LinearLayout.LayoutParams(0, dp(40), 1f))
        sheet.addView(
            modes,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

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
        sheet.addView(
            reply,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

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
            hint = "Type or tap the mic…"
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

        sheet.addView(
            composer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        root.addView(
            sheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    private fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString("mode", m.name).apply()
        paintChip(aiChip, m == Mode.AI)
        paintChip(phoneChip, m == Mode.PHONE)
        input.hint = if (m == Mode.AI) "Ask VIX AI…" else "Say a phone command…"
    }

    // ---------------- Lifecycle ----------------

    fun onShown() {
        busy = false
        listening = false
        reply.visibility = View.GONE
        typing.stop(); typing.visibility = View.GONE
        input.setText("")

        val saved = prefs.getString("mode", Mode.AI.name)
        setMode(if (saved == Mode.PHONE.name) Mode.PHONE else Mode.AI)

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
                showReply("That isn't a phone command I know. Switch to VIX AI to ask it.")
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
        status.text = "VIX AI IS THINKING…"
        typing.visibility = View.VISIBLE
        typing.start()

        val client = ai ?: AiApiClient(context).also { ai = it }

        client.sendMessage(text) { result ->
            if (ai == null) return@sendMessage // overlay was closed

            busy = false
            typing.stop()
            typing.visibility = View.GONE
            status.text = if (result.successful) "DONE" else "NEEDS ATTENTION"
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

    private fun styleChip(b: Button, label: String, onClick: () -> Unit) {
        b.text = label
        b.textSize = 12f
        b.isAllCaps = false
        b.setPadding(dp(8), 0, dp(8), 0)
        b.setOnClickListener { onClick() }
    }

    private fun paintChip(b: Button, selected: Boolean) {
        b.background = rounded(if (selected) redColor else chipColor, 16)
        b.setTextColor(if (selected) Color.WHITE else mutedColor)
    }

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
