package com.aikeyboardmobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * JavaScript <-> native bridge for the chat UI.
 *
 * The published cloud page (and the bundled offline page) both talk to this
 * bridge when running inside the Android app:
 *   - chat/stopChat  : streaming chat completions through AiClient (no CORS, key stays native)
 *   - get/set key+URL: shared with the floating bubble (Prefs)
 *   - native actions : accessibility, battery, overlay/bubble, updates
 */
class ChatBridge(
    private val activity: Activity,
    private val jsSink: (String) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, AiClient.StreamSession>()

    private fun js(script: String) = main.post { jsSink(script) }

    private fun errScript(id: String, msg: String): String {
        val payload = JSONObject().put("m", msg).toString()
        return "window.__aiError(${JSONObject.quote(id)}, $payload)"
    }

    /** bodyJson = full request body {model, messages, baseUrl?, apiKey?, temperature?...}.
     *  No API key => server relay (POST {apiBase}/api/chat, server-side credentials).
     *  API key set => direct OpenAI-compatible stream to the user's own endpoint. */
    @JavascriptInterface
    fun chat(id: String, bodyJson: String) {
        try {
            val body = JSONObject(bodyJson)
            val cfg = Prefs.load(activity)
            val apiKey = (body.optString("apiKey", cfg.apiKey).ifBlank { cfg.apiKey }).trim()

            val onDelta: (String) -> Unit = { d ->
                val payload = JSONObject().put("d", d).toString()
                js("window.__aiChunk(${JSONObject.quote(id)}, $payload)")
            }
            val onDone: (Boolean) -> Unit = { aborted ->
                js("window.__aiDone(${JSONObject.quote(id)}, $aborted)")
                sessions.remove(id)
            }
            val onError: (String) -> Unit = { msg ->
                js(errScript(id, msg))
                sessions.remove(id)
            }

            if (apiKey.isBlank()) {
                val session = AiClient.relayStream(
                    id, AppUi.apiBase(activity), bodyJson,
                    onDelta = onDelta, onDone = onDone, onError = onError
                )
                sessions[id] = session
                return
            }

            val baseUrl = (body.optString("baseUrl", cfg.baseUrl).ifBlank { cfg.baseUrl })
            if (baseUrl.isBlank()) {
                js(errScript(id, "No AI endpoint set. Open Settings and add the API URL."))
                return
            }
            val session = AiClient.stream(
                id, baseUrl, apiKey, bodyJson,
                onDelta = onDelta, onDone = onDone, onError = onError
            )
            sessions[id] = session
        } catch (e: Exception) {
            js(errScript(id, "Could not start: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    @JavascriptInterface
    fun stopChat(id: String) {
        sessions[id]?.cancelled = true
    }

    // ---------- shared AI settings (bubble + chat use the same) ----------

    @JavascriptInterface
    fun getApiKey(): String = Prefs.load(activity).apiKey

    @JavascriptInterface
    fun setApiKey(key: String) {
        val cfg = Prefs.load(activity)
        Prefs.save(activity, cfg.copy(apiKey = key.trim()))
    }

    @JavascriptInterface
    fun getBaseUrl(): String = Prefs.load(activity).baseUrl

    @JavascriptInterface
    fun setBaseUrl(url: String) {
        val cfg = Prefs.load(activity)
        Prefs.save(activity, cfg.copy(baseUrl = url.trim()))
    }

    // ---------- app info & updates ----------

    @JavascriptInterface
    fun appVersion(): String = try {
        val pm = activity.packageManager.getPackageInfo(activity.packageName, 0)
        "${pm.versionName} (${pm.longVersionCode})"
    } catch (_: Exception) {
        "3.3.0"
    }

    @JavascriptInterface
    fun checkUpdate() {
        main.post { AppUi.checkForUpdate(activity, manual = true) }
    }

    @JavascriptInterface
    fun loadCloudApp() {
        main.post {
            // The web project is the backend now — open its console in the browser.
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.DEFAULT_API_BASE + "/chat"))
                )
            }
        }
    }

    // ---------- native system shortcuts ----------

    @JavascriptInterface
    fun isAccessibilityEnabled(): Boolean {
        val expected = "${activity.packageName}/${ChatAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        main.post {
            runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
        }
    }

    @JavascriptInterface
    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(activity)

    @JavascriptInterface
    fun openOverlaySettings() {
        main.post {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(i) }
            Toast.makeText(activity, "Find AI Reply in the list and switch it ON", Toast.LENGTH_LONG).show()
        }
    }

    @JavascriptInterface
    fun startBubble() {
        main.post {
            if (!Settings.canDrawOverlays(activity)) {
                openOverlaySettings()
                return@post
            }
            val i = Intent(activity, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW_PANEL)
            runCatching { ContextCompat.startForegroundService(activity, i) }
                .onFailure {
                    Toast.makeText(activity, "Bubble could not start: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    @JavascriptInterface
    fun openBatterySettings() {
        main.post {
            // Direct "allow ignore battery optimizations" dialog, with graceful fallbacks
            // for vendors (Infinix/Transsion) that reshuffle their battery screens.
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${activity.packageName}"))
            val ok = runCatching { activity.startActivity(direct) }.isSuccess
            if (!ok) {
                val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { activity.startActivity(list) }
                Toast.makeText(activity, "Find AI Reply and allow background / remove optimization", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun openAppInfo() {
        main.post {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                )
            }
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!url.startsWith("http")) return
        main.post {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        main.post { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }
}
