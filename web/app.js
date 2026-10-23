import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.4/+esm";
import { SUPABASE_URL, SUPABASE_KEY, GEMINI_MODEL } from "./config.js";

const supabase = createClient(SUPABASE_URL, SUPABASE_KEY, {
  auth: {
    flowType: "pkce",
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
  },
});

const $ = (id) => document.getElementById(id);
const SITE = location.origin + location.pathname;

const LS_KEY = "vix_gemini_key";
const LS_SEARCH = "vix_web_search";
const DEFAULT_STATUS = "YOUR AI ASSISTANT";
const MAX_HISTORY = 20;
const SYSTEM_PROMPT =
  "You are Vix, The AI mode for VIX . Answer in clear, conversational text " +
  "with no markdown headings, no asterisks and no bullet symbols. Put any code " +
  "inside triple backtick blocks. Keep answers short unless asked for detail.";

// ------------------------------------------------------------
// State
// ------------------------------------------------------------
let session = null;
let appStarted = false;
let recovering = false;
let authMode = "login";
let authBusy = false;

let chats = [];
let historyLoaded = false;
let historyError = null;
let chatId = null;
let chatToken = 0;
let convo = []; // [{ role: "user" | "assistant", content }]
let replyingTo = null;
let busy = false;
let saveQueue = Promise.resolve();
let saveWarned = false;
let emptyShown = false;
let searchRefused = false;
let installEvent = null;

// ------------------------------------------------------------
// Small helpers
// ------------------------------------------------------------
function showScreen(name) {
  for (const id of ["splash", "auth", "main", "profile"]) {
    $(id).classList.toggle("hidden", id !== name);
  }
}

let toastTimer = null;
function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3500);
}

function setStatus(text) {
  $("status").textContent = text;
}

function initials(email) {
  const local = (email || "").split("@")[0];
  const parts = local.split(/[^A-Za-z]+/).filter(Boolean);
  let text = "V";
  if (parts.length >= 2) text = parts[0][0] + parts[1][0];
  else if (parts.length === 1) text = parts[0].slice(0, 2);
  return text.toUpperCase();
}

function chatTitle(text) {
  const oneLine = text.trim().replace(/\s+/g, " ");
  return oneLine.length > 40 ? oneLine.slice(0, 40) + "…" : oneLine;
}

function openSheet(title, items) {
  $("sheet-title").textContent = title || "";
  $("sheet-title").classList.toggle("hidden", !title);
  const box = $("sheet-items");
  box.innerHTML = "";
  for (const item of items) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "sheet-item" + (item.danger ? " danger" : "");
    b.textContent = item.label;
    b.onclick = () => {
      closeSheet();
      item.run();
    };
    box.appendChild(b);
  }
  $("sheet").classList.remove("hidden");
}

function closeSheet() {
  $("sheet").classList.add("hidden");
}

// ------------------------------------------------------------
// Login pages
// ------------------------------------------------------------
function setAuthMsg(text, isError) {
  const el = $("auth-msg");
  el.textContent = text || "";
  el.classList.toggle("hidden", !text);
  el.classList.toggle("error", !!isError);
}

function setAuthMode(mode, message, isError) {
  authMode = mode;

  const titles = {
    login: ["Welcome back", "Log in to your VIX account"],
    signup: ["Create your account", "Your chats are saved to your account"],
    forgot: ["Reset your password", "Enter your email and we'll send you a reset link"],
    newpassword: ["Choose a new password", "Pick a password you'll remember"],
  };
  const primary = {
    login: "Log in",
    signup: "Sign up",
    forgot: "Send reset link",
    newpassword: "Save password",
  };
  const switchText = {
    login: "New to VIX? Sign up",
    signup: "Already have an account? Log in",
    forgot: "Back to log in",
    newpassword: "Skip for now",
  };

  $("auth-title").textContent = titles[mode][0];
  $("auth-sub").textContent = titles[mode][1];
  $("auth-primary").textContent = primary[mode];
  $("link-switch").textContent = switchText[mode];

  $("email-wrap").classList.toggle("hidden", mode === "newpassword");
  $("pw-wrap").classList.toggle("hidden", mode === "forgot");
  $("confirm-wrap").classList.toggle("hidden", !(mode === "signup" || mode === "newpassword"));
  $("link-forgot").classList.toggle("hidden", mode !== "login");
  $("or-row").classList.toggle("hidden", mode === "forgot" || mode === "newpassword");
  $("google").classList.toggle("hidden", mode === "forgot" || mode === "newpassword");

  $("password").value = "";
  $("confirm").value = "";
  $("password").placeholder =
    mode === "login" ? "Password" : "Password (at least 6 characters)";
  $("password").autocomplete = mode === "login" ? "current-password" : "new-password";

  setAuthMsg(message, isError);
}

