package com.aikeyboardmobile

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hot-update system for the chat UI.
 *
 * The Android shell is just a WebView. The actual chat interface lives in ONE
 * html file. At startup (and on manual check) we poll a tiny version.json in
 * the GitHub repo; if a newer UI exists we download it, swap it in atomically
 * and reload. New UI reaches every installed phone WITHOUT a new APK.
 *
 * Local (bundled) copy is always present as offline fallback.
 */
object AppUi {

    const val REMOTE_BASE = "https://raw.githubusercontent.com/firefox-star/AI-Reply/main/appui"
    private const val VERSION_URL = "$REMOTE_BASE/version.json"
    private const val FILE_URL = "$REMOTE_BASE/index.html"

    private const val PREFS = "appui"
    private val main = Handler(Looper.getMainLooper())

    fun installedFile(ctx: Context): File = File(ctx.filesDir, "appui/index.html")

    fun installedVersion(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("version", 0)

    /** Make sure a local UI exists (first run: copy the bundled one). */
    fun ensureInstalled(ctx: Context): Boolean {
        val f = installedFile(ctx)
        if (f.exists() && installedVersion(ctx) > 0) return true
        return copyFromAssets(ctx)
    }

    private fun copyFromAssets(ctx: Context): Boolean = try {
        val assetsVersion = ctx.assets.open("appui/version.json").bufferedReader().use {
            JSONObject(it.readText()).optInt("version", 1)
        }
        val out = installedFile(ctx)
        out.parentFile?.mkdirs()
        ctx.assets.open("appui/index.html").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("version", assetsVersion).apply()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Check the repo for a newer UI. Silent in the background; shows toasts
     * when manual. On success the WebView reloads with the new file.
     */
    fun checkForUpdate(ctx: Context, manual: Boolean, onResult: ((String) -> Unit)? = null) {
        Thread {
            var result = "latest"
            try {
                val conn = URL("$VERSION_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val vText = conn.inputStream.bufferedReader().use { it.readText() }
                try { conn.disconnect() } catch (_: Exception) {}
                val remoteVersion = JSONObject(vText).optInt("version", 0)
                val current = installedVersion(ctx)

                if (remoteVersion > current) {
                    val dir = File(ctx.filesDir, "appui")
                    dir.mkdirs()
                    val tmp = File(dir, "index.html.tmp")
                    val dl = URL("$FILE_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    dl.connectTimeout = 15000
                    dl.readTimeout = 30000
                    dl.inputStream.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    try { dl.disconnect() } catch (_: Exception) {}

                    // sanity check: a real UI file is never tiny
                    if (tmp.length() > 5000) {
                        val dst = installedFile(ctx)
                        if (dst.exists()) dst.delete()
                        if (tmp.renameTo(dst)) {
                            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putInt("version", remoteVersion).apply()
                            result = "updated:$remoteVersion"
                        } else {
                            tmp.delete()
                            result = "error"
                        }
                    } else {
                        tmp.delete()
                        result = "error"
                    }
                }
            } catch (_: Exception) {
                result = if (manual) "error" else "offline"
            }

            main.post {
                when {
                    result.startsWith("updated:") -> {
                        Toast.makeText(ctx, "Chat updated to build ${result.substringAfter(':')} — refreshing", Toast.LENGTH_SHORT).show()
                        (ctx as? MainActivity)?.reloadWebView()
                    }
                    result == "error" && manual ->
                        Toast.makeText(ctx, "Update check failed — check internet and try again", Toast.LENGTH_SHORT).show()
                    result == "latest" && manual ->
                        Toast.makeText(ctx, "You are on the latest chat build", Toast.LENGTH_SHORT).show()
                }
                onResult?.invoke(result)
            }
        }.start()
    }
}
