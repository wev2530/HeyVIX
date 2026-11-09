import { showDialog } from "./dialog.js";

// Connections for the web app. This does the same as the Android app
// (AgentClient.kt + the Connections part of ProfileActivity.kt):
//
// - The Profile page lists GitHub, Supabase, Webflow and Dropbox, plus custom
//   connectors (any MCP address). Tokens go to the Supabase function "vix-agent",
//   which checks them and stores them encrypted. They are never kept in this browser.
// - "vix-oauth" lets the user sign in on the service's website instead of pasting a token.
// - In the chat, when the user has their OWN AI key and at least one connection,
//   the question goes to "vix-agent" so the AI can use those services as tools.
//   Anything that changes something waits for Allow / Deny first.
//
// This browser only remembers WHICH services are connected (names, no tokens).

export const SERVICES = [
  {
    id: "github",
    label: "GitHub",
    hint:
      "Create a personal access token at github.com/settings/tokens and give it access to " +
      "your repositories. To let VIX make changes, it needs write access.",
  },
  {
    id: "supabase",
    label: "Supabase",
    hint: "Create an access token at supabase.com/dashboard/account/tokens.",
  },
  {
    id: "webflow",
    label: "Webflow",
    hint:
      "Create a Site API token in Webflow: Site settings, Apps and integrations, API access.",
  },
  {
    id: "dropbox",
    label: "Dropbox",
    hint:
      "Create an access token in the Dropbox App Console (your app, Settings, Generated " +
      "access token). It stops working after about 4 hours.",
  },
];

const LS_CONNECTED = "vix_connected";
const STALE_MS = 10 * 60 * 1000;