function setAuthBusy(on, label) {
  authBusy = on;
  const b = $("auth-primary");
  b.disabled = on;
  if (on) b.textContent = label;
  else setAuthMode(authMode, $("auth-msg").classList.contains("hidden") ? "" : $("auth-msg").textContent, $("auth-msg").classList.contains("error"));
}

function friendlyAuthError(err) {
  const code = err && err.code;
  const text = (err && err.message) || "";
  if (code === "invalid_credentials") return "Wrong email or password.";
  if (code === "email_not_confirmed") return "Please confirm your email first. Check your inbox.";
  if (code === "user_already_exists" || code === "email_exists") return "An account with this email already exists. Try logging in.";
  if (code === "weak_password") return "That password is too weak. Try a longer one.";
  if (code === "same_password") return "Choose a password you haven't used before.";
  if (code === "signup_disabled") return "Sign ups are turned off right now.";
  if (code === "over_email_send_rate_limit" || (err && err.status === 429)) return "Too many tries. Wait a minute and try again.";
  if (/failed to fetch|network/i.test(text)) return "No internet connection.";
  return text || "Something went wrong.";
}

async function authSubmit(event) {
  event.preventDefault();
  if (authBusy) return;

  const email = $("email").value.trim();
  const password = $("password").value;
  const confirm = $("confirm").value;

  if (authMode !== "newpassword" && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    setAuthMsg("Enter a valid email address.", true);
    return;
  }
  if (authMode === "login" && !password) {
    setAuthMsg("Enter your password.", true);
    return;
  }
  if (authMode === "signup" || authMode === "newpassword") {
    if (password.length < 6) {
      setAuthMsg("Use at least 6 characters for the password.", true);
      return;
    }
    if (password !== confirm) {
      setAuthMsg("The two passwords don't match.", true);
      return;
    }
  }

  setAuthMsg("", false);
  const busyLabels = {
    login: "Logging in…",
    signup: "Creating account…",
    forgot: "Sending…",
    newpassword: "Saving…",
  };
  authBusy = true;
  $("auth-primary").disabled = true;
  $("auth-primary").textContent = busyLabels[authMode];

  let message = "";
  let isError = false;
  let nextMode = authMode;

  try {
    if (authMode === "login") {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) throw error;
    } else if (authMode === "signup") {
      const { data, error } = await supabase.auth.signUp({
        email,
        password,
        options: { emailRedirectTo: SITE },
      });
      if (error) throw error;
      if (!data.session) {
        nextMode = "login";
        message = "Account created. Check your email to confirm it, then log in.";
      }
    } else if (authMode === "forgot") {
      const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo: SITE });
      if (error) throw error;
      message = "If that email has an account, a reset link is on its way. Open it in this browser.";
    } else if (authMode === "newpassword") {
      const { error } = await supabase.auth.updateUser({ password });
      if (error) throw error;
      recovering = false;
      toast("Password updated.");
      enterApp();
    }
  } catch (err) {
    message = friendlyAuthError(err);
    isError = true;
  }

  authBusy = false;
  $("auth-primary").disabled = false;
  if (authMode === "login" && !isError) {
    // Signed in: the auth listener opens the app.
    setAuthMode("login", "", false);
    return;
  }
  setAuthMode(nextMode, message, isError);
}

async function googleSignIn() {
  if (authBusy) return;
  const { error } = await supabase.auth.signInWithOAuth({
    provider: "google",
    options: { redirectTo: SITE },
  });
  if (error) setAuthMsg(friendlyAuthError(error), true);
}

$("auth-form").addEventListener("submit", authSubmit);
$("google").onclick = googleSignIn;
$("link-forgot").onclick = () => setAuthMode("forgot");
$("link-switch").onclick = () => {
  if (authMode === "login") setAuthMode("signup");
  else if (authMode === "newpassword") {
    recovering = false;
    enterApp();
  } else setAuthMode("login");
};
$("pw-toggle").onclick = () => {
  const show = $("password").type === "password";
  $("password").type = show ? "text" : "password";
  $("pw-toggle").textContent = show ? "Hide" : "Show";
};

