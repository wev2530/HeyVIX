# VIX (Hey VIX)

**Your personal assistant on Android.** Talk or type, and VIX answers questions, controls your phone and looks things up on the web. All of it is in one app, with your chats saved to your account.

---

## Download

**[Download the latest VIX APK](https://github.com/wev2530/HeyVIX/releases/tag/v1.0.56)**

The file comes as a `.zip`. Open it and install the `.apk` inside.

### How to install

1. Download the file above on your Android phone and unzip it.
2. Tap the `.apk`. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. Open **VIX**, create an account (email or Google) and you're in.

Needs Android 8.0 or newer.

---

## The problem VIX solves

Phone assistants are split into pieces. One app answers questions, another controls the phone, another searches the web, and none of them remember your conversations in a way you control.

VIX puts these together in one place:

- **Ask anything** and get an AI answer, without switching apps.
- **Control your phone** by voice or text, hands free.
- **Get live information** such as news, scores, prices and weather, not only what an AI learned in the past.
- **Keep your conversations** in an account, so your chat history is there when you come back.

---

## What VIX can do

- **AI answers**: ask questions, get help with writing or code, have a conversation. Turn VIX AI on or off any time.
- **Phone commands**: turn the flashlight on, open the camera, take a screenshot, record your screen.
- **Live web info**: ask for the latest news, scores, prices or weather, or say "search for ...".
- **Voice in and out**: speak to VIX and hear its answers. Choose the voice and the speed.
- **Hey VIX wake word**: say "Hey VIX", "Hi VIX" or "Hello VIX", with or without a request, like "Hey VIX open camera".
- **Power button assistant**: set VIX as your phone's digital assistant and hold the power button to open it.
- **Chat history**: every chat is saved to your account. Open the left menu to search, reopen, rename or delete chats.
- **Swipe to reply**: swipe a message to reply to it.
- **Sign in**: email and password, or Continue with Google.
- **Your privacy**: your Gemini and Tavily keys stay on your phone only.

---

## Using VIX

1. **Left menu (the menu button)**: your chat history, search, and New chat.
2. **Plus button**: quick commands like flashlight, camera and screenshot.
3. **Profile (bottom of the left menu)**: all the settings, including VIX AI on or off, web search, the wake word, the power button assistant, voice and speed, your keys, deleting your chat history, and logging out.

### Setting up the AI and web search

VIX AI uses your own keys, saved only on your phone:

1. Open **Profile**.
2. Add your **Gemini API key** for AI answers.
3. Add your **Tavily search key** for live web info.

---

## Build it yourself

1. Clone this repo and open it in Android Studio.
2. In `SupabaseConfig.kt`, set the address and public key of your own Supabase project. It needs the `chats` and `messages` tables with Row Level Security turned on.
3. Build and run on a phone or emulator.

---

## Built with

- Kotlin and plain Android views
- Supabase for accounts and chat history
- Google Gemini for AI answers
- Tavily for live web search

---

## Developer

Made by [wev2530](https://github.com/wev2530).

Found a bug or have an idea? [Open an issue](https://github.com/wev2530/HeyVIX/issues).