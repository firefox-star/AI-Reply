import ZAI from "z-ai-web-dev-sdk";

export interface ChatMessage {
  role: string;
  content: string;
}

/** Free GLM models the backend accepts. */
export const ALLOWED_MODELS = [
  "glm-4.5-flash",
  "glm-4-flash",
  "glm-4-flash-250414",
  "glm-4v-flash",
] as const;

export const DEFAULT_MODEL = "glm-4.5-flash";

const MAX_MESSAGE_CHARS = 8000;
const MAX_CONVERSATION_MESSAGES = 30;
const MAX_SYSTEM_CHARS = 2000;

export function normalizeInput(body: {
  message?: unknown;
  conversation?: unknown;
  messages?: unknown;
  model?: unknown;
  system?: unknown;
}): { messages: ChatMessage[]; model: string; error?: string } {
  const msgs: ChatMessage[] = [];

  // System prompt (optional, capped)
  const sys = typeof body.system === "string" ? body.system.trim().slice(0, MAX_SYSTEM_CHARS) : "";
  if (sys) msgs.push({ role: "system", content: sys });

  // New simple format: { message, conversation: [{role, content}...] }
  if (typeof body.message === "string" && body.message.trim()) {
    const conv = Array.isArray(body.conversation) ? body.conversation : [];
    for (const m of conv.slice(-MAX_CONVERSATION_MESSAGES)) {
      const mm = m as { role?: unknown; content?: unknown };
      if (
        (mm?.role === "user" || mm?.role === "assistant") &&
        typeof mm.content === "string" &&
        mm.content.trim()
      ) {
        msgs.push({ role: mm.role, content: mm.content.slice(0, MAX_MESSAGE_CHARS) });
      }
    }
    msgs.push({ role: "user", content: body.message.trim().slice(0, MAX_MESSAGE_CHARS) });
    return { messages: msgs, model: pickModel(body.model), error: undefined };
  }

  // Legacy format: { messages: [{role, content}...] }
  if (Array.isArray(body.messages)) {
    for (const m of body.messages.slice(-MAX_CONVERSATION_MESSAGES)) {
      const mm = m as { role?: unknown; content?: unknown };
      if (
        (mm?.role === "user" || mm?.role === "assistant" || mm?.role === "system") &&
        typeof mm.content === "string" &&
        mm.content.trim()
      ) {
        msgs.push({
          role: mm.role === "system" ? "system" : mm.role,
          content: mm.content.slice(0, MAX_MESSAGE_CHARS),
        });
      }
    }
    if (msgs.some((m) => m.role === "user")) {
      return { messages: msgs, model: pickModel(body.model), error: undefined };
    }
  }

  return { messages: [], model: pickModel(body.model), error: "Provide `message` (string) or `messages` (array)." };
}

function pickModel(model: unknown): string {
  return typeof model === "string" && (ALLOWED_MODELS as readonly string[]).includes(model)
    ? model
    : DEFAULT_MODEL;
}

/** Extract text deltas from one raw SSE line of an OpenAI-compatible stream. */
function deltaFromLine(line: string): string | null {
  const t = line.trim();
  if (!t.startsWith("data:")) return null;
  const payload = t.slice(5).trim();
  if (!payload || payload === "[DONE]") return null;
  try {
    const json = JSON.parse(payload);
    const delta = json?.choices?.[0]?.delta;
    if (typeof delta?.content === "string" && delta.content) return delta.content;
    const msg = json?.choices?.[0]?.message;
    if (typeof msg?.content === "string" && msg.content) return msg.content;
  } catch {
    // partial JSON chunk — ignore
  }
  return null;
}

/**
 * Ask the AI (server-side only — SDK credentials never leave this process).
 * Returns the complete reply text. Handles every known SDK return shape:
 * ReadableStream of raw SSE bytes, plain string, or a whole message object.
 */
export async function askAI(messages: ChatMessage[], model: string): Promise<string> {
  const zai = await ZAI.create();
  const completion: unknown = await zai.chat.completions.create({
    messages: messages.map((m) => ({
      role: m.role as "user" | "assistant" | "system",
      content: m.content,
    })),
    stream: true,
  });

  let out = "";

  const consumeLines = (text: string) => {
    for (const line of text.split("\n")) {
      const d = deltaFromLine(line);
      if (d) out += d;
    }
  };

  if (completion && typeof completion === "object" && "getReader" in (completion as object)) {
    const reader = (completion as ReadableStream<Uint8Array>).getReader();
    const dec = new TextDecoder();
    let carry = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      carry += dec.decode(value, { stream: true });
      const lines = carry.split("\n");
      carry = lines.pop() ?? "";
      consumeLines(lines.join("\n"));
    }
    consumeLines(carry);
  } else if (typeof completion === "string") {
    if (completion.includes("data:")) consumeLines(completion);
    else out = completion;
  } else {
    const whole = (completion as { choices?: Array<{ message?: { content?: string } }> })
      ?.choices?.[0]?.message?.content;
    if (typeof whole === "string") out = whole;
  }

  return out.trim();
}

/** Friendly error message from an SDK/network failure. */
export function friendlyError(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg.includes("429") || msg.toLowerCase().includes("rate")) {
    return "The AI is busy right now (rate limit). Please retry in a few seconds.";
  }
  return `AI error: ${msg}`;
}

/* ------------------------- light in-memory rate limit ------------------------ */

const hits = new Map<string, { count: number; reset: number }>();
const WINDOW_MS = 60_000;
const MAX_PER_WINDOW = 40;

export function rateLimited(ip: string): boolean {
  const now = Date.now();
  const rec = hits.get(ip);
  if (!rec || rec.reset < now) {
    hits.set(ip, { count: 1, reset: now + WINDOW_MS });
    return false;
  }
  rec.count += 1;
  if (hits.size > 5000) hits.clear(); // hard cap memory
  return rec.count > MAX_PER_WINDOW;
}