supabase.auth.onAuthStateChange((event, newSession) => {
  session = newSession;
  // Do the work outside this callback, as Supabase asks.
  setTimeout(() => {
    if (location.search.includes("code=") || location.hash.includes("access_token")) {
      history.replaceState({}, "", location.pathname);
    }

    if (event === "PASSWORD_RECOVERY") {
      recovering = true;
      showScreen("auth");
      setAuthMode("newpassword");
      return;
    }

    if (newSession) {
      if (!recovering) enterApp();
    } else {
      appStarted = false;
      showScreen("auth");
      setAuthMode("login");
    }
  }, 0);
});

// ------------------------------------------------------------
// Starting the app
// ------------------------------------------------------------
function enterApp() {
  if (!appStarted) {
    appStarted = true;
    startApp();
  }
  showScreen("main");
}

function startApp() {
  const email = (session && session.user && session.user.email) || "";
  $("avatar-small").textContent = initials(email);
  $("avatar-big").textContent = initials(email);
  $("profile-email-small").textContent = email || "My profile";
  $("profile-email").textContent = email || "VIX account";
  $("account-email").textContent = email || "Not available";
  $("opt-search").checked = localStorage.getItem(LS_SEARCH) === "1";
  updateKeyStatus();

  chats = [];
  historyLoaded = false;
  historyError = null;
  resetChatState();
  showEmptyState();
  loadHistory();
}

// ------------------------------------------------------------
// Chat bubbles
// ------------------------------------------------------------
function renderRich(el, text) {
  const parts = String(text).split("```");
  parts.forEach((part, i) => {
    if (i % 2 === 1) {
      const newline = part.indexOf("\n");
      const firstLine = newline >= 0 ? part.slice(0, newline).trim() : "";
      const code = newline >= 0 && /^[\w+#.-]*$/.test(firstLine) ? part.slice(newline + 1) : part;
      const pre = document.createElement("pre");
      const c = document.createElement("code");
      c.textContent = code.replace(/\n$/, "");
      pre.appendChild(c);
      el.appendChild(pre);
    } else {
      const trimmed = part.replace(/^\n+|\n+$/g, "");
      if (trimmed) {
        const block = document.createElement("div");
        block.className = "plain";
        block.textContent = trimmed;
        el.appendChild(block);
      }
    }
  });
  if (!el.firstChild) el.textContent = String(text);
}

function scrollDown() {
  setTimeout(() => {
    const c = $("conversation");
    c.scrollTop = c.scrollHeight;
  }, 0);
}

function addBubble(text, who, quote, isError) {
  if (emptyShown) {
    emptyShown = false;
    $("conversation").innerHTML = "";
  }

  const row = document.createElement("div");
  row.className = "row " + who;

  const bubble = document.createElement("div");
  bubble.className = "bubble" + (isError ? " error" : "");

  if (quote) {
    const q = document.createElement("div");
    q.className = "quote";
    q.textContent = quote;
    bubble.appendChild(q);
  }

  const body = document.createElement("div");
  renderRich(body, text);
  bubble.appendChild(body);

  const reply = document.createElement("button");
  reply.type = "button";
  reply.className = "reply-btn";
  reply.title = "Reply";
  reply.setAttribute("aria-label", "Reply to this message");
  reply.textContent = "↩";
  reply.onclick = () => startReply(text);

  row.append(bubble, reply);
  $("conversation").appendChild(row);
  scrollDown();
}

function showEmptyState() {
  $("conversation").innerHTML = "";
  emptyShown = false;
  addBubble(
    "Hi, I'm VIX AI. Ask me a question, get help with writing or code, or search the web.",
    "assistant"
  );
  emptyShown = true;

  const spacer = document.createElement("div");
  spacer.className = "spacer";
  $("conversation").appendChild(spacer);

  addSuggestion("✍️   Write or edit", "Help me write ");
  addSuggestion("💻   Help with code", "Help me with this code: ");
  addSuggestion("🔍   Search the web", "Search for ");
}

function addSuggestion(label, prefill) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "suggestion";
  b.textContent = label;
  b.onclick = () => prefillInput(prefill);
  $("conversation").appendChild(b);
}

function prefillInput(text) {
  const input = $("input");
  input.value = text;
  autoGrow();
  input.focus();
  input.setSelectionRange(text.length, text.length);
}

