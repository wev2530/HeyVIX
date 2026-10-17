package com.vix.heyvix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Patterns
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

/**
 * Welcome, Log in, Sign up and Forgot password pages (one activity,
 * the pages switch inside it). Dark VIX look with the red accent.
 * Also catches the vix://auth link that brings the user back from Google.
 */
class AuthActivity : Activity() {

    private enum class Screen { WELCOME, LOGIN, SIGNUP, FORGOT }

    private val backgroundColor = Color.rgb(12, 12, 16)
    private val surfaceColor = Color.rgb(30, 30, 38)
    private val redColor = Color.rgb(255, 55, 65)
    private val textColor = Color.rgb(245, 245, 248)
    private val mutedColor = Color.rgb(165, 165, 175)
    private val greenColor = Color.rgb(68, 220, 130)

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    private lateinit var content: LinearLayout
    private var screen = Screen.WELCOME
    private var busy = false
    private var lastEmail = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insetsOf(insets)
                v.setPadding(i[0], i[1], i[2], i[3])
                insets
            }
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        scroll.addView(
            content,
            FrameLayout.LayoutParams(matchParent, matchParent)
        )
        root.addView(scroll, LinearLayout.LayoutParams(matchParent, matchParent))

        setContentView(root)
        render(Screen.WELCOME)

        // Opened by the link that brings the user back from Google.
        handleOAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.WELCOME -> super.onBackPressed()
            Screen.FORGOT -> render(Screen.LOGIN)
            else -> render(Screen.WELCOME)
        }
    }

    // ----------------------------------------------------------
    // Google sign-in
    // ----------------------------------------------------------

    private fun startGoogleSignIn() {
        if (busy) return

        val url = SupabaseAuth.googleSignInUrl(this)

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "I couldn't open the browser for Google sign-in.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Handles vix://auth?code=... (or an error) when the browser sends the user back. */
    private fun handleOAuthIntent(source: Intent?) {
        val data = source?.data ?: return
        if (data.scheme != "vix" || data.host != "auth") return

        // Use the link only once, even if the screen is rebuilt.
        source.data = null

        val code = data.getQueryParameter("code")

        if (code.isNullOrBlank()) {
            val reason = data.getQueryParameter("error_description")
                ?: data.getQueryParameter("error")
                ?: "Google sign-in was cancelled."
            render(Screen.LOGIN, reason, true)
            return
        }

        showSigningIn()

        SupabaseAuth.finishOAuth(this, code) { result ->
            if (isFinishing || isDestroyed) return@finishOAuth

            if (result.successful) {
                goToMain()
            } else {
                render(Screen.LOGIN, result.message, true)
            }
        }
    }

    private fun showSigningIn() {
        screen = Screen.LOGIN
        busy = true
        content.removeAllViews()
        addLogo(90)
        addTitle("Signing you in…", "Finishing your Google sign-in")
    }

    // ----------------------------------------------------------
    // Pages
    // ----------------------------------------------------------

    private fun render(target: Screen, info: String? = null, infoIsError: Boolean = false) {
        screen = target
        busy = false
        content.removeAllViews()

        when (target) {
            Screen.WELCOME -> renderWelcome()
            Screen.LOGIN -> renderLogin(info, infoIsError)
            Screen.SIGNUP -> renderSignup()
            Screen.FORGOT -> renderForgot()
        }
    }

    private fun renderWelcome() {
        addLogo(150)

        content.addView(
            TextView(this).apply {
                text = "Your personal assistant"
                textSize = 16f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )

        addButton("Log in", true) { render(Screen.LOGIN) }
        addButton("Sign up", false) { render(Screen.SIGNUP) }

        addDivider()
        addGoogleButton()
    }

    private fun renderLogin(info: String?, infoIsError: Boolean) {
        addLogo(90)
        addTitle("Welcome back", "Log in to your VIX account")

        val message = addMessage(info, if (infoIsError) redColor else greenColor)
        val email = addField(
            "Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            View.AUTOFILL_HINT_EMAIL_ADDRESS,
            lastEmail
        )
        val password = addPasswordField("Password", View.AUTOFILL_HINT_PASSWORD)

        addLink("Forgot password?", true) {
            lastEmail = email.text.toString().trim()
            render(Screen.FORGOT)
        }

        val button = addButton("Log in", true)
        button.setOnClickListener {
            if (!busy) doLogin(email, password, message, button)
        }

        password.imeOptions = EditorInfo.IME_ACTION_DONE
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                button.performClick()
                true
            } else {
                false
            }
        }

        addDivider()
        addGoogleButton()

        addLink("New to VIX? Sign up", false) {
            lastEmail = email.text.toString().trim()
            render(Screen.SIGNUP)
        }
    }

    private fun renderSignup() {
        addLogo(90)
        addTitle("Create your account", "Your chats are saved to your account")

        val message = addMessage(null, redColor)
        val email = addField(
            "Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            View.AUTOFILL_HINT_EMAIL_ADDRESS,
            lastEmail
        )
        val password = addPasswordField("Password (at least 6 characters)", "newPassword")
        val confirm = addPasswordField("Confirm password", "newPassword")

        val button = addButton("Sign up", true)
        button.setOnClickListener {
            if (!busy) doSignup(email, password, confirm, message, button)
        }

        confirm.imeOptions = EditorInfo.IME_ACTION_DONE
        confirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                button.performClick()
                true
            } else {
                false
            }
        }

        addDivider()
        addGoogleButton()

        addLink("Already have an account? Log in", false) {
            lastEmail = email.text.toString().trim()
            render(Screen.LOGIN)
        }
    }

    private fun renderForgot() {
        addLogo(90)
        addTitle("Reset your password", "Enter your email and we'll send you a reset link")

        val message = addMessage(null, redColor)
        val email = addField(
            "Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            View.AUTOFILL_HINT_EMAIL_ADDRESS,
            lastEmail
        )

        val button = addButton("Send reset link", true)
        button.setOnClickListener {
            if (!busy) doReset(email, message, button)
        }

        addLink("Back to log in", false) {
            lastEmail = email.text.toString().trim()
            render(Screen.LOGIN)
        }
    }

    // ----------------------------------------------------------
    // Actions
    // ----------------------------------------------------------

    private fun doLogin(
        email: EditText,
        password: EditText,
        message: TextView,
        button: Button
    ) {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()

        if (!validEmail(mail)) {
            showError(message, "Enter a valid email address.")
            return
        }
        if (pass.isEmpty()) {
            showError(message, "Enter your password.")
            return
        }

        hideKeyboard()
        setBusy(button, true, "Logging in…")
        message.visibility = View.GONE

        SupabaseAuth.signIn(this, mail, pass) { result ->
            if (isFinishing || isDestroyed) return@signIn

            if (result.successful) {
                goToMain()
            } else {
                setBusy(button, false, "Log in")
                showError(message, result.message)
            }
        }
    }

    private fun doSignup(
        email: EditText,
        password: EditText,
        confirm: EditText,
        message: TextView,
        button: Button
    ) {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()

        if (!validEmail(mail)) {
            showError(message, "Enter a valid email address.")
            return
        }
        if (pass.length < 6) {
            showError(message, "Use at least 6 characters for the password.")
            return
        }
        if (pass != confirm.text.toString()) {
            showError(message, "The two passwords don't match.")
            return
        }

        hideKeyboard()
        setBusy(button, true, "Creating account…")
        message.visibility = View.GONE

        SupabaseAuth.signUp(this, mail, pass) { result ->
            if (isFinishing || isDestroyed) return@signUp

            when {
                !result.successful -> {
                    setBusy(button, false, "Sign up")
                    showError(message, result.message)
                }

                result.needsConfirmation -> {
                    lastEmail = mail
                    render(
                        Screen.LOGIN,
                        "Account created. Check your email to confirm it, then log in."
                    )
                }

                else -> goToMain()
            }
        }
    }

    private fun doReset(email: EditText, message: TextView, button: Button) {
        val mail = email.text.toString().trim()

        if (!validEmail(mail)) {
            showError(message, "Enter a valid email address.")
            return
        }

        hideKeyboard()
        setBusy(button, true, "Sending…")
        message.visibility = View.GONE

        SupabaseAuth.sendReset(mail) { result ->
            if (isFinishing || isDestroyed) return@sendReset

            setBusy(button, false, "Send reset link")
            if (result.successful) {
                message.text = result.message
                message.setTextColor(greenColor)
                message.visibility = View.VISIBLE
            } else {
                showError(message, result.message)
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ----------------------------------------------------------
    // View builders
    // ----------------------------------------------------------

    private fun addLogo(heightDp: Int) {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.vix_logo_lockup_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "VIX logo"
        }
        content.addView(
            logo,
            LinearLayout.LayoutParams(matchParent, dp(heightDp)).apply {
                bottomMargin = dp(16)
            }
        )
    }

    private fun addTitle(title: String, subtitle: String) {
        content.addView(
            TextView(this).apply {
                text = title
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 14f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
    }

    private fun addMessage(initial: String?, color: Int): TextView {
        val view = TextView(this).apply {
            textSize = 13f
            setTextColor(color)
            gravity = Gravity.CENTER
            if (initial.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = initial
            }
        }
        content.addView(
            view,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
        )
        return view
    }

    private fun addField(
        hintText: String,
        type: Int,
        autofill: String,
        value: String
    ): EditText {
        val field = EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surfaceColor, 14)
            setAutofillHints(autofill)
            inputType = type
            setText(value)
        }
        content.addView(
            field,
            LinearLayout.LayoutParams(matchParent, dp(54)).apply {
                topMargin = dp(10)
            }
        )
        return field
    }

    private fun addPasswordField(hintText: String, autofill: String): EditText {
        val field = EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setPadding(dp(16), dp(14), dp(68), dp(14))
            background = rounded(surfaceColor, 14)
            setAutofillHints(autofill)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val toggle = TextView(this).apply {
            text = "Show"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        var shown = false
        toggle.setOnClickListener {
            shown = !shown
            val position = field.selectionEnd
            field.inputType = InputType.TYPE_CLASS_TEXT or
                if (shown) {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            field.setSelection(position.coerceIn(0, field.text.length))
            toggle.text = if (shown) "Hide" else "Show"
        }

        val frame = FrameLayout(this)
        frame.addView(field, FrameLayout.LayoutParams(matchParent, dp(54)))
        frame.addView(
            toggle,
            FrameLayout.LayoutParams(
                wrapContent,
                wrapContent,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )

        content.addView(
            frame,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(10)
            }
        )
        return field
    }

    private fun addButton(
        label: String,
        primary: Boolean,
        onClick: (() -> Unit)? = null
    ): Button {
        val button = Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else textColor)
            background = rounded(if (primary) redColor else surfaceColor, 26)
            if (onClick != null) {
                setOnClickListener { if (!busy) onClick() }
            }
        }
        content.addView(
            button,
            LinearLayout.LayoutParams(matchParent, dp(52)).apply {
                topMargin = dp(14)
            }
        )
        return button
    }

    /** White "Continue with Google" button. */
    private fun addGoogleButton() {
        val label = SpannableString("G     Continue with Google")
        label.setSpan(
            ForegroundColorSpan(Color.rgb(66, 133, 244)),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val button = Button(this).apply {
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 30, 34))
            background = rounded(Color.WHITE, 26)
            text = label
            contentDescription = "Continue with Google"
            setOnClickListener { startGoogleSignIn() }
        }

        content.addView(
            button,
            LinearLayout.LayoutParams(matchParent, dp(52)).apply {
                topMargin = dp(6)
            }
        )
    }

    private fun addDivider() {
        content.addView(
            TextView(this).apply {
                text = "or"
                textSize = 13f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
    }

    private fun addLink(label: String, alignEnd: Boolean, onClick: () -> Unit) {
        val link = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(redColor)
            gravity = if (alignEnd) Gravity.END else Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(8))
            setOnClickListener { if (!busy) onClick() }
        }
        content.addView(
            link,
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
    }

    // ----------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------

    private fun showError(view: TextView, message: String) {
        view.text = message
        view.setTextColor(redColor)
        view.visibility = View.VISIBLE
    }

    private fun setBusy(button: Button, on: Boolean, label: String) {
        busy = on
        button.text = label
        button.alpha = if (on) 0.6f else 1f
    }

    private fun validEmail(value: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(value).matches()

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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
}