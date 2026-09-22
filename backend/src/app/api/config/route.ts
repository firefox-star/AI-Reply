import { ALLOWED_MODELS, DEFAULT_MODEL } from "@/lib/ai";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Remote configuration for the Android APK.
 * The APK applies what it already supports; unknown keys are ignored safely.
 */
export function GET() {
  return Response.json({
    success: true,
    updated: "2026-09-22",
    config: {
      default_model: DEFAULT_MODEL,
      models: ALLOWED_MODELS,
      min_app_version: 13,
      latest_version: "3.3.0",
      api_version: 1,
      defaults: {
        backend: "server", // "server" = built-in relay, "custom" = user's own key
        max_context_messages: 30,
        request_timeout_seconds: 120,
      },
      messages: {
        relay_notice: "AI powered by the AI Reply server — no API key required.",
      },
    },
  });
}