function showTyping() {
  const row = document.createElement("div");
  row.id = "typing-row";
  row.className = "row assistant";
  row.innerHTML = '<div class="bubble typing"><span></span><span></span><span></span></div>';
  $("conversation").appendChild(row);
  scrollDown();
}

function hideTyping() {
  const row = $("typing-row");
  if (row) row.remove();
}

function startReply(text) {
  replyingTo = text;
  $("reply-preview").textContent = text;
  $("reply-bar").classList.remove("hidden");
  $("input").placeholder = "Type your reply...";
  $("input").focus();
}

function clearReply() {
  replyingTo = null;
  $("reply-bar").classList.add("hidden");
  $("input").placeholder = "Message VIX AI...";
}

// ------------------------------------------------------------
// Asking the AI
// ------------------------------------------------------------
class AiError extends Error {
  constructor(kind, status, detail) {
    super(detail || kind);
    this.kind = kind;
    this.status = status || 0;
    this.detail = detail || "";
  }
}

// Turns the saved chat into what Gemini expects: starts with the user,
// alternates sides, ends with the AI, and keeps only the latest messages.
function geminiHistory(list) {
  const out = [];
  for (const m of list) {
    if (!m.content || !m.content.trim()) continue;
    const role = m.role === "assistant" ? "model" : "user";
    const last = out[out.length - 1];
    if (last && last.role === role) last.text += "\n" + m.content;
    else out.push({ role, text: m.content });
  }
  while (out.length > MAX_HISTORY) out.shift();
  while (out.length && out[0].role !== "user") out.shift();
  if (out.length && out[out.length - 1].role === "user") out.pop();
  return out;
}

async function askAI(history, outgoing) {
  const messages = [...history, { role: "user", text: outgoing }];
  const search = localStorage.getItem(LS_SEARCH) === "1";
  const ownKey = (localStorage.getItem(LS_KEY) || "").trim();
  return ownKey
    ? askGeminiDirect(ownKey, messages, search)
    : askViaServer(messages, search);
}

function extractGeminiText(json) {
  const blocked = json && json.promptFeedback && json.promptFeedback.blockReason;
  if (blocked) throw new AiError("blocked", 0, String(blocked));
  const parts = json && json.candidates && json.candidates[0] && json.candidates[0].content && json.candidates[0].content.parts;
  const text = Array.isArray(parts)
    ? parts.map((p) => (typeof p.text === "string" ? p.text : "")).join("").trim()
    : "";
  if (!text) throw new AiError("empty", 0, "");
  return text;
}

// The user's own key: the browser talks to Google directly.
async function askGeminiDirect(key, messages, search) {
  const contents = messages.map((m) => ({ role: m.role, parts: [{ text: m.text }] }));

  const call = async (withSearch) => {
    const body = { system_instruction: { parts: [{ text: SYSTEM_PROMPT }] }, contents };
    if (withSearch) body.tools = [{ google_search: {} }];
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": key },
        body: JSON.stringify(body),
      }
    );
    const raw = await res.text();
    let json = {};
    try { json = JSON.parse(raw); } catch (e) { /* keep empty */ }
    return { ok: res.ok, status: res.status, json, raw };
  };

  const wantSearch = search && !searchRefused;
  let r = await call(wantSearch);

  if (!r.ok && wantSearch && [400, 403, 429].includes(r.status) && /search|grounding|tool/i.test(r.raw)) {
    // Free keys can be refused search: answer without it instead of failing.
    searchRefused = true;
    r = await call(false);
  }

  if (!r.ok) {
    const detail = (r.json && r.json.error && r.json.error.message) || "";
    throw new AiError("gemini_error", r.status, detail);
  }
  return extractGeminiText(r.json);
}

// No own key: ask the server, which holds the default key.
async function askViaServer(messages, search) {
  const { data, error } = await supabase.functions.invoke("gemini-chat", {
    body: { messages, search },
  });

  if (error) {
    let body = null;
    try { body = await error.context.json(); } catch (e) { /* no body */ }
    if (body && body.error) {
      throw new AiError(body.error, body.status || 0, body.detail || "");
    }
    throw new AiError("server", 0, "");
  }
  if (data && data.error) throw new AiError(data.error, data.status || 0, data.detail || "");
  if (!data || !data.text) throw new AiError("empty", 0, "");
  return data.text;
}

