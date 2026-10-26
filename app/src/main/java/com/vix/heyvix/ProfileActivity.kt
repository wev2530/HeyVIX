package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The profile page, opened from the profile icon in the left menu.
 * Holds every setting: VIX AI, web search, wake word, power button,
 * voice and speed, keys, account, app version, chat history and log out.
 */
class ProfileActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val panelColor = Color.rgb(28, 28, 36)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    companion object {
        private const val WAKE_MIC_REQUEST = 2001
        private const val WAKE_NOTIF_REQUEST = 2002
        private const val WAKE_OVERLAY_REQUEST = 2003

        // Same preference file and key the VIX overlay and the chat use.
        private const val MODE_PREFS = "vix_overlay"
        private const val MODE_KEY = "mode"
        private const val MODE_PHONE = "PHONE"
    }

    private class Row(val layout: LinearLayout, val subtitle: TextView)

    private lateinit var content: LinearLayout
    private lateinit var speechOutput: VixSpeechOutput
    private lateinit var chatRepo: ChatRepository

    private lateinit var aiSwitch: Switch
    private lateinit var aiRow: Row
    private lateinit var searchSwitch: Switch
    private lateinit var searchRow: Row
    private lateinit var wakeSwitch: Switch
    private lateinit var wakeRow: Row
    private lateinit var assistRow: Row
    private lateinit var speedRow: Row
    private lateinit var keyRow: Row

    private var updating = false
    private var wakeNotifAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SupabaseAuth.isLoggedIn(this)) {
            finish()
            return
        }

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        chatRepo = ChatRepository(this)
        speechOutput = VixSpeechOutput(
            this,
            { },
            { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        )

        buildInterface()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) refresh()
    }

    override fun onDestroy() {
        if (::speechOutput.isInitialized) speechOutput.shutdown()
        if (::chatRepo.isInitialized) chatRepo.close()
        super.onDestroy()
    }

    // ----------------------------------------------------------
    // Screen
    // ----------------------------------------------------------

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insetsOf(insets)
                v.setPadding(i[0], i[1], i[2], i[3])
                insets
            }
        }

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(matchParent, matchParent))
        setContentView(root)

        // Back button
        val back = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            contentDescription = "Back"
            background = circle(panelColor)
            setOnClickListener { finish() }
        }
        content.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Avatar and email
        val email = SupabaseAuth.email(this)

        val avatar = TextView(this).apply {
            text = vixInitials(email)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(redColor)
        }
        content.addView(
            avatar,
            LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
        )

        content.addView(
            TextView(this).apply {
                text = email.ifBlank { "VIX account" }
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )

        // ---------- My VIX ----------
        addSection("My VIX")

        aiSwitch = Switch(this)
        styleSwitch(aiSwitch)
        aiRow = addRow("VIX AI", "", trailing = aiSwitch) { aiSwitch.toggle() }
        aiSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MODE_KEY, if (checked) "AI" else MODE_PHONE)
                .apply()
            refresh()
        }

        searchSwitch = Switch(this)
        styleSwitch(searchSwitch)
        searchRow = addRow(
            "Web search",
            "Live info from Google (works with a Gemini key)",
            trailing = searchSwitch
        ) {
            searchSwitch.toggle()
        }
        searchSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            AiApiClient.setWebSearch(this, checked)
        }

        wakeSwitch = Switch(this)
        styleSwitch(wakeSwitch)
        wakeRow = addRow("Activate with command", "", trailing = wakeSwitch) {
            wakeSwitch.toggle()
        }
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked) startWake() else stopWake()
        }

        assistRow = addRow("Power button assistant", "") { onAssistClicked() }

        // ---------- Voice ----------
        addSection("Voice")

        addRow("Voice", "Tap to choose a voice") { onVoiceClicked() }
        speedRow = addRow("Speed", "") {
            speechOutput.cycleSpeed()
            refresh()
        }

        // ---------- Keys ----------
        addSection("Keys (saved only on this phone)")

        keyRow = addRow("AI key", "") { onAiKeyClicked() }
        addRow("Tavily search key", "Needed for live web info") {
            promptKey("Tavily search key") { key ->
                TavilyClient.saveKey(this, key)
            }
        }

        // ---------- Account ----------
        addSection("Account")

        addRow("Email", email.ifBlank { "Not available" })
        addRow("App version", appVersionName())
        addRow("Delete chat history", "Removes all your saved chats", redColor) {
            confirmDeleteHistory()
        }
        addRow("About the developer", null) { showDeveloper() }
        addRow("Log out", null, redColor) { confirmLogout() }
    }

    // ----------------------------------------------------------
    // Keeping the switches in sync
    // ----------------------------------------------------------

    private fun isAiMode(): Boolean {
        val saved = getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .getString(MODE_KEY, "AI")
        return saved != MODE_PHONE
    }

    private fun refresh() {
        updating = true

        val ai = isAiMode()
        aiSwitch.isChecked = ai
        aiRow.subtitle.text = if (ai) {
            "On: questions go to the AI"
        } else {
            "Off: phone commands only, I ask before using the AI"
        }

        searchSwitch.isChecked = AiApiClient.isWebSearchOn(this)
        searchRow.layout.visibility = if (ai) View.VISIBLE else View.GONE

        val wakeOn = WakeWordService.isEnabled(this)
        wakeSwitch.isChecked = wakeOn
        wakeRow.subtitle.text = if (wakeOn) {
            "On: say Hey VIX, Hi VIX or Hello VIX"
        } else {
            "Off: turn on to say Hey VIX"
        }

        assistRow.subtitle.text = if (isVixAssistant()) {
            "VIX is set as your phone's assistant"
        } else {
            "Tap to set VIX as the assistant"
        }

        speedRow.subtitle.text = speechOutput.speedLabel()

        keyRow.subtitle.text = if (AiApiClient.hasKey(this)) {
            "${AiApiClient.currentProvider(this).label} key saved. Tap to change."
        } else {
            "Not set. Needed for VIX AI answers. Works with any provider."
        }

        updating = false
    }

    // ----------------------------------------------------------
    // Wake word
    // ----------------------------------------------------------

    private fun startWake() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(
                "This phone has no speech recognition service, " +
                    "so the wake word can't work."
            )
            refresh()
            return
        }

        wakeNotifAsked = false
        continueWakeSetup()
    }

    private fun stopWake() {
        WakeWordService.setEnabled(this, false)
        startService(
            Intent(this, WakeWordService::class.java)
                .setAction(WakeWordService.ACTION_STOP)
        )
        toast("Wake word is off.")
        refresh()
    }

    /** Asks for each needed permission in turn, then turns the wake word on. */
    private fun continueWakeSetup() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                WAKE_MIC_REQUEST
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            !wakeNotifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wakeNotifAsked = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                WAKE_NOTIF_REQUEST
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            toast(
                "One last step: turn on \"Allow display over other apps\" " +
                    "for VIX so it can open when you say Hey VIX."
            )
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                WAKE_OVERLAY_REQUEST
            )
            return
        }

        WakeWordService.setEnabled(this, true)
        startForegroundService(Intent(this, WakeWordService::class.java))
        toast(
            "Wake word is on. Close VIX, then say \"Hey VIX\". " +
                "You can add a request, like \"Hey VIX open camera\"."
        )
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            WAKE_MIC_REQUEST -> {
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    continueWakeSetup()
                } else {
                    toast("The wake word needs the microphone. It stays off.")
                    refresh()
                }
            }

            WAKE_NOTIF_REQUEST -> continueWakeSetup()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == WAKE_OVERLAY_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                continueWakeSetup()
            } else {
                toast(
                    "Without \"Display over other apps\", VIX can't open when " +
                        "you say Hey VIX. Wake word stays off."
                )
                refresh()
            }
        }
    }

    // ----------------------------------------------------------
    // Power button / default assistant
    // ----------------------------------------------------------

    private fun isVixAssistant(): Boolean {
        return try {
            val value = Settings.Secure.getString(contentResolver, "assistant") ?: ""
            value.startsWith("$packageName/")
        } catch (_: Exception) {
            false
        }
    }

    private fun onAssistClicked() {
        if (isVixAssistant()) {
            AlertDialog.Builder(this)
                .setTitle("Power button assistant")
                .setMessage(
                    "VIX is your phone's assistant. Hold the power button to open it. " +
                        "If that opens something else, look in Settings for " +
                        "\"Press and hold power button\" (or \"Power key\") and " +
                        "choose the digital assistant."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        toast(
            "On the next screen, open \"Digital assistant app\" and choose VIX. " +
                "Then set holding the power button to the digital assistant."
        )

        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                toast(
                    "I couldn't open Settings. Open Settings, Apps, Default apps, " +
                        "Digital assistant app, and choose VIX."
                )
            }
        }
    }

    // ----------------------------------------------------------
    // Voice, keys, account
    // ----------------------------------------------------------

    private fun onVoiceClicked() {
        val options = speechOutput.voiceOptions()

        if (options.isEmpty()) {
            toast("The voices aren't ready yet. Try again in a moment.")
            return
        }

        val labels = options.map { it.label }.toTypedArray()
        val current = options
            .indexOfFirst { it.name == speechOutput.currentVoiceName() }
            .coerceAtLeast(0)
        var chosen = current

        AlertDialog.Builder(this)
            .setTitle("Choose a voice")
            .setSingleChoiceItems(labels, current) { _, which -> chosen = which }
            .setPositiveButton("Use it") { _, _ ->
                speechOutput.chooseVoice(options[chosen].name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Step 1 of the AI key: choose the provider. */
    private fun onAiKeyClicked() {
        val providers = AiApiClient.PROVIDERS
        val labels = providers.map { it.label }.toTypedArray()
        val current = providers
            .indexOfFirst { it.id == AiApiClient.currentProvider(this).id }
            .coerceAtLeast(0)
        var chosen = current

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose your AI provider")
            .setSingleChoiceItems(labels, current) { _, which -> chosen = which }
            .setPositiveButton("Next") { _, _ -> showKeyForm(providers[chosen]) }
            .setNegativeButton("Cancel", null)

        if (AiApiClient.hasKey(this)) {
            dialog.setNeutralButton("Remove key") { _, _ ->
                AiApiClient.removeKey(this)
                toast("Key removed.")
                refresh()
            }
        }

        dialog.show()
    }

    /** Step 2 of the AI key: key, model and (for Other providers) base URL. */
    private fun showKeyForm(provider: AiApiClient.Provider) {
        val sameProvider = provider.id == AiApiClient.currentProvider(this).id
        val isCustom = provider.id == "custom"

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        form.addView(
            TextView(this).apply {
                text = provider.hint
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            }
        )

        val baseBox = EditText(this).apply {
            hint = "Base URL, for example https://api.example.com/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (sameProvider) setText(AiApiClient.currentBase(this@ProfileActivity))
        }

        val modelBox = EditText(this).apply {
            hint = if (provider.defaultModel.isBlank()) {
                "Model name (required)"
            } else {
                "Model (optional, default ${provider.defaultModel})"
            }
            inputType = InputType.TYPE_CLASS_TEXT
            if (sameProvider) setText(AiApiClient.currentModel(this@ProfileActivity))
        }

        val keyBox = EditText(this).apply {
            hint = "Paste your key here"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        if (isCustom) form.addView(baseBox)
        form.addView(modelBox)
        form.addView(keyBox)

        AlertDialog.Builder(this)
            .setTitle("${provider.label} key")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val key = keyBox.text.toString().trim()
                val model = modelBox.text.toString().trim()
                val base = baseBox.text.toString().trim()

                when {
                    key.isEmpty() -> toast("Nothing was saved. Paste your key first.")
                    isCustom && !base.startsWith("http", ignoreCase = true) ->
                        toast("Nothing was saved. Enter the base URL of your provider.")
                    isCustom && model.isEmpty() ->
                        toast("Nothing was saved. Enter the model name for your provider.")
                    else -> {
                        AiApiClient.saveSettings(this, provider, key, model, base)
                        toast("Saved on this phone.")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptKey(title: String, onSave: (String) -> Unit) {
        val box = EditText(this).apply {
            hint = "Paste the key here"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val key = box.text.toString().trim()
                if (key.isEmpty()) {
                    toast("Nothing was saved.")
                } else {
                    onSave(key)
                    toast("Saved on this phone.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteHistory() {
        AlertDialog.Builder(this)
            .setTitle("Delete chat history?")
            .setMessage("All your saved chats will be removed from your account. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                chatRepo.deleteAllChats { out ->
                    if (isFinishing || isDestroyed) return@deleteAllChats
                    if (out.value == true) {
                        toast("Chat history deleted.")
                    } else {
                        toast("Couldn't delete: ${out.error}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeveloper() {
        val card = DeveloperCard.build(this) {
            if (!DeveloperInfo.openWhatsApp(this)) {
                toast("I couldn't open WhatsApp. Make sure it is installed.")
            }
        }

        AlertDialog.Builder(this)
            .setView(card)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("Your chats stay saved in your account.")
            .setPositiveButton("Log out") { _, _ ->
                SupabaseAuth.signOut(this) {
                    startActivity(
                        Intent(this, AuthActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    )
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The version of this build, like 1.0.60 (same number as the release tag). */
    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ----------------------------------------------------------
    // View builders
    // ----------------------------------------------------------

    private fun addSection(label: String) {
        content.addView(
            TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(mutedColor)
                setPadding(dp(4), dp(18), dp(4), dp(8))
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
    }

    private fun addRow(
        title: String,
        subtitle: String?,
        titleColor: Int = textColor,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): Row {
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(titleColor)
        }
        val subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor)
            if (subtitle == null) {
                visibility = View.GONE
            } else {
                text = subtitle
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitleView)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panelColor, 18)
            addView(texts, LinearLayout.LayoutParams(0, wrapContent, 1f))
            if (trailing != null) addView(trailing)
            if (onClick != null) setOnClickListener { onClick() }
        }

        content.addView(
            row,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(6)
            }
        )

        return Row(row, subtitleView)
    }

    private fun styleSwitch(s: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        s.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, mutedColor))
        s.trackTintList = ColorStateList(
            states,
            intArrayOf(redColor, Color.rgb(70, 70, 82))
        )
    }

    // ----------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun insetsOf(i: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            intArrayOf(b.left, b.top, b.right, b.bottom)
        } else {
            intArrayOf(
                i.systemWindowInsetLeft,
                i.systemWindowInsetTop,
                i.systemWindowInsetRight,
                i.systemWindowInsetBottom
            )
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
}