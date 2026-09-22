import { NextRequest } from "next/server";
import { askAI, friendlyError, normalizeInput, rateLimited } from "@/lib/ai";

export const runtime = "nodejs";
export const maxDuration = 300;

/**
 * PRIMARY PRODUCT API — called by the Android APK.
 *
 * Request:  { "message": "Hello", "conversation": [{"role":"user","content":"..."}, ...],
 *             "model": "glm-4.5-flash" (optional), "system": "..." (optional) }
 * Response: { "success": true, "reply": "Hello! How can I help you?" }
 * Errors:   { "success": false, "error": "human readable reason" }
 */
export async function POST(req: NextRequest) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "local";
  if (rateLimited(ip)) {
    return Response.json(
      { success: false, error: "Too many requests — slow down a little." },
      { status: 429 }
    );
  }

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return Response.json({ success: false, error: "Body must be valid JSON." }, { status: 400 });
  }

  const { messages, model, error } = normalizeInput(body);
  if (error || messages.length === 0) {
    return Response.json({ success: false, error: error ?? "Empty request." }, { status: 400 });
  }

  try {
    const reply = await askAI(messages, model);
    if (!reply) {
      return Response.json(
        { success: false, error: "The AI returned an empty response. Please retry." },
        { status: 502 }
      );
    }
    return Response.json({ success: true, reply, model });
  } catch (e) {
    return Response.json({ success: false, error: friendlyError(e) }, { status: 502 });
  }
}

export function GET() {
  return Response.json(
    {
      success: false,
      error: "POST JSON here: {\"message\":\"Hello\",\"conversation\":[]}",
    },
    { status: 405 }
  );
}
