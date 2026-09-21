package com.aikeyboardmobile

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The app shell. The whole chat experience is a web app:
 *
 *  1. CLOUD UI (default) — the published AI Reply Chat site. Every improvement
 *     I deploy there reaches this app instantly, no APK download.
 *  2. OFFLINE UI — a bundled copy in filesDir (OTA-updated from the repo via
 *     AppUi). Shown automatically when the cloud site can't be reached, and it
 *     talks to the AI through the native bridge (ChatBridge).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var usingLocal = false

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
                    // Keep our own origins inside; open everything else outside.
                    return if (url.startsWith(REMOTE_UI_URL) || url.startsWith("file:")) {
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

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
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
            loadRemote()
        }

        // Silent hot-update check for the offline UI (applies + reloads when newer).
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

    fun loadRemote() {
        usingLocal = false
        webView.loadUrl(REMOTE_UI_URL)
    }

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
        /** The published cloud chat (updated automatically when I deploy improvements). */
        const val REMOTE_UI_URL = "https://preview-27ef61e4-a38f-4789-a20a-2561ff7a2b30.space-z.ai/"
    }
}
