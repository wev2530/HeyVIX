<div align="center">

<h1>🎙️ Vix</h1>

<h3>Your personal assistant and AI chat, on Android and on the web</h3>

<p>
  <a href="https://github.com/wev2530/HeyVIX/releases/latest/download/Vix.apk">
    <img src="https://img.shields.io/badge/Download-Android%20App-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download Android App">
  </a>
  &nbsp;
  <a href="https://vix-ai.netlify.app">
    <img src="https://img.shields.io/badge/Open-Web%20App-blue?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Open Web App">
  </a>
</p>

<p><em>Built by <strong>GCV Designs</strong></em></p>

</div>

---

## 📖 What is Vix?

Vix is an assistant that lives in two places:

1. **Vix for Android**: a full app with a built-in personal assistant (voice, quick commands, overlay) plus **Vix AI** chat.
2. **Vix on the Web**: a website/PWA with the same **Vix AI** chat, which works on any phone, tablet or computer with no install.

Both use the **same account**, so your chats follow you from your phone to your laptop.

> **Vix** is the personal assistant. **Vix AI** is the smart chat part that answers questions in depth. You choose when to use it.

---

## ❗ The problem Vix solves

- Most assistants send everything to the cloud, even simple tasks that should be instant.
- AI chat apps are separate from your phone's assistant, so you juggle several apps.
- Your chats are stuck on one device.
- Many AI apps lock you to one provider and one key.

**Vix puts these together:** quick tasks are handled by the personal assistant, harder questions go to Vix AI, and everything syncs to your account.

---

## ✨ Features

### 📱 Android app
- **Personal assistant** that handles commands itself, without always needing the AI
- **Voice activation** with a wake word and quick commands
- **Overlay mode switch** to change between *AI mode on*, *Web search* and *AI mode off* from anywhere
- **Ask before forwarding:** if the assistant can't answer, it tells you and asks whether to send the question to Vix AI. It never forwards silently.
- **Vix AI chat** with chat history in a side drawer
- **Profile page** holding all settings (AI on/off, web search, wake word, quick commands, voice, API key)
- **Email and Google sign-in**

### 🌐 Web app
- The same **Vix AI** chat in your browser
- Full-width desktop layout, also works on phones
- Same account and synced chat history as the Android app
- **Web search** (Tavily) and **link reading** for current information
- Bring your own API key from **any supported provider**, or use the default

### 🔐 Shared
- Accounts, chat history and secure backend on **Supabase**
- Web search cannot be turned on while AI mode is off, which keeps the modes consistent

---

## 🚀 Get Vix

| Platform | Link |
|---|---|
| 🌐 **Web app** | **https://vix-ai.netlify.app** |
| 📱 **Android app (direct download, always the latest)** | **https://github.com/wev2530/HeyVIX/releases/latest/download/Vix.apk** |

Want to see all versions and release notes? Visit the **[Releases page](https://github.com/wev2530/HeyVIX/releases)**.

### Installing the Android app
1. Tap **[Download Android App](https://github.com/wev2530/HeyVIX/releases/latest/download/Vix.apk)**. The latest version starts downloading automatically.
2. Open the downloaded file on your phone. If asked, allow **Install from this source** in settings.
3. Open Vix, sign up or log in, and grant the permissions it asks for (microphone, overlay).

---

## 🧭 How to use it

1. **Sign up** with email or Google (same account on app and web).
2. Open the **left menu** to see your chat history, and the **profile icon** at the bottom for settings.
3. **Choose your mode:**
   - **AI mode ON**: questions go to Vix AI
   - **Web search**: Vix AI uses live search for current information
   - **AI mode OFF**: only the personal assistant works
4. *(Optional)* Add **your own API key** in settings if you don't want to use the default.

---

## ✅ Pros and ❌ Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Assistant and AI chat in one app | Android app is not on the Play Store yet (install via APK) |
| Same account and chats on phone and web | Vix AI needs an internet connection |
| Doesn't send everything to the AI, so quick tasks stay fast | Free-tier AI keys have usage limits |
| Asks before forwarding to AI, so you stay in control | Web app has no personal-assistant features (voice, overlay) |
| Bring your own key from any provider | iOS app not available |
| Live web search and link reading | Answers depend on the AI provider you choose |

---

## 🏗️ Project structure

```
HeyVIX/
├── app/                 # Android app (Kotlin)
├── web/                 # Website / PWA
├── supabase/
│   └── functions/
│       └── gemini-chat  # Backend function for AI chat
└── README.md
```

**Built with:** Kotlin (Android), HTML/CSS/JavaScript (web), Supabase (auth, database, edge functions), Gemini and other AI providers, Tavily (web search), Netlify (hosting).

---

## ⚠️ Notes

- Vix AI can make mistakes. Check important information.
- Your API key is yours: only add keys you trust the app with.

---

## 📬 Contact

Developed by **GCV Designs**.
Found a bug or have an idea? Open an **[Issue](https://github.com/wev2530/HeyVIX/issues)**.

---

<div align="center">

<strong>Made with ❤️ by GCV Designs</strong>

</div>