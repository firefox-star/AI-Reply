export const dynamic = "force-dynamic";

const PUBLIC_API = "https://preview-chat-7e3581ef-b06f-4ba0-be81-e5559866c627.space-z.ai";

async function probe(path: string): Promise<{ ok: boolean; ms: number }> {
  const t0 = Date.now();
  try {
    const res = await fetch(`${PUBLIC_API}${path}`, { cache: "no-store" });
    return { ok: res.ok, ms: Date.now() - t0 };
  } catch {
    return { ok: false, ms: Date.now() - t0 };
  }
}

export default async function StatusPage() {
  const cfg = await probe("/api/config");
  const feat = await probe("/api/features");

  return (
    <main className="min-h-screen bg-zinc-950 px-5 py-10 text-zinc-200">
      <div className="mx-auto max-w-2xl">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-emerald-400">
          AI Reply · Backend service
        </p>
        <h1 className="mt-2 text-2xl font-bold text-white">Public API for the Android app</h1>
        <p className="mt-2 text-sm leading-relaxed text-zinc-400">
          This service is the server-side brain of the <b>AI Reply</b> Android app. It hosts no
          chat UI on purpose — the APK is the product; this is the relay that talks to the AI
          provider with server-side credentials. No private keys are ever shipped inside the app.
        </p>

        <div className="mt-6 grid gap-3 sm:grid-cols-2">
          <div className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
            <p className="text-sm font-semibold text-white">POST /api/chat</p>
            <p className="mt-1 text-xs text-zinc-400">Send a message, get the AI reply as JSON.</p>
          </div>
          <div className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
            <p className="text-sm font-semibold text-white">GET /api/config</p>
            <p className="mt-1 text-xs text-zinc-400">
              Remote configuration · {cfg.ok ? `up (${cfg.ms}ms)` : "checking…"}
            </p>
          </div>
          <div className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
            <p className="text-sm font-semibold text-white">GET /api/features</p>
            <p className="mt-1 text-xs text-zinc-400">
              Remote feature flags · {feat.ok ? `up (${feat.ms}ms)` : "checking…"}
            </p>
          </div>
          <div className="rounded-xl border border-zinc-800 bg-zinc-900 p-4">
            <p className="text-sm font-semibold text-white">POST /api/chat/stream</p>
            <p className="mt-1 text-xs text-zinc-400">SSE variant used by the internal console.</p>
          </div>
        </div>

        <h2 className="mt-8 text-sm font-semibold uppercase tracking-wider text-zinc-400">
          Quick example
        </h2>
        <pre className="mt-2 overflow-x-auto rounded-xl border border-zinc-800 bg-black p-4 text-[12.5px] leading-relaxed text-zinc-300">
{`curl -X POST ${PUBLIC_API}/api/chat \\
  -H "Content-Type: application/json" \\
  -d '{"message":"Hello","conversation":[]}'`}
        </pre>
        <pre className="mt-3 overflow-x-auto rounded-xl border border-zinc-800 bg-black p-4 text-[12.5px] leading-relaxed text-emerald-300">
{`{ "success": true,
  "reply": "Hello! How can I help you?",
  "model": "glm-4.5-flash" }`}
        </pre>

        <p className="mt-8 text-xs text-zinc-500">
          AI Reply backend · v1 · free GLM models only · AI credentials stay server-side
        </p>
      </div>
    </main>
  );
}
