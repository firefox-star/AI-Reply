import { NextRequest } from "next/server";
import { askAI, friendlyError, normalizeInput, rateLimited } from "@/lib/ai";

export const runtime = "nodejs";
export const maxDuration = 300;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * SSE companion endpoint (used by the built-in web console at /chat).
 * The primary product API is POST /api/chat (plain JSON).
 */
export async function POST(req: NextRequest) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "local";

  const encoder = new TextEncoder();
  const sseHeaders: HeadersInit = {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  };
  const emit = (controller: ReadableStreamDefaultController, event: string, data: unknown) => {
    controller.enqueue(
      encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    );
  };

  if (rateLimited(ip)) {
    return new Response(
      new ReadableStream({
        start(c) {
          emit(c, "error", { message: "Too many requests — slow down a little." });
          c.close();
        },
      }),
      { headers: sseHeaders }
    );
  }

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return new Response(
      new ReadableStream({
        start(c) {
          emit(c, "error", { message: "Body must be valid JSON." });
          c.close();
        },
      }),
      { headers: sseHeaders }
    );
  }

  const { messages, model, error } = normalizeInput(body);
  if (error || messages.length === 0) {
    return new Response(
      new ReadableStream({
        start(c) {
          emit(c, "error", { message: error ?? "Empty request." });
          c.close();
        },
      }),
      { headers: sseHeaders }
    );
  }

  const stream = new ReadableStream({
    async start(controller) {
      try {
        const reply = await askAI(messages, model);
        if (!reply) {
          emit(controller, "error", { message: "The AI returned an empty response. Try again." });
          controller.close();
          return;
        }
        // Re-emit the finished reply at a natural typing pace.
        const step = 24;
        for (let i = 0; i < reply.length; i += step) {
          emit(controller, "delta", { text: reply.slice(i, i + step) });
          await sleep(14);
        }
        emit(controller, "done", { ok: true });
        controller.close();
      } catch (e) {
        emit(controller, "error", { message: friendlyError(e) });
        controller.close();
      }
    },
  });

  return new Response(stream, { headers: sseHeaders });
}