function friendlyAiError(err) {
  if (!(err instanceof AiError)) {
    const text = (err && err.message) || "";
    if (/failed to fetch|network/i.test(text)) return "No internet connection.";
    return text || "Something went wrong.";
  }

  switch (err.kind) {
    case "daily_limit":
      return "You've used today's messages on the shared key. Paste your own Gemini key in Profile to keep chatting, or try again tomorrow.";
    case "no_default_key":
      return "The free public quota has run out. Add your own API key in Profile to continue using the AI service.";
    case "not_signed_in":
      return "Please log in again.";
    case "gemini_busy":
      return "Free limit reached. Wait a bit and try again.";
    case "blocked":
      return `Google blocked this request (${err.detail}).`;
    case "empty":
    case "empty_answer":
      return "VIX AI returned no answer. Try again.";
    case "gemini_error": {
      const s = err.status;
      if (s === 400 && /api key/i.test(err.detail)) return "Google rejected the key. Paste a valid Gemini key in Profile.";
      if (s === 400) return `Bad request (HTTP 400): ${err.detail.slice(0, 160)}`;
      if (s === 401 || s === 403) return `Google rejected the key or this model isn't allowed (HTTP ${s}). Check your key in Profile.`;
      if (s === 404) return "Model not found (HTTP 404).";
      if (s === 429) return "Free limit reached (HTTP 429). Wait a bit and try again.";
      if (s >= 500) return `Google's server is busy (HTTP ${s}). Try again in a moment.`;
      return `VIX AI error (HTTP ${s}). ${err.detail.slice(0, 160)}`;
    }
    default:
      return `VIX AI error (${err.kind}). ${err.detail}`.trim();
  }
}

// ------------------------------------------------------------
// Sending a message
// ------------------------------------------------------------
async function sendMessage(raw) {
  const text = raw.trim();
  if (!text) return;

  const quote = replyingTo;
  const history = geminiHistory(convo);

  addBubble(text, "user", quote);
  convo.push({ role: "user", content: text });
  queueSave("user", text, quote);
  clearReply();

  const outgoing = quote
    ? `I'm replying to this earlier message: "${quote.slice(0, 400)}"\n\n${text}`
    : text;

  busy = true;
  setStatus("THINKING…");
  showTyping();

  try {
    const answer = await askAI(history, outgoing);
    hideTyping();
    addBubble(answer, "assistant");
    convo.push({ role: "assistant", content: answer });
    queueSave("assistant", answer, null);
    setStatus("DONE");
  } catch (err) {
    hideTyping();
    addBubble(friendlyAiError(err), "assistant", null, true);
    setStatus("NEEDS ATTENTION");
  } finally {
    busy = false;
  }
}

// ------------------------------------------------------------
// Saving and loading chats (same tables as the app)
// ------------------------------------------------------------
function queueSave(role, content, quote) {
  saveQueue = saveQueue
    .then(() => saveMessage(role, content, quote))
    .catch(() => {});
}

async function ensureChat(firstText) {
  if (chatId) return chatId;

  const token = chatToken;
  const { data, error } = await supabase
    .from("chats")
    .insert({ title: chatTitle(firstText) })
    .select("id")
    .single();
  if (error) throw error;

  if (token !== chatToken) {
    // The user moved to another chat meanwhile: drop the empty one.
    supabase.from("chats").delete().eq("id", data.id).then(() => {});
    return null;
  }

  chatId = data.id;
  loadHistory();
  return chatId;
}

async function saveMessage(role, content, quote) {
  try {
    const id = await ensureChat(content);
    if (!id) return;
    const { error } = await supabase
      .from("messages")
      .insert({ chat_id: id, role, content, quote: quote || null });
    if (error) throw error;
  } catch (err) {
    if (!saveWarned) {
      saveWarned = true;
      toast("Couldn't save this chat: " + ((err && err.message) || err));
    }
  }
}

async function loadHistory() {
  const { data, error } = await supabase
    .from("chats")
    .select("id,title")
    .order("updated_at", { ascending: false })
    .limit(200);

  historyLoaded = true;
  if (error) {
    historyError = error.message;
  } else {
    chats = data;
    historyError = null;
  }
  renderHistory();
}

