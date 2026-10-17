package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val drawerColor = Color.rgb(18, 18, 24)
    private val panelColor = Color.rgb(28, 28, 36)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)
    private val userBubbleColor = Color.rgb(48, 31, 36)

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    // Screen parts
    private lateinit var root: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var status: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micButton: Button

    // Left menu (drawer)
    private lateinit var drawerPanel: LinearLayout
    private lateinit var scrim: View
    private lateinit var historyList: LinearLayout
    private lateinit var searchBox: EditText
    private var drawerOpen = false
    private var drawerWidth = 0
    private var allChats: List<ChatRepository.Chat> = emptyList()
    private var historyLoaded = false
    private var historyError: String? = null

    // Helpers
    private lateinit var commandRouter: LocalCommandRouter
    private lateinit var voiceController: VoiceController
    private lateinit var speechOutput: VixSpeechOutput
    private lateinit var aiApiClient: AiApiClient
    private lateinit var tavilyClient: TavilyClient
    private lateinit var chatRepo: ChatRepository

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

    // Swipe-to-reply: the message the user is replying to, and its bar.
    private var replyingTo: String? = null
    private lateinit var replyBar: LinearLayout
    private lateinit var replyPreview: TextView

    // The message being replied to, passed to the AI as context.
    private var replyContext: String? = null

    // Saved chat state
    private class PendingSave(val role: String, val content: String, val quote: String?)

    private var chatId: String? = null
    private var creatingChat = false
    private var chatToken = 0
    private val pendingSaves = ArrayList<PendingSave>()
    private var saveWarned = false
    private var loggingOut = false

    // True while the empty-chat greeting and suggestions are on screen.
    private var emptyShown = false

    // The voice or speed may have been changed on the profile page.
    private var speechNeedsRefresh = false

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
        private const val SCREEN_CAPTURE_REQUEST = 1003
        private const val NOTIFICATION_PERMISSION_REQUEST = 1004

        // Same preference file and key the VIX overlay and profile page use.
        private const val MODE_PREFS = "vix_overlay"
        private const val MODE_KEY = "mode"
        private const val MODE_PHONE = "PHONE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Logged out: go to the login pages.
        if (!SupabaseAuth.isLoggedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        commandRouter = LocalCommandRouter(this)
        aiApiClient = AiApiClient(this)
        tavilyClient = TavilyClient(this)
        chatRepo = ChatRepository(this)

        buildInterface()
        setupVoice()

        updateInputHint()
        restoreWakeService()
        loadHistory()
        handleWakeIntent(intent)
    }

    // ----------------------------------------------------------
    // Screen
    // ----------------------------------------------------------

    private fun buildInterface() {
        root = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
        }

        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(
            mainColumn,
            FrameLayout.LayoutParams(matchParent, matchParent)
        )

        // Top bar: menu button, logo, new chat button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "VIX AI logo"
        }

        topBar.addView(
            circleButton("☰", "Menu") { openDrawer() },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        topBar.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        )
        topBar.addView(
            circleButton("✎", "New chat") { newChat() },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        mainColumn.addView(
            topBar,
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )

        status = TextView(this).apply {
            text = "YOUR PERSONAL ASSISTANT"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        mainColumn.addView(status)

        // Conversation
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }

        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(conversation)
        }

        mainColumn.addView(
            conversationScroll,
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )

        showEmptyState()

        // "Replying to ..." bar, shown above the typing box after a swipe.
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
            background = rounded(Color.rgb(36, 36, 46), 14)
            visibility = View.GONE
        }

        replyBar.addView(
            View(this).apply { setBackgroundColor(redColor) },
            LinearLayout.LayoutParams(dp(3), matchParent).apply {
                marginEnd = dp(10)
            }
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

        replyBar.addView(replyTexts, LinearLayout.LayoutParams(0, wrapContent, 1f))

        replyBar.addView(
            TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                contentDescription = "Cancel reply"
                setOnClickListener { clearReply() }
            }
        )

        mainColumn.addView(
            replyBar,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
            }
        )

        // Message bar: + button, text box, mic, send
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(panelColor, 28)
        }

        val plusButton = TextView(this).apply {
            text = "+"
            textSize = 26f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            contentDescription = "Quick commands"
            background = circle(Color.rgb(48, 48, 58))
            setOnClickListener { showPlusMenu() }
        }
        composer.addView(plusButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        input = EditText(this).apply {
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(10), dp(6), dp(10))
            background = null
        }
        composer.addView(input, LinearLayout.LayoutParams(0, wrapContent, 1f))

        micButton = Button(this).apply {
            text = "🎙"
            textSize = 18f
            isAllCaps = false
            setTextColor(textColor)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = circle(Color.rgb(48, 48, 58))
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
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
        )

        val sendButton = Button(this).apply {
            text = "➤"
            textSize = 20f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = circle(redColor)
            contentDescription = "Send message"
            setOnClickListener { submitTypedMessage() }
        }
        composer.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) }
        )

        mainColumn.addView(
            composer,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
            }
        )

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedMessage()
                true
            } else {
                false
            }
        }

        buildDrawer()

        // Keep everything clear of the status bar, nav bar and keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val i = insetsOf(insets)
            mainColumn.setPadding(
                i[0] + dp(14), i[1] + dp(8), i[2] + dp(14), i[3] + dp(8)
            )
            drawerPanel.setPadding(
                i[0] + dp(16), i[1] + dp(14), dp(16), i[3] + dp(12)
            )
            insets
        }

        setContentView(root)
    }

    // ----------------------------------------------------------
    // Left menu (drawer)
    // ----------------------------------------------------------

    private fun buildDrawer() {
        drawerWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()

        scrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(matchParent, matchParent))

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(drawerColor)
            isClickable = true
            elevation = dp(16).toFloat()
            translationX = -drawerWidth.toFloat()
        }
        root.addView(drawerPanel, FrameLayout.LayoutParams(drawerWidth, matchParent))

        // Header: logo + search button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.vix_logo_lockup_dark)
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                contentDescription = "VIX AI logo"
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        header.addView(
            circleButton("🔍", "Search chats") {
                if (searchBox.visibility == View.VISIBLE) {
                    searchBox.setText("")
                    searchBox.visibility = View.GONE
                } else {
                    searchBox.visibility = View.VISIBLE
                    searchBox.requestFocus()
                }
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        drawerPanel.addView(header, LinearLayout.LayoutParams(matchParent, wrapContent))

        searchBox = EditText(this).apply {
            hint = "Search chats"
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(panelColor, 16)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    renderHistory(s?.toString().orEmpty())
                }
            })
        }
        drawerPanel.addView(
            searchBox,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(10)
            }
        )

        // New chat row
        drawerPanel.addView(
            TextView(this).apply {
                text = "✎   New chat"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setOnClickListener {
                    closeDrawer()
                    newChat()
                }
            },
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
            }
        )

        drawerPanel.addView(
            TextView(this).apply {
                text = "Chats"
                textSize = 13f
                setTextColor(mutedColor)
                setPadding(dp(12), dp(4), dp(12), dp(6))
            }
        )

        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        drawerPanel.addView(
            ScrollView(this).apply { addView(historyList) },
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )

        // Profile row at the bottom: opens the profile page
        val email = SupabaseAuth.email(this)

        val avatar = TextView(this).apply {
            text = vixInitials(email)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(redColor)
        }

        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(14), dp(10))
            background = rounded(panelColor, 18)
            contentDescription = "Profile and settings"
            setOnClickListener { openProfile() }

            addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = email.ifBlank { "My profile" }
                        textSize = 15f
                        setTextColor(textColor)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "Profile and settings"
                        textSize = 12f
                        setTextColor(mutedColor)
                    })
                },
                LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                    marginStart = dp(12)
                }
            )
        }

        drawerPanel.addView(
            profileRow,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
            }
        )

        renderHistory("")
    }

    private fun openDrawer() {
        hideKeyboard()
        loadHistory()
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            if (!drawerOpen) scrim.visibility = View.GONE
        }.start()
        drawerPanel.animate().translationX(-drawerWidth.toFloat()).setDuration(200).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    private fun openProfile() {
        closeDrawer()
        speechNeedsRefresh = true
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    private fun renderHistory(filter: String) {
        historyList.removeAllViews()

        val query = filter.trim()
        val shown = if (query.isEmpty()) {
            allChats
        } else {
            allChats.filter { it.title.contains(query, ignoreCase = true) }
        }

        if (shown.isEmpty()) {
            val message = when {
                historyError != null -> "Couldn't load chats: $historyError"
                !historyLoaded -> "Loading chats…"
                query.isNotEmpty() -> "No chats found."
                else -> "No chats yet. Start a new one!"
            }
            historyList.addView(
                TextView(this).apply {
                    text = message
                    textSize = 14f
                    setTextColor(mutedColor)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
            )
            return
        }

        for (chat in shown) {
            val row = TextView(this).apply {
                text = chat.title
                textSize = 16f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(13), dp(12), dp(13))
                if (chat.id == chatId) background = rounded(panelColor, 14)
                setOnClickListener { openChat(chat) }
                setOnLongClickListener {
                    showChatOptions(chat)
                    true
                }
            }
            historyList.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private fun loadHistory() {
        chatRepo.listChats { out ->
            if (isFinishing || isDestroyed) return@listChats

            if (out.authExpired) {
                forceLogin()
                return@listChats
            }

            val list = out.value
            if (list != null) {
                allChats = list
                historyError = null
            } else {
                historyError = out.error ?: "unknown error"
            }
            historyLoaded = true
            renderHistory(searchBox.text.toString())
        }
    }

    private fun showChatOptions(chat: ChatRepository.Chat) {
        AlertDialog.Builder(this)
            .setTitle(chat.title)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) renameChatDialog(chat) else deleteChatDialog(chat)
            }
            .show()
    }

    private fun renameChatDialog(chat: ChatRepository.Chat) {
        val box = EditText(this).apply {
            setText(chat.title)
            setSelection(text.length)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle("Rename chat")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val title = box.text.toString().trim()
                if (title.isNotEmpty()) {
                    chatRepo.renameChat(chat.id, title) { loadHistory() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteChatDialog(chat: ChatRepository.Chat) {
        AlertDialog.Builder(this)
            .setTitle("Delete this chat?")
            .setMessage("It will be removed from your account. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                chatRepo.deleteChat(chat.id) { loadHistory() }
                if (chat.id == chatId && !requestInProgress) {
                    closeDrawer()
                    newChat()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------------------------------------------------
    // New chat / open chat / saving
    // ----------------------------------------------------------

    private fun newChat() {
        if (requestInProgress) {
            toast("Please wait for the current reply.")
            return
        }

        chatToken++
        chatId = null
        creatingChat = false
        pendingSaves.clear()
        pendingForward = null
        replyContext = null
        clearReply()
        resetAi()
        input.text.clear()
        conversation.removeAllViews()
        showEmptyState()
        status.text = "YOUR PERSONAL ASSISTANT"
        renderHistory(searchBox.text.toString())
    }

    private fun openChat(chat: ChatRepository.Chat) {
        if (requestInProgress) {
            toast("Please wait for the current reply.")
            return
        }

        closeDrawer()
        if (chat.id == chatId) return

        chatToken++
        val token = chatToken
        chatId = chat.id
        creatingChat = false
        pendingSaves.clear()
        pendingForward = null
        replyContext = null
        clearReply()
        resetAi()

        emptyShown = false
        conversation.removeAllViews()
        conversation.addView(
            TextView(this).apply {
                text = "Loading chat…"
                textSize = 14f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
        )

        chatRepo.loadMessages(chat.id) { out ->
            if (isFinishing || isDestroyed || token != chatToken) {
                return@loadMessages
            }

            conversation.removeAllViews()

            if (out.authExpired) {
                forceLogin()
                return@loadMessages
            }

            val list = out.value
            if (list == null) {
                addMessage("I couldn't load this chat: ${out.error}", false)
                return@loadMessages
            }

            if (list.isEmpty()) showEmptyState()

            for (m in list) {
                addMessage(m.content, m.role == "user", m.quote)
            }
        }

        renderHistory(searchBox.text.toString())
    }

    /** Starts a fresh AI memory so a new or opened chat doesn't mix with the old one. */
    private fun resetAi() {
        aiApiClient.close()
        aiApiClient = AiApiClient(this)
    }

    /** Saves one message to the current chat, creating the chat on the first message. */
    private fun persist(role: String, content: String, quote: String?) {
        val id = chatId
        if (id != null) {
            chatRepo.addMessage(id, role, content, quote)
            return
        }

        pendingSaves.add(PendingSave(role, content, quote))
        if (creatingChat) return

        creatingChat = true
        val token = chatToken

        chatRepo.createChat(chatTitle(content)) { out ->
            if (isFinishing || isDestroyed) return@createChat

            if (token != chatToken) {
                // The user moved to another chat meanwhile: drop the empty one.
                out.value?.let { chatRepo.deleteChat(it) }
                return@createChat
            }

            creatingChat = false
            val newId = out.value

            if (newId == null) {
                pendingSaves.clear()
                if (out.authExpired) {
                    forceLogin()
                } else if (!saveWarned) {
                    saveWarned = true
                    toast("Couldn't save this chat: ${out.error}")
                }
                return@createChat
            }

            chatId = newId
            for (p in pendingSaves) {
                chatRepo.addMessage(newId, p.role, p.content, p.quote)
            }
            pendingSaves.clear()
            loadHistory()
        }
    }

    private fun chatTitle(text: String): String {
        val oneLine = text.trim().replace("\n", " ")
        return if (oneLine.length > 40) oneLine.take(40) + "…" else oneLine
    }

    private fun forceLogin() {
        if (loggingOut) return
        loggingOut = true

        SupabaseAuth.signOut(this) {
            startActivity(
                Intent(this, AuthActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            finish()
        }
    }

    // ----------------------------------------------------------
    // Empty chat screen and the "+" menu
    // ----------------------------------------------------------

    private fun showEmptyState() {
        addMessage(
            "Hi, I'm VIX AI. Ask me a question, request coding help, " +
                "or use a supported phone command.",
            false
        )
        emptyShown = true

        conversation.addView(
            View(this),
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )

        addSuggestion("🔦   Turn on flashlight", "Turn on flashlight")
        addSuggestion("📷   Open camera", "Open camera")
        addSuggestion("🔍   Search the web", "Search for ")
    }

    private fun addSuggestion(label: String, command: String) {
        conversation.addView(
            TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(mutedColor)
                setPadding(dp(8), dp(14), dp(8), dp(14))
                setOnClickListener {
                    input.setText(command)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                    showKeyboard()
                }
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
    }

    private fun showPlusMenu() {
        val labels = arrayOf(
            "🔦   Turn on flashlight",
            "📷   Open camera",
            "📸   Take a screenshot",
            "🎬   Start screen recording",
            "🔍   Search the web"
        )

        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> quickSend("Turn on flashlight")
                    1 -> quickSend("Open camera")
                    2 -> quickSend("Take a screenshot")
                    3 -> quickSend("Start screen recording")
                    else -> {
                        input.setText("Search for ")
                        input.setSelection(input.text.length)
                        input.requestFocus()
                        showKeyboard()
                    }
                }
            }
            .show()
    }

    private fun quickSend(command: String) {
        if (requestInProgress) {
            toast("Please wait for the current reply.")
            return
        }
        sendUserText(command)
    }

    // ----------------------------------------------------------
    // Input hint (depends on VIX AI on/off, which is set on the profile page)
    // ----------------------------------------------------------

    private fun isAiMode(): Boolean {
        val saved = getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .getString(MODE_KEY, "AI")
        return saved != MODE_PHONE
    }

    private fun updateInputHint() {
        if (!::input.isInitialized) return

        input.hint = when {
            replyingTo != null -> "Type your reply..."
            isAiMode() -> "Message VIX AI..."
            else -> "Say a phone command..."
        }
    }

    // ----------------------------------------------------------
    // Swipe to reply
    // ----------------------------------------------------------

    private fun startReply(text: String) {
        replyingTo = text
        replyPreview.text = text
        replyBar.visibility = View.VISIBLE
        input.hint = "Type your reply..."
        input.requestFocus()
        showKeyboard()
    }

    private fun clearReply() {
        replyingTo = null
        if (::replyBar.isInitialized) replyBar.visibility = View.GONE
        updateInputHint()
    }

    // ----------------------------------------------------------
    // Chat bubbles
    // ----------------------------------------------------------

    /** Adds a chat bubble. Swipe it right to reply. quote = the message replied to. */
    private fun addMessage(
        message: String,
        fromUser: Boolean,
        quote: String? = null
    ) {
        // The first real message replaces the greeting and suggestions.
        if (emptyShown) {
            emptyShown = false
            conversation.removeAllViews()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(if (fromUser) userBubbleColor else panelColor, 16)
        }

        if (!quote.isNullOrBlank()) {
            val quoteView = TextView(this).apply {
                text = quote
                textSize = 12f
                setTextColor(mutedColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = rounded(Color.argb(60, 255, 255, 255), 8)
            }
            content.addView(
                quoteView,
                LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    bottomMargin = dp(6)
                }
            )
        }

        content.addView(
            TextView(this).apply {
                text = message
                textSize = 15f
                setTextColor(textColor)
                setTextIsSelectable(true)
            }
        )

        val line = SwipeReplyLayout(this) { startReply(message) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))

            addView(
                content,
                LinearLayout.LayoutParams(0, wrapContent, 0.88f)
            )
        }

        conversation.addView(line)
        scrollToBottom()
    }

    /** The developer business card, shown in the chat. */
    private fun showDeveloperCard() {
        status.text = "DONE"

        val card = DeveloperCard.build(this) {
            if (!DeveloperInfo.openWhatsApp(this)) {
                showReply("I couldn't open WhatsApp. Make sure it is installed.", false)
            }
        }

        val holder = LinearLayout(this).apply {
            setPadding(0, dp(5), 0, dp(5))
            addView(card, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        conversation.addView(holder)
        scrollToBottom()

        speechOutput.speak(DeveloperInfo.spokenMessage())
    }

    private fun scrollToBottom() {
        conversationScroll.post {
            conversationScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ----------------------------------------------------------
    // "VIX AI is typing" indicator (breathing logo + three dots)
    // ----------------------------------------------------------

    private fun showTyping() {
        if (typingRow != null) return

        val indicator = TypingIndicatorView(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(5), 0, dp(5))
            addView(
                indicator,
                LinearLayout.LayoutParams(wrapContent, wrapContent)
            )
        }

        conversation.addView(row)
        indicator.start()
        typingRow = row
        scrollToBottom()
    }

    private fun hideTyping() {
        val row = typingRow ?: return

        (row.getChildAt(0) as? TypingIndicatorView)?.stop()
        conversation.removeView(row)
        typingRow = null
    }

    // ----------------------------------------------------------
    // Sending messages
    // ----------------------------------------------------------

    private fun submitTypedMessage() {
        val command = input.text.toString().trim()

        if (command.isEmpty()) {
            toast("Type a message first.")
            return
        }

        // "/key AIza..." saves the Gemini API key privately on this phone.
        // It is handled here so the key is never shown as a chat bubble.
        if (command.startsWith("/key ", ignoreCase = true)) {
            input.text.clear()
            val key = command.substring(5).trim()

            if (key.isEmpty()) {
                addMessage("Type /key followed by your Gemini API key.", false)
            } else {
                AiApiClient.saveKey(this, key)
                addMessage("Gemini key saved on this phone.", false)
                status.text = "DONE"
            }
            return
        }

        // "/tavilykey tvly-..." saves the Tavily web search key privately.
        if (command.startsWith("/tavilykey ", ignoreCase = true)) {
            input.text.clear()
            val key = command.substring(11).trim()

            if (key.isEmpty()) {
                addMessage("Type /tavilykey followed by your Tavily key.", false)
            } else {
                TavilyClient.saveKey(this, key)
                addMessage(
                    "Tavily key saved on this phone. I can now look up " +
                        "live info, like \"latest news on...\" or " +
                        "\"search for...\".",
                    false
                )
                status.text = "DONE"
            }
            return
        }

        if (requestInProgress) {
            toast("Please wait for the current reply.")
            return
        }

        input.text.clear()
        sendUserText(command)
    }

    /** Shows the user's message, saves it, and handles it. */
    private fun sendUserText(text: String) {
        val quote = replyingTo
        addMessage(text, true, quote)
        persist("user", text, quote)
        replyContext = quote
        clearReply()
        handleCommand(text)
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

                    sendUserText(text)
                }

                override fun onError(message: String) {
                    listening = false
                    micButton.text = "🎙"
                    status.text = "YOUR PERSONAL ASSISTANT"
                    addMessage(message, false)
                }
            }
        )

        speechOutput = createSpeechOutput()
    }

    private fun createSpeechOutput(): VixSpeechOutput {
        return VixSpeechOutput(
            this,
            { speaking ->
                if (speaking) {
                    status.text = "VIX AI IS SPEAKING…"
                } else if (status.text == "VIX AI IS SPEAKING…") {
                    status.text = "YOUR PERSONAL ASSISTANT"
                }
            },
            { message -> toast(message) }
        )
    }

    private fun startVoiceInput() {
        if (requestInProgress) {
            toast("Please wait for the current reply.")
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
        // The assistant asked "forward to VIX AI?" and is waiting for yes / no.
        val waiting = pendingForward
        if (waiting != null) {
            pendingForward = null

            when (YesNo.parse(rawCommand)) {
                true -> {
                    // One time only, and never with web search (AI mode is off).
                    sendToAi(waiting, allowSearch = false)
                    return
                }

                false -> {
                    showReply("Okay, I won't send it to VIX AI.", true, true)
                    return
                }

                null -> {
                    // Not a yes or no: treat it as a new command below.
                }
            }
        }

        // Questions about the developer are answered here, in both modes.
        if (DeveloperInfo.isAbout(rawCommand)) {
            showDeveloperCard()
            return
        }

        status.text = "WORKING…"

        // Screenshot and screen recording are handled before the router.
        val screenAction = ScreenCaptureController.parse(rawCommand)
        if (screenAction != null) {
            handleScreenAction(screenAction)
            return
        }

        // Live info (news, scores, prices, weather, "search for ...") is
        // looked up by the assistant itself with Tavily, with AI on or off.
        val liveRequest = LiveInfo.parse(rawCommand)
        if (liveRequest != null && TavilyClient.hasKey(this)) {
            sendToWeb(liveRequest)
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

            showReply(localResult.message, localResult.successful, true)
            return
        }

        // VIX AI is switched off: never forward to the AI automatically.
        if (!isAiMode()) {
            pendingForward = rawCommand

            showReply(
                "I can't do that, and VIX AI is off, so I didn't send it. " +
                    "Want me to forward it to VIX AI just this once? " +
                    "Say yes or no.",
                true,
                true
            )
            status.text = "WAITING FOR YES OR NO"
            return
        }

        // AI is on: send general questions to the configured VIX AI API.
        sendToAi(rawCommand)
    }

    /**
     * Sends one message to the AI and shows the reply.
     * allowSearch = false forces web search off for this one message.
     */
    private fun sendToAi(text: String, allowSearch: Boolean = true) {
        requestInProgress = true

        status.text = "YOUR PERSONAL ASSISTANT"
        showTyping()

        // If this is a swipe-reply, tell the AI which message it answers.
        val quoted = replyContext
        replyContext = null
        val outgoing = if (!quoted.isNullOrBlank()) {
            "I'm replying to this earlier message: \"${quoted.take(400)}\"\n\n$text"
        } else {
            text
        }

        aiApiClient.sendMessage(outgoing, allowSearch) { aiResult ->
            if (isFinishing || isDestroyed) {
                return@sendMessage
            }

            requestInProgress = false
            hideTyping()

            showReply(aiResult.message, aiResult.successful, aiResult.successful)
        }
    }

    /** Looks up live info on the web with Tavily and shows a short reply. */
    private fun sendToWeb(request: LiveInfo.Request) {
        requestInProgress = true

        status.text = "SEARCHING THE WEB…"
        showTyping()

        tavilyClient.search(request) { webResult ->
            if (isFinishing || isDestroyed) {
                return@search
            }

            requestInProgress = false
            hideTyping()

            showReply(webResult.message, webResult.successful, webResult.successful)
        }
    }

    /** save = also store this reply in the chat history. */
    private fun showReply(
        message: String,
        speak: Boolean,
        save: Boolean = false
    ) {
        addMessage(message, false)
        if (save) persist("assistant", message, null)

        status.text = if (speak) "DONE" else "NEEDS ATTENTION"

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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

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
    // Wake word ("Hey VIX", "Hi VIX", "Hello VIX")
    // The on/off switch is on the profile page.
    // ----------------------------------------------------------

    override fun onStart() {
        super.onStart()
        WakeWordService.setUiVisible(true)
    }

    override fun onStop() {
        WakeWordService.setUiVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::input.isInitialized) handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (
            intent == null ||
            !intent.getBooleanExtra(WakeWordService.EXTRA_WAKE, false)
        ) {
            return
        }

        val command = intent
            .getStringExtra(WakeWordService.EXTRA_WAKE_COMMAND)
            ?.trim()
            .orEmpty()

        intent.removeExtra(WakeWordService.EXTRA_WAKE)
        intent.removeExtra(WakeWordService.EXTRA_WAKE_COMMAND)

        if (command.isNotEmpty()) {
            replyingTo = null
            sendUserText(command)
        } else {
            status.text = "LISTENING… SPEAK NOW"
            // Short pause so the microphone is free again.
            conversationScroll.postDelayed({ startVoiceInput() }, 400)
        }
    }

    /** Restarts the listener after VIX is reopened, if it was turned on. */
    private fun restoreWakeService() {
        if (
            WakeWordService.isEnabled(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(this)
        ) {
            startForegroundService(Intent(this, WakeWordService::class.java))
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
        if (!::input.isInitialized) return

        // The voice or speed may have changed on the profile page.
        if (speechNeedsRefresh) {
            speechNeedsRefresh = false
            speechOutput.shutdown()
            speechOutput = createSpeechOutput()
        }

        // VIX AI may have been switched on or off (profile page or overlay).
        updateInputHint()

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

        if (::tavilyClient.isInitialized) {
            tavilyClient.close()
        }

        if (::chatRepo.isInitialized) {
            chatRepo.close()
        }

        super.onDestroy()
    }

    // ----------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun circleButton(
        label: String,
        description: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            contentDescription = description
            background = circle(panelColor)
            setOnClickListener { onClick() }
        }
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun circle(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
}