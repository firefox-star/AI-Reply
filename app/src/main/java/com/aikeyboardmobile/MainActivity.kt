package com.aikeyboardmobile

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The app shell. The Android app is the PRODUCT; the web project is its backend.
 *
 *  1. LOCAL UI (always) — the bundled chat interface in filesDir. Hot-updated
 *     over the air from the repo via AppUi, so UI improvements arrive without
 *     a new APK.
 *  2. SERVER RELAY — with no user API key, all AI goes through the public
 *     backend (POST {DEFAULT_API_BASE}/api/chat) using server-side
 *     credentials. Users may still plug their own key in Settings, in which
 *     case the app talks to their endpoint directly.
 *
 * The backend URL is OTA-configurable (version.json "apiBase"): if the server
 * moves, every installed phone follows WITHOUT an APK update.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var usingLocal = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0d0f14"))
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            isVerticalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    // Keep our own local UI inside; open everything else outside.
                    return if (url.startsWith("file:")) {
                        false
                    } else if (url.startsWith("http")) {
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                        true
                    } else {
                        true
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame && !usingLocal) loadLocal()
                }
            }
        }

        webView.addJavascriptInterface(
            ChatBridge(this) { script -> webView.evaluateJavascript(script, null) },
            "Android"
        )

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })

        if (!AppUi.ensureInstalled(this)) {
            android.widget.Toast.makeText(this, "Setup problem — reinstall the app", android.widget.Toast.LENGTH_LONG).show()
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadLocal()
        }

        // Silent hot-update check for the local UI + server address (applies + reloads when newer).
        AppUi.checkForUpdate(this, manual = false)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the overlay permission was granted while we were away, tell the page.
        evaluateJs("window.__appResume && window.__appResume()")
    }

    /** Kept for compatibility with OTA state from v3.2.x; the local UI is the app now. */
    fun switchToCloudIfLocal() { /* no-op since v3.3.0 */ }

    fun loadLocal() {
        usingLocal = true
        val f = AppUi.installedFile(this)
        webView.loadUrl("file://${f.absolutePath}")
    }

    fun reloadWebView() {
        runOnUiThread { webView.reload() }
    }

    fun evaluateJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        /**
         * The public backend (baked as a BOOTSTRAP; the live value is OTA-delivered
         * via version.json "apiBase" — see AppUi.apiBase).
         */
        const val DEFAULT_API_BASE = "https://preview-chat-7e3581ef-b06f-4ba0-be81-e5559866c627.space-z.ai"
    }
}
