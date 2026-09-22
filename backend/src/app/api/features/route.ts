import { NextRequest } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Remote feature flags for the Android APK.
 * IMPORTANT: these toggle capabilities the APK already ships with.
 * Unknown flags are ignored by the APK; missing flags fall back to defaults.
 */
export function GET(req: NextRequest) {
  const v = Number(req.nextUrl.searchParams.get("v") ?? "0"); // APK versionCode

  return Response.json({
    success: true,
    updated: "2026-09-22",
    features: {
      // Server relay chat (no user API key needed) — core of v3.3.0+
      server_relay: true,
      // Existing APK capabilities, remotely toggleable
      auto_reply: true, // accessibility-based auto replies
      bubble_overlay: true, // floating bubble quick chat
      quick_tile: true, // reply tile in quick settings
      streaming_ui: true, // typewriter rendering in the chat UI
      image_input: false, // GLM-4V ready but UI not shipped yet
      voice_input: false, // not shipped yet
      new_reply_modes: true, // tone/length modes selectable in-app
    },
    min_app_version: {
      server_relay: 13,
      new_reply_modes: 11,
      streaming_ui: 11,
      auto_reply: 9,
      bubble_overlay: 9,
      quick_tile: 9,
    },
    requested_version: Number.isFinite(v) ? v : 0,
  });
}