function renderHistory() {
  const box = $("history");
  box.innerHTML = "";

  const query = $("search").value.trim().toLowerCase();
  const shown = query ? chats.filter((c) => c.title.toLowerCase().includes(query)) : chats;

  if (shown.length === 0) {
    const note = document.createElement("div");
    note.className = "history-note";
    if (historyError) note.textContent = "Couldn't load chats: " + historyError;
    else if (!historyLoaded) note.textContent = "Loading chats…";
    else if (query) note.textContent = "No chats found.";
    else note.textContent = "No chats yet. Start a new one!";
    box.appendChild(note);
    return;
  }

  for (const chat of shown) {
    const row = document.createElement("div");
    row.className = "chat-row" + (chat.id === chatId ? " current" : "");

    const title = document.createElement("button");
    title.type = "button";
    title.className = "chat-title";
    title.textContent = chat.title;
    title.onclick = () => openChat(chat);

    const more = document.createElement("button");
    more.type = "button";
    more.className = "chat-more";
    more.textContent = "⋯";
    more.setAttribute("aria-label", "Chat options");
    more.onclick = () => chatOptions(chat);

    row.append(title, more);
    box.appendChild(row);
  }
}

function chatOptions(chat) {
  openSheet(chat.title, [
    { label: "Rename", run: () => renameChat(chat) },
    { label: "Delete", danger: true, run: () => deleteChat(chat) },
  ]);
}

async function renameChat(chat) {
  const name = prompt("Rename chat", chat.title);
  if (name === null) return;
  const title = name.trim();
  if (!title) return;
  const { error } = await supabase.from("chats").update({ title }).eq("id", chat.id);
  if (error) toast("Couldn't rename: " + error.message);
  loadHistory();
}

async function deleteChat(chat) {
  if (!confirm("Delete this chat? It will be removed from your account.")) return;
  const { error } = await supabase.from("chats").delete().eq("id", chat.id);
  if (error) {
    toast("Couldn't delete: " + error.message);
    return;
  }
  if (chat.id === chatId && !busy) newChat();
  loadHistory();
}

function resetChatState() {
  chatToken++;
  chatId = null;
  convo = [];
  clearReply();
  $("input").value = "";
  autoGrow();
}

function newChat() {
  if (busy) {
    toast("Please wait for the current reply.");
    return;
  }
  closeDrawer();
  resetChatState();
  showEmptyState();
  setStatus(DEFAULT_STATUS);
  renderHistory();
}

async function openChat(chat) {
  if (busy) {
    toast("Please wait for the current reply.");
    return;
  }
  closeDrawer();
  if (chat.id === chatId) return;

  resetChatState();
  const token = chatToken;
  chatId = chat.id;
  emptyShown = false;
  $("conversation").innerHTML = '<div class="history-note">Loading chat…</div>';
  setStatus(DEFAULT_STATUS);
  renderHistory();

  const { data, error } = await supabase
    .from("messages")
    .select("role,content,quote")
    .eq("chat_id", chat.id)
    .order("created_at", { ascending: true })
    .limit(1000);

  if (token !== chatToken) return;
  $("conversation").innerHTML = "";

  if (error) {
    addBubble("I couldn't load this chat: " + error.message, "assistant", null, true);
    return;
  }
  if (data.length === 0) showEmptyState();

  // The AI continues from these messages (see geminiHistory).
  for (const m of data) {
    addBubble(m.content, m.role === "user" ? "user" : "assistant", m.quote);
    convo.push({ role: m.role, content: m.content });
  }
}

// ------------------------------------------------------------
// Left menu
// ------------------------------------------------------------
function openDrawer() {
  loadHistory();
  $("scrim").classList.remove("hidden");
  $("drawer").classList.add("open");
}

function closeDrawer() {
  $("scrim").classList.add("hidden");
  $("drawer").classList.remove("open");
}

$("menu-btn").onclick = openDrawer;
$("scrim").onclick = closeDrawer;
$("new-btn").onclick = newChat;
$("drawer-new").onclick = newChat;
$("search-btn").onclick = () => {
  const box = $("search");
  if (box.classList.contains("hidden")) {
    box.classList.remove("hidden");
    box.focus();
  } else {
    box.value = "";
    box.classList.add("hidden");
    renderHistory();
  }
};
$("search").addEventListener("input", renderHistory);
$("profile-row").onclick = () => {
  closeDrawer();
  showScreen("profile");
};

// ------------------------------------------------------------
// Composer
// ------------------------------------------------------------
function autoGrow() {
  const el = $("input");
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 140) + "px";
}

