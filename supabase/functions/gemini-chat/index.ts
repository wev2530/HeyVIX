import "jsr:@supabase/functions-js/edge-runtime.d.ts";

// The default Gemini key lives ONLY here, as the secret GEMINI_API_KEY.
// Signed-in users of the VIX AI website call this function when they have
// not pasted their own key. Each user gets DAILY_LIMIT messages per day.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-3.5-flash-lite";
const DAILY_LIMIT = Number(Deno.env.get("DAILY_LIMIT") ?? "30");
const MAX_MESSAGES = 40;
const MAX_TEXT = 8000;

const SYSTEM =
  "Your name is Vix. You are part of Vix AI. When someone asks who you are, simply say " +
  "I am Vix, or I am Vix, a part of Vix AI. Never call yourself Vixie, and never describe " +
  "yourself as someone's Vix assistant or personal assistant. Speak in a calm, mature, " +
  "professional tone. Answer in clear, conversational text with no markdown headings, " +
  "no asterisks and no bullet symbols. Put any code inside triple backtick blocks. " +
  "Keep answers short unless asked for detail.";

type Msg = { role: "user" | "model";text: string };

function reply(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// The gateway already checked the token (verify_jwt). We only read who it is.
function userIdFrom(req: Request): string | null {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const part = token.split(".")[1];
  if (!part) return null;
  try {
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded));
    if (payload.role !== "authenticated" || typeof payload.sub !== "string") {
      return null;
    }
    return payload.sub;
  } catch {
    return null;
  }
}

async function callGemini(apiKey: string, contents: unknown[], withSearch: boolean) {
  const body: Record < string, unknown > = {
    system_instruction: { parts: [{ text: SYSTEM }] },
    contents,
  };
  if (withSearch) body.tools = [{ google_search: {} }];
  
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify(body),
    },
  );
  
  const raw = await res.text();
  let json: any = {};
  try {
    json = JSON.parse(raw);
  } catch {
    // keep empty
  }
  return { ok: res.ok, status: res.status, json, raw };
}

function looksLikeSearchRefusal(status: number, raw: string): boolean {
  if (status !== 400 && status !== 403 && status !== 429) return false;
  const text = raw.toLowerCase();
  return text.includes("search") || text.includes("grounding") || text.includes("tool");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS });
  }
  if (req.method !== "POST") {
    return reply({ error: "method_not_allowed" }, 405);
  }
  
  const userId = userIdFrom(req);
  if (!userId) return reply({ error: "not_signed_in" }, 401);
  
  let input: any;
  try {
    input = await req.json();
  } catch {
    return reply({ error: "bad_request" }, 400);
  }
  
  const messages: Msg[] = (Array.isArray(input?.messages) ? input.messages : [])
    .slice(-MAX_MESSAGES)
    .filter((m: any) => (m?.role === "user" || m?.role === "model") && typeof m?.text === "string")
    .map((m: any) => ({ role: m.role, text: String(m.text).slice(0, MAX_TEXT) }))
    .filter((m: Msg) => m.text.trim().length > 0);
  
  if (messages.length === 0 || messages[messages.length - 1].role !== "user") {
    return reply({ error: "bad_request" }, 400);
  }
  
  const apiKey = Deno.env.get("GEMINI_API_KEY");
  if (!apiKey) return reply({ error: "no_default_key" }, 503);
  
  // Count this message against the user's daily limit.
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceKey) return reply({ error: "usage_check_failed" }, 500);
  
  const usage = await fetch(`${supabaseUrl}/rest/v1/rpc/bump_ai_usage`, {
    method: "POST",
    headers: {
      apikey: serviceKey,
      Authorization: `Bearer ${serviceKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ p_user: userId }),
  });
  if (!usage.ok) return reply({ error: "usage_check_failed" }, 500);
  
  const used = Number(await usage.json());
  if (used > DAILY_LIMIT) {
    return reply({ error: "daily_limit", limit: DAILY_LIMIT }, 429);
  }
  
  const contents = messages.map((m) => ({
    role: m.role,
    parts: [{ text: m.text }],
  }));
  
  const wantSearch = input?.search === true;
  let result = await callGemini(apiKey, contents, wantSearch);
  
  if (!result.ok && wantSearch && looksLikeSearchRefusal(result.status, result.raw)) {
    // Free keys can be refused search: answer without it instead of failing.
    result = await callGemini(apiKey, contents, false);
  }
  
  if (!result.ok) {
    if (result.status === 429) return reply({ error: "gemini_busy" }, 429);
    return reply({
      error: "gemini_error",
      status: result.status,
      detail: String(result.json?.error?.message ?? "").slice(0, 200),
    }, 502);
  }
  
  const blocked = result.json?.promptFeedback?.blockReason;
  if (blocked) return reply({ error: "blocked", detail: String(blocked) }, 200);
  
  const parts = result.json?.candidates?.[0]?.content?.parts;
  const text = Array.isArray(parts) ?
    parts.map((p: any) => (typeof p?.text === "string" ? p.text : "")).join("").trim() :
    "";
  
  if (!text) return reply({ error: "empty_answer" }, 200);
  
  return reply({ text, used, limit: DAILY_LIMIT });
});