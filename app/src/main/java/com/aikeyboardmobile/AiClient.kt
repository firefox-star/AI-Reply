package com.aikeyboardmobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reply tones. The user taps exactly ONE tone, so each request asks the model for
 * exactly ONE reply — this fixes the old bug where tapping one tone returned all four.
 */
enum class Tone(val title: String, val extra: String = "") {
    FRIENDLY("Friendly"),
    PROFESSIONAL("Professional"),
    SHORT("Short", " Keep the reply under 20 words."),
    PLAYFUL("Playful")
}

/**
 * Minimal OpenAI-compatible chat/completions client using HttpURLConnection —
 * zero external dependencies. Works with Z.ai, OpenRouter, Gemini's OpenAI-compat
 * endpoint, Mistral, Groq and Cerebras alike.
 */
object AiClient {

    sealed class Result
    data class Ok(val text: String) : Result()
    data class Err(val msg: String) : Result()

    fun generate(
        cfg: Prefs.Config,
        tone: Tone,
        message: String,
        cb: (Result) -> Unit,
        transcript: String = ""
    ) {
        try {
            val url = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"

            val system = "You are a helpful messaging assistant. The user received a message and " +
                    "wants to reply in one specific tone. Write exactly ONE reply in that tone. " +
                    "Output ONLY the reply text itself: no quotes, no labels, no explanation, no options. " +
                    "Keep it natural and under 60 words." + tone.extra

            val user = if (transcript.isNotBlank()) {
                "Tone: ${tone.title}\n\n" +
                        "Here is the recent chat so far (\"Them:\" = the other person, \"Me:\" = the user):\n" +
                        "-----\n$transcript\n-----\n\n" +
                        "The user wants to reply to the latest part of this conversation. " +
                        "Message being replied to: $message\n\n" +
                        "Write the user's reply in the tone above, consistent with the conversation."
            } else {
                "Tone: ${tone.title}\n\nMessage I received: $message\n\nWrite my reply in the tone above."
            }

            val root = JSONObject()
            root.put("model", cfg.model.trim())
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", system))
            msgs.put(JSONObject().put("role", "user").put("content", user))
            root.put("messages", msgs)
            root.put("temperature", 0.8)
            root.put("max_tokens", 300)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (cfg.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
                }
            }

            conn.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            try { conn.disconnect() } catch (_: Exception) {}

            if (code !in 200..299) {
                cb(Err(humanError(code, body)))
                return
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            val message0 = choices?.optJSONObject(0)?.optJSONObject("message")
            var content = message0?.optString("content").orEmpty()
            if (content.isBlank()) {
                // Some GLM deployments put text in reasoning_content as a fallback
                content = message0?.optString("reasoning_content").orEmpty()
            }
            content = clean(content)

            if (content.isBlank()) {
                cb(Err("The AI returned an empty reply. Try again, or check the model name (e.g. glm-4.5-flash)."))
            } else {
                cb(Ok(content))
            }
        } catch (e: Exception) {
            cb(Err("Network problem: ${e.message ?: e.javaClass.simpleName}. Check your internet and the Base URL."))
        }
    }

    /**
     * Streaming chat completion for the chat UI (SSE, OpenAI-compatible).
     * Emits deltas as they arrive; falls back to one big delta when the
     * server ignores "stream": true. Returns a session handle that can cancel.
     */
    class StreamSession(val id: String) {
        @Volatile
        var cancelled = false
        internal var conn: HttpURLConnection? = null
    }

    fun stream(
        id: String,
        baseUrl: String,
        apiKey: String,
        bodyJson: String,
        onDelta: (String) -> Unit,
        onDone: (aborted: Boolean) -> Unit,
        onError: (String) -> Unit
    ): StreamSession {
        val session = StreamSession(id)
        Thread {
            var abortedClean = false
            try {
                val root = JSONObject(bodyJson)
                root.put("stream", true)
                if (!root.has("model") || root.optString("model").isBlank()) {
                    root.put("model", "glm-4.5-flash")
                }
                val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    }
                }
                session.conn = conn
                conn.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode

                if (code !in 200..299) {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    onError(humanError(code, body))
                    return@Thread
                }

                val contentType = conn.contentType ?: ""
                val isSse = contentType.contains("event-stream", ignoreCase = true)
                if (!isSse) {
                    // Server ignored stream:true — respond like the one-shot path.
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (session.cancelled) {
                        onDone(true)
                        return@Thread
                    }
                    val json = JSONObject(body)
                    val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    var text = msg?.optString("content").orEmpty()
                    if (text.isBlank()) text = msg?.optString("reasoning_content").orEmpty()
                    if (text.isBlank()) {
                        onError("The AI returned an empty reply. Check the model name (e.g. glm-4.5-flash).")
                    } else {
                        onDelta(text)
                        onDone(false)
                    }
                    return@Thread
                }

                val reader = conn.inputStream.bufferedReader()
                val full = StringBuilder()
                while (true) {
                    if (session.cancelled) {
                        abortedClean = true
                        break
                    }
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val delta = try {
                        val j = JSONObject(payload)
                        j.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                }
                when {
                    abortedClean -> onDone(true)
                    full.isEmpty() -> onError("The AI returned an empty reply. The free tier may be busy — try again shortly.")
                    else -> onDone(false)
                }
            } catch (e: Exception) {
                if (session.cancelled) {
                    onDone(true)
                } else {
                    onError("Network problem: ${e.message ?: e.javaClass.simpleName}. Check your internet and the Base URL.")
                }
            } finally {
                try { session.conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
        return session
    }

    /** Tolerant cleanup: strips code fences, leading labels and wrapping quotes. */
    private fun clean(raw: String): String {
        var s = raw.trim()
        if (s.contains("```")) {
            val a = s.indexOf("```")
            val b = s.lastIndexOf("```")
            if (b > a) s = s.substring(a + 3, b).trim()
            s = s.removePrefix("json").trim()
        }
        s = s.replace(Regex("^(reply|suggestion|assistant)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        s = s.trim()
        if (s.length >= 2) {
            val f = s.first()
            val l = s.last()
            if ((f == '"' && l == '"') || (f == '\u201C' && l == '\u201D')) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    /** Turn HTTP errors into plain, helpful language. */
    private fun humanError(code: Int, body: String): String {
        var detail = ""
        try {
            val j = JSONObject(body)
            val err = j.optJSONObject("error")
            detail = err?.optString("message").orEmpty()
            if (detail.isBlank() && j.has("message")) detail = j.optString("message")
        } catch (_: Exception) {
        }
        val base = when (code) {
            401, 403 -> "API key missing or wrong (HTTP $code). Paste a valid free key in step 1."
            429 -> "Rate limit reached (HTTP 429). Wait about a minute and try again — free tiers get busy."
            404 -> "Model or URL not found (HTTP 404). Check the model name, e.g. glm-4.5-flash."
            400 -> "Bad request (HTTP 400). Check the model name and Base URL."
            else -> "The AI server answered with HTTP $code."
        }
        return if (detail.isBlank()) base else "$base\n$detail"
    }
}