$("input").addEventListener("input", autoGrow);
$("input").addEventListener("keydown", (e) => {
  const touch = matchMedia("(pointer: coarse)").matches;
  if (e.key === "Enter" && !e.shiftKey && !touch) {
    e.preventDefault();
    $("composer").requestSubmit();
  }
});

$("composer").addEventListener("submit", (e) => {
  e.preventDefault();
  const text = $("input").value;
  if (!text.trim()) return;
  if (busy) {
    toast("Please wait for the current reply.");
    return;
  }
  $("input").value = "";
  autoGrow();
  sendMessage(text);
});

$("reply-cancel").onclick = clearReply;

$("plus-btn").onclick = () =>
  openSheet("", [
    { label: "✍️   Write or edit", run: () => prefillInput("Help me write ") },
    { label: "💻   Help with code", run: () => prefillInput("Help me with this code: ") },
    { label: "🔍   Search the web", run: () => prefillInput("Search for ") },
  ]);

$("sheet").addEventListener("click", (e) => {
  if (e.target === $("sheet")) closeSheet();
});
$("sheet-cancel").onclick = closeSheet;

// Voice input, where the browser supports it
const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
let recognition = null;

if (!SpeechRec) {
  $("mic-btn").classList.add("hidden");
} else {
  $("mic-btn").onclick = () => {
    if (recognition) {
      recognition.stop();
      return;
    }
    if (busy) {
      toast("Please wait for the current reply.");
      return;
    }

    let heard = "";
    recognition = new SpeechRec();
    recognition.lang = navigator.language || "en-US";
    recognition.interimResults = true;

    recognition.onstart = () => {
      $("mic-btn").classList.add("on");
      setStatus("LISTENING… SPEAK NOW");
    };
    recognition.onresult = (e) => {
      let t = "";
      for (const r of e.results) t += r[0].transcript;
      heard = t;
      $("input").value = t;
      autoGrow();
    };
    recognition.onerror = (e) => {
      if (e.error !== "no-speech" && e.error !== "aborted") {
        toast("Microphone problem: " + e.error);
      }
    };
    recognition.onend = () => {
      recognition = null;
      $("mic-btn").classList.remove("on");
      setStatus(DEFAULT_STATUS);
      const text = heard.trim();
      if (text) {
        $("input").value = "";
        autoGrow();
        sendMessage(text);
      }
    };
    recognition.start();
  };
}

// ------------------------------------------------------------
// Profile page
// ------------------------------------------------------------
function updateKeyStatus() {
  const has = !!(localStorage.getItem(LS_KEY) || "").trim();
  $("key-status").textContent = has
    ? "Your key is saved in this browser and used for your chats."
    : "Not set. VIX AI uses the shared key (limited messages per day).";
  $("key-remove").classList.toggle("hidden", !has);
}

$("profile-back").onclick = () => showScreen("main");

$("opt-search").onchange = () => {
  localStorage.setItem(LS_SEARCH, $("opt-search").checked ? "1" : "0");
  if ($("opt-search").checked) searchRefused = false;
};

$("key-save").onclick = () => {
  const key = $("key-input").value.trim();
  if (!key) {
    toast("Paste your key first.");
    return;
  }
  localStorage.setItem(LS_KEY, key);
  $("key-input").value = "";
  searchRefused = false;
  updateKeyStatus();
  toast("Saved in this browser.");
};

$("key-remove").onclick = () => {
  localStorage.removeItem(LS_KEY);
  updateKeyStatus();
  toast("Key removed.");
};

$("delete-history").onclick = async () => {
  if (!confirm("Delete ALL your saved chats? This can't be undone.")) return;
  const userId = session && session.user && session.user.id;
  if (!userId) return;
  const { error } = await supabase.from("chats").delete().eq("user_id", userId);
  if (error) {
    toast("Couldn't delete: " + error.message);
    return;
  }
  toast("Chat history deleted.");
  if (!busy) {
    resetChatState();
    showEmptyState();
  }
  loadHistory();
};

$("logout").onclick = async () => {
  if (!confirm("Log out?")) return;
  await supabase.auth.signOut();
};

window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  installEvent = e;
  $("install-btn").classList.remove("hidden");
});

$("install-btn").onclick = async () => {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice;
  installEvent = null;
  $("install-btn").classList.add("hidden");
};

// ------------------------------------------------------------
// Installable app
// ------------------------------------------------------------
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("sw.js").catch(() => {});
  });
}

showScreen("splash");
