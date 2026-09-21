package com.aikeyboardmobile

/**
 * Free AI provider presets. The first entry (Z.ai GLM) is the default because its
 * GLM-4.5-Flash model is 100% free and worked reliably in testing.
 */
data class Preset(
    val name: String,
    val url: String,
    val model: String,
    val keyUrl: String,
    val hint: String
)

object Presets {
    val all: List<Preset> = listOf(
        Preset(
            "Z.ai GLM ✓",
            "https://api.z.ai/api/paas/v4",
            "glm-4.5-flash",
            "https://z.ai/manage-apikey/apikey-list",
            "RECOMMENDED — GLM-4.5-Flash is 100% free, no credit card. Get your free key at z.ai (API Keys). Free models: glm-4.5-flash, glm-4.7-flash. Tap here to get your key."
        ),
        Preset(
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            "z-ai/glm-5.2:free",
            "https://openrouter.ai/keys",
            "One key, many free models (any id ending in :free). Get a free key at openrouter.ai/keys. Tap here."
        ),
        Preset(
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash-lite",
            "https://aistudio.google.com/apikey",
            "Google's free tier via the OpenAI-compatible endpoint. Get a free key at aistudio.google.com. Tap here."
        ),
        Preset(
            "Mistral",
            "https://api.mistral.ai/v1",
            "mistral-small-latest",
            "https://console.mistral.ai/api-keys",
            "Free tier available. Get a free key at console.mistral.ai. Tap here."
        ),
        Preset(
            "Groq",
            "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile",
            "https://console.groq.com/keys",
            "Very fast free tier. Get a free key at console.groq.com. Tap here."
        ),
        Preset(
            "Cerebras",
            "https://api.cerebras.ai/v1",
            "llama3.1-8b",
            "https://cloud.cerebras.ai",
            "Free tier with generous daily limits. Get a free key at cloud.cerebras.ai. Tap here."
        )
    )
}