// hasOwnKey: () => boolean, true when the user saved their own AI key.
export function createConnections({ supabase, $, toast, openSheet, hasOwnKey }) {
  let modes = {}; // service id -> "read" | "write"
  let custom = []; // [{ service, label, host, mode }]
  let loaded = false;
  let oauth = new Set(); // services that have browser sign-in on the server
  let lastRefresh = 0;
  let waitingSignIn = false;

  // ---------- what is connected (names only, no tokens) ----------

  function cachedNames() {
    try {
      const list = JSON.parse(localStorage.getItem(LS_CONNECTED) || "[]");
      return Array.isArray(list) ? list : [];
    } catch (e) {
      return [];
    }
  }

  function setCache(names) {
    try {
      localStorage.setItem(LS_CONNECTED, JSON.stringify(names));
    } catch (e) { /* private mode: the chat just skips the tools */ }
  }

  function hasConnections() {
    return cachedNames().length > 0;
  }

  function clearCache() {
    setCache([]);
    modes = {};
    custom = [];
    loaded = false;
    lastRefresh = 0;
  }

  // ---------- network ----------

  // One call to an Edge Function with the logged-in user's session.
  // Never throws for a bad HTTP status: json.error always says what happened.
  async function invoke(fn, body) {
    const { data, error } = await supabase.functions.invoke(fn, { body });
    if (error) {
      let json = {};
      try { json = await error.context.json(); } catch (e) { /* no body */ }
      const status = (error.context && error.context.status) || 0;
      if (!json || typeof json !== "object") json = {};
      if (!json.error) json.error = status ? "http_" + status : "network";
      return { ok: false, status, json };
    }
    return { ok: true, status: 200, json: data || {} };
  }

  function errorText(json) {
    const code = (json && json.error) || "";
    const detail = (json && json.detail) || "";
    switch (code) {
      case "network": return "No internet connection.";
      case "not_configured": return "Connections aren't set up on the server yet.";
      case "token_invalid": return ("That token was refused. Check it and try again. " + detail).slice(0, 200);
      case "not_signed_in":
      case "http_401": return "Please log in again.";
      case "bad_request": return "Something is missing. Check the token and try again.";
      case "connections_failed": return "The server couldn't save your connections. Try again.";
      case "connector_failed": return ("I couldn't connect to that connector. " + detail).slice(0, 200);
      case "too_many_connectors": return "You can add up to 5 custom connectors.";
      default: return `Something went wrong (${code}).`;
    }
  }

  function parseList(json) {
    const m = {};
    const c = [];
    for (const item of json.connections || []) {
      if (!item || !item.service) continue;
      const mode = item.mode || "read";
      m[item.service] = mode;
      if (String(item.service).startsWith("mcp:")) {
        c.push({
          service: item.service,
          label: item.label || String(item.service).slice(4),
          host: item.host || "",
          mode,
        });
      }
    }
    return { m, c };
  }

  // Asks the server what is connected. Returns { ok, message }.
  async function fetchList() {
    const r = await invoke("vix-agent", { action: "list" });
    if (!r.ok) return { ok: false, message: errorText(r.json) };
    const { m, c } = parseList(r.json);
    modes = m;
    custom = c;
    loaded = true;
    setCache(Object.keys(m));
    lastRefresh = Date.now();
    return { ok: true, message: "" };
  }

  // Updates the list of connected services if it was not checked for 10 minutes,
  // and renews sign-in tokens that are about to run out (for example Dropbox).
  async function refreshIfStale() {
    if (Date.now() - lastRefresh < STALE_MS) return;
    lastRefresh = Date.now();
    try { await invoke("vix-oauth", { action: "refresh" }); } catch (e) { /* old token may still work */ }
    try {
      const r = await invoke("vix-agent", { action: "list" });
      if (r.ok) setCache(Object.keys(parseList(r.json).m));
    } catch (e) { /* keep the old list */ }
  }

  async function loadProviders() {
    try {
      const r = await invoke("vix-oauth", { action: "providers" });
      if (r.ok && Array.isArray(r.json.services)) {
        oauth = new Set(r.json.services.filter(Boolean));
      }
    } catch (e) { /* no sign-in buttons then: pasting a token still works */ }
  }

  // ---------- the chat with tools ----------

  // Returns the answer, or null when the server says there are no connections
  // (then the normal direct call is used instead).
  // messages: [{ role: "user" | "model", text }], ending with the new question.
  async function askViaAgent(cfg, messages, defaultModel) {
    const common = () => ({
      action: "chat",
      provider: cfg.provider,
      key: cfg.key,
      model: cfg.model || defaultModel || "",
      base: cfg.provider === "custom" ? cfg.base : "",
    });

    let body = { ...common(), messages };

    for (let round = 0; round < 5; round++) {
      const r = await invoke("vix-agent", body);
      const json = r.json;

      if (json.error) {
        if (json.error === "no_connections") {
          clearCache();
          return null;
        }
        throw new Error(agentMessage(json.error, json));
      }

      if (Array.isArray(json.confirm) && json.confirm.length > 0) {
        const items = json.confirm.map((c) => ({
          id: String((c && c.id) || ""),
          name: String((c && c.name) || ""),
          summary: String((c && c.summary) || ""),
        }));
        const decisions = await askApproval(items);
        body = { ...common(), state: json.state || [], decisions };
        continue;
      }

      const text = String(json.text || "").trim();
      if (!text) throw new Error("VIX AI returned no answer. Try again.");
      return text;
    }

    throw new Error("That needed too many approval steps. Try a simpler request.");
  }

  function agentMessage(code, json) {
    const detail = String(json.detail || "").slice(0, 160);
    switch (code) {
      case "network": return "Couldn't reach the server. Check your internet connection.";
      case "not_configured": return "Connections aren't set up on the server yet.";
      case "not_signed_in":
      case "http_401": return "Please log in again to use VIX AI.";
      case "provider_error": return `The AI provider returned an error (HTTP ${json.status || "?"}). ${detail}`.trim();
      case "provider_setup": return "Your provider settings are incomplete. Open Profile and check the base URL and model name.";
      case "too_many_steps": return "I used too many steps. Try a simpler request.";
      case "timeout": return "That took too long. Try a simpler request.";
      case "empty_answer": return "VIX AI returned no answer. Try again.";
      case "agent_failed": return "Something went wrong: " + detail;
      default: return `VIX AI error (${code}).`;
    }
  }

  // The Allow / Deny question before the AI changes anything.
  // Resolves to { id: true | false }. Closing it any other way is not possible.
  async function askApproval(items) {
    const list = document.createElement("div");
    list.className = "approval-list";
    for (const it of items) {
      const p = document.createElement("div");
      p.className = "approval-item";
      p.textContent = "• " + (it.summary || it.name);
      list.appendChild(p);
    }

    const allowed = await showDialog({
      title: "Allow VIX to do this?",
      content: list,
      cancelable: false,
      buttons: [
        { label: "Deny", value: false },
        { label: "Allow", value: true, primary: true },
      ],
    });

    const out = {};
    for (const it of items) out[it.id] = allowed === true;
    return out;
  }

  // ---------- Profile page ----------

  function makeRow(title, sub, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "row-card btn-card";
    const text = document.createElement("span");
    text.className = "row-text";
    const t = document.createElement("span");
    t.className = "row-title";
    t.textContent = title;
    text.appendChild(t);
    if (sub) {
      const s = document.createElement("span");
      s.className = "row-sub";
      s.textContent = sub;
      text.appendChild(s);
    }
    b.appendChild(text);
    b.onclick = onClick;
    return b;
  }

  function render() {
    const box = $("connections-box");
    if (!box) return;
    box.innerHTML = "";
    const own = hasOwnKey();

    for (const service of SERVICES) {
      const mode = modes[service.id];
      const canSignIn = oauth.has(service.id);
      let sub;
      if (!mode && canSignIn) sub = "Not connected. Tap to sign in.";
      else if (!mode && loaded) sub = "Not connected. Tap to connect.";
      else if (!mode) sub = "Tap to connect.";
      else if (!own) sub = "Connected, but VIX only uses it with your own AI key. Add one under AI key.";
      else if (mode === "write") sub = "Connected. VIX can make changes, and asks you first.";
      else sub = "Connected. VIX can only read.";
      box.appendChild(makeRow(service.label, sub, () => onServiceClick(service)));
    }

    for (const c of custom) {
      const where = c.host ? c.host + ". " : "";
      let what;
      if (!own) what = "Connected, but VIX only uses it with your own AI key.";
      else if (c.mode === "write") what = "Can make changes, and asks you first.";
      else what = "Read only (only tools the connector marks as read-only).";
      box.appendChild(makeRow(c.label, where + what, () => onCustomClicked(c)));
    }

    box.appendChild(
      makeRow("Add custom connector", "Connect any MCP connector by its address", () => showCustomForm(null))
    );
  }

  // Called when the Profile page opens, and again after coming back from a sign-in.
  async function loadProfile() {
    render();
    await Promise.all([fetchList(), loadProviders()]);
    render();
  }

  // After a change: toast the result and redraw.
  async function afterChange(work, okMessage) {
    const r = await work();
    if (r.ok) {
      const l = await fetchList();
      toast(l.ok ? okMessage : l.message);
    } else {
      toast(r.message);
    }
    render();
  }

  async function call(body, fn = "vix-agent") {
    const r = await invoke(fn, body);
    return r.ok ? { ok: true, json: r.json } : { ok: false, message: errorText(r.json), json: r.json };
  }

  // ---------- small form helpers ----------

  function input(type, placeholder, value) {
    const el = document.createElement("input");
    el.type = type;
    el.placeholder = placeholder;
    el.autocomplete = "off";
    el.className = "modal-input";
    if (value) el.value = value;
    return el;
  }

  function note(text) {
    const p = document.createElement("div");
    p.className = "modal-note";
    p.textContent = text;
    return p;
  }

  function checkbox(text, checked) {
    const label = document.createElement("label");
    label.className = "modal-check";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = !!checked;
    const span = document.createElement("span");
    span.textContent = text;
    label.append(box, span);
    return { label, box };
  }

  // ---------- one service ----------

  function onServiceClick(service) {
    const mode = modes[service.id];
    const canSignIn = oauth.has(service.id);

    if (!mode) {
      if (canSignIn) showSignInForm(service, false);
      else showConnectForm(service, false);
      return;
    }

    const write = mode === "write";
    const items = [];
    if (canSignIn) items.push({ label: "Sign in again", run: () => showSignInForm(service, write) });
    items.push({ label: "Change token", run: () => showConnectForm(service, write) });
    items.push({
      label: write ? "Switch to read only" : "Allow changes (it asks you first)",
      run: () =>
        afterChange(
          () => call({ action: "set_mode", service: service.id, mode: write ? "read" : "write" }),
          "Updated."
        ),
    });
    items.push({ label: "Disconnect", danger: true, run: () => confirmDisconnect(service.id, service.label) });
    openSheet(service.label, items);
  }

  async function showConnectForm(service, currentWrite) {
    const form = document.createElement("div");
    const token = input("password", "Paste your token here");
    const write = checkbox("Allow VIX to make changes (it always asks you first)", currentWrite);
    form.append(note(service.hint), token, write.label);

    const choice = await showDialog({
      title: "Connect " + service.label,
      content: form,
      buttons: [
        { label: "Cancel", value: null },
        { label: "Save", value: "save", primary: true },
      ],
    });
    if (choice !== "save") return;

    const value = token.value.trim();
    if (!value) {
      toast("Nothing was saved. Paste your token first.");
      return;
    }
    toast("Checking the token…");
    await afterChange(
      () => call({ action: "save", service: service.id, token: value, mode: write.box.checked ? "write" : "read" }),
      service.label + " connected."
    );
  }

  async function showSignInForm(service, currentWrite) {
    const form = document.createElement("div");
    const write = checkbox("Allow VIX to make changes (it always asks you first)", currentWrite);
    form.append(
      note(
        `You will sign in on the ${service.label} website, then come back to VIX. ` +
          "No token to copy. Tokens renew by themselves when the service allows it."
      ),
      write.label
    );

    const choice = await showDialog({
      title: "Connect " + service.label,
      content: form,
      buttons: [
        { label: "Cancel", value: null },
        { label: "Use a token", value: "token" },
        { label: "Sign in", value: "signin", primary: true },
      ],
    });
    if (choice === "token") showConnectForm(service, write.box.checked);
    else if (choice === "signin") startSignIn(service, write.box.checked);
  }

  async function startSignIn(service, write) {
    toast("Opening " + service.label + "…");
    // Open the tab right now (a click just happened), fill in the address when it arrives.
    // Browsers block tabs that open after waiting for the network.
    const tab = window.open("", "_blank");

    const r = await call({ action: "start", service: service.id, mode: write ? "write" : "read" }, "vix-oauth");
    const url = r.ok ? String(r.json.url || "") : "";

    if (!url.startsWith("https://")) {
      if (tab) tab.close();
      let message = "I couldn't start the sign-in.";
      const code = r.json && r.json.error;
      if (code === "not_configured") message = "Sign-in isn't set up for this service on the server yet.";
      else if (code === "not_signed_in" || code === "http_401") message = "Please log in again.";
      else if (code === "network") message = "No internet connection.";
      toast(message);
      return;
    }

    waitingSignIn = true;
    if (tab) tab.location.href = url;
    else location.href = url;
  }

  function confirmDisconnect(serviceId, label) {
    showDialog({
      title: `Disconnect ${label}?`,
      message: "Your saved token is deleted from the server.",
      buttons: [
        { label: "Cancel", value: null },
        { label: "Disconnect", value: "yes", danger: true },
      ],
    }).then((v) => {
      if (v === "yes") {
        afterChange(() => call({ action: "remove", service: serviceId }), label + " disconnected.");
      }
    });
  }

  // ---------- custom connectors ----------

  function onCustomClicked(c) {
    const write = c.mode === "write";
    openSheet(c.label, [
      { label: "Update address or token", run: () => showCustomForm(c) },
      {
        label: write ? "Switch to read only" : "Allow changes (it asks you first)",
        run: () =>
          afterChange(
            () => call({ action: "set_mode", service: c.service, mode: write ? "read" : "write" }),
            "Updated."
          ),
      },
      { label: "Disconnect", danger: true, run: () => confirmDisconnect(c.service, c.label) },
    ]);
  }

  // Add a custom connector, or update one that already exists.
  async function showCustomForm(existing) {
    const form = document.createElement("div");
    const name = input("text", "Name, for example Notes", existing ? existing.label : "");
    if (existing) name.disabled = true;
    const url = input("url", "https://example.com/mcp");
    const token = input("password", "Token (optional)");
    const write = checkbox("Allow VIX to make changes (it always asks you first)", existing && existing.mode === "write");
    form.append(
      note(
        "Give it a name, then paste the connector's MCP address (it starts with https). " +
          "VIX will look for it and connect before saving. The token is optional."
      ),
      name,
      url,
      token,
      write.label
    );

    const choice = await showDialog({
      title: existing ? "Update " + existing.label : "Add custom connector",
      content: form,
      buttons: [
        { label: "Cancel", value: null },
        { label: "Connect", value: "go", primary: true },
      ],
    });
    if (choice !== "go") return;

    const n = name.value.trim();
    const u = url.value.trim();
    const t = token.value.trim();
    if (!n) return toast("Nothing was saved. Enter a name.");
    if (!/^https:\/\//i.test(u)) return toast("Nothing was saved. The address must start with https://");

    toast("Looking for the connector…");
    await afterChange(
      () => call({ action: "save_custom", name: n, url: u, token: t, mode: write.box.checked ? "write" : "read" }),
      n + " connected."
    );
  }

  // Back from the sign-in tab: show what is connected now.
  function backFromSignIn() {
    if (!waitingSignIn) return;
    waitingSignIn = false;
    loadProfile();
  }
  window.addEventListener("focus", backFromSignIn);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) backFromSignIn();
  });

  return { hasConnections, clearCache, refreshIfStale, askViaAgent, loadProfile, render };
}
