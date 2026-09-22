"use client";

import { useRef, useState } from "react";

interface Msg {
  role: "user" | "assistant";
  content: string;
  err?: string;
}

const SUGGESTIONS = [
  "Say hello in one short sentence",
  "Explain what an API relay is, briefly",
  "Write a haiku about Android apps",
];

export default function ConsolePage() {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const scrollDown = () =>
    setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);

  const send = async (text?: string) => {
    const content = (text ?? input).trim();
    if (!content || streaming) return;
    setInput("");
    setStreaming(true);

    const history = [...messages, { role: "user" as const, content }];
    setMessages([...history, { role: "assistant", content: "" }]);
    scrollDown();

    const ctrl = new AbortController();
    abortRef.current = ctrl;
    let acc = "";
    let err = "";

    try {
      const res = await fetch("/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          messages: history.map((m) => ({ role: m.role, content: m.content })),
        }),
        signal: ctrl.signal,
      });
      if (!res.ok || !res.body) {
        err = `Server error ${res.status}`;
      } else {
        const reader = res.body.getReader();
        const dec = new TextDecoder();
        let carry = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          carry += dec.decode(value, { stream: true });
          const parts = carry.split("\n\n");
          carry = parts.pop() ?? "";
          for (const part of parts) {
            let event = "message";
            let data = "";
            for (const line of part.split("\n")) {
              if (line.startsWith("event:")) event = line.slice(6).trim();
              else if (line.startsWith("data:")) data += line.slice(5).trim();
            }
            if (!data) continue;
            try {
              const j = JSON.parse(data) as { text?: string; message?: string };
              if (event === "delta" && j.text) {
                acc += j.text;
                setMessages([...history, { role: "assistant", content: acc }]);
                scrollDown();
              } else if (event === "error" && j.message) {
                err = j.message;
              }
            } catch { /* ignore malformed chunk */ }
          }
        }
      }
    } catch (e) {
      if ((e as Error)?.name !== "AbortError") err = `Network error: ${(e as Error)?.message}`;
    }

    setMessages(
      [...history, { role: "assistant", content: acc, err: err || undefined }].map((m) =>
        m.role === "assistant" && !m.content && m.err ? { ...m } : m
      ) as Msg[]
    );
    abortRef.current = null;
    setStreaming(false);
  };

  return (
    <main className="flex h-dvh flex-col bg-zinc-950 text-zinc-200">
      <header className="border-b border-zinc-800 px-4 py-3">
        <p className="text-xs uppercase tracking-widest text-emerald-400">internal console</p>
        <h1 className="text-sm font-semibold text-white">
          API smoke-test console — not the product
        </h1>
      </header>

      <div className="flex-1 overflow-y-auto px-4 py-4">
        {messages.length === 0 && (
          <div className="mx-auto mt-10 max-w-md text-center">
            <p className="text-sm text-zinc-500">
              Minimal console to exercise the backend. The real product is the Android app.
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  onClick={() => send(s)}
                  className="rounded-full border border-zinc-800 bg-zinc-900 px-3 py-1.5 text-xs text-zinc-300 hover:bg-zinc-800"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`mb-4 flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
                m.role === "user"
                  ? "bg-emerald-600 text-white"
                  : "border border-zinc-800 bg-zinc-900 text-zinc-200"
              }`}
            >
              <p className="whitespace-pre-wrap">{m.content}</p>
              {m.role === "assistant" && streaming && i === messages.length - 1 && (
                <span className="ml-0.5 inline-block h-4 w-2 animate-pulse bg-emerald-400 align-text-bottom" />
              )}
              {m.err && <p className="mt-2 text-xs text-red-400">⚠ {m.err}</p>}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <div className="border-t border-zinc-800 px-4 py-3">
        <div className="mx-auto flex max-w-2xl items-end gap-2">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
            placeholder="Type a test message…"
            className="flex-1 rounded-xl border border-zinc-800 bg-zinc-900 px-4 py-2.5 text-sm text-zinc-100 outline-none focus:border-emerald-500/40"
          />
          {streaming ? (
            <button
              onClick={() => abortRef.current?.abort()}
              className="rounded-xl bg-zinc-800 px-4 py-2.5 text-sm text-white"
            >
              Stop
            </button>
          ) : (
            <button
              onClick={() => send()}
              disabled={!input.trim()}
              className="rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white disabled:opacity-30"
            >
              Send
            </button>
          )}
        </div>
      </div>
    </main>
  );
}
