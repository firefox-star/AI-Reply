package com.aikeyboardmobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton

/**
 * Native floating bubble (no React Native, no web views):
 *  - a small draggable ball that floats over any app;
 *  - tapping it opens a compact reply panel;
 *  - the panel asks for ONE tone and returns exactly ONE reply (fixes the
 *    "tap Playful, get all 4" bug from the old build);
 *  - works entirely with the normal "Display over other apps" permission.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_SHOW_PANEL = "com.aikeyboardmobile.SHOW_PANEL"
        @Volatile
        var panelOpen: Boolean = false
    }

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "AI Reply needs “Display over other apps” — open the app to allow it.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        mainHandler.post { showBubble() }
        if (intent?.action == ACTION_SHOW_PANEL) {
            mainHandler.postDelayed({ openPanel() }, 120)
        }
        return START_STICKY
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showBubble() {
        if (bubbleView != null) return
        val v = View.inflate(this, R.layout.bubble_view, null)
        val density = resources.displayMetrics.density
        val size = (54 * density).toInt()
        val maxW = resources.displayMetrics.widthPixels
        val maxH = resources.displayMetrics.heightPixels

        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (18 * density).toInt()
            y = (maxH / 3)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy > 500) moved = true
                    if (moved) {
                        p.x = (startX + dx).coerceIn(0, maxW - size)
                        p.y = (startY + dy).coerceIn(0, maxH - size)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(v, p) }
        bubbleView = v
    }

    private fun togglePanel() {
        if (panelView == null) openPanel() else closePanel()
    }

    @SuppressLint("InflateParams")
    private fun openPanel() {
        if (panelView != null) return
        val v = View.inflate(this, R.layout.panel_bubble, null)
        val density = resources.displayMetrics.density
        val maxW = resources.displayMetrics.widthPixels
        val w = minOf((maxW * 0.94f).toInt(), (420 * density).toInt())

        val p = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (28 * density).toInt()
        }

        val etMsg = v.findViewById<EditText>(R.id.etPanelMsg)
        val status = v.findViewById<TextView>(R.id.tvPanelStatus)
        val boxResult = v.findViewById<View>(R.id.boxPanelResult)
        val tvResult = v.findViewById<TextView>(R.id.tvPanelResult)
        val btnCopy = v.findViewById<MaterialButton>(R.id.btnPanelCopy)
        val colorSub = resources.getColor(R.color.textSub, theme)
        val colorGreen = resources.getColor(R.color.accentGreen, theme)
        val colorErr = resources.getColor(R.color.errorRed, theme)

        // Pre-fill from the optional accessibility service (best effort)
        if (ChatAccessibilityService.lastText.isNotBlank()) {
            etMsg.setText(ChatAccessibilityService.lastText)
            status.text = "Message auto-filled — pick ONE tone."
        }

        val toneButtons: List<Pair<MaterialButton, Tone>> = listOf(
            v.findViewById<MaterialButton>(R.id.btnPanelFriendly) to Tone.FRIENDLY,
            v.findViewById<MaterialButton>(R.id.btnPanelProfessional) to Tone.PROFESSIONAL,
            v.findViewById<MaterialButton>(R.id.btnPanelShort) to Tone.SHORT,
            v.findViewById<MaterialButton>(R.id.btnPanelPlayful) to Tone.PLAYFUL
        )

        fun setBusy(busy: Boolean) {
            toneButtons.forEach { (b, _) -> b.isEnabled = !busy }
        }

        toneButtons.forEach { (btn, tone) ->
            btn.setOnClickListener {
                val cfg = Prefs.load(this)
                val msg = etMsg.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) {
                    status.setTextColor(colorErr)
                    status.text = "Type or paste the message you received first."
                    return@setOnClickListener
                }
                if (cfg.apiKey.isBlank()) {
                    status.setTextColor(colorErr)
                    status.text = "Open AI Reply and paste your free API key (step 1) first."
                    return@setOnClickListener
                }
                status.setTextColor(colorSub)
                status.text = "Thinking…"
                boxResult.visibility = View.GONE
                setBusy(true)
                Thread {
                    AiClient.generate(cfg, tone, msg) { r ->
                        mainHandler.post {
                            setBusy(false)
                            when (r) {
                                is AiClient.Ok -> {
                                    status.setTextColor(colorGreen)
                                    status.text = "${tone.title} reply ready ✓ — tap Copy, then paste in your chat."
                                    tvResult.text = r.text
                                    boxResult.visibility = View.VISIBLE
                                }
                                is AiClient.Err -> {
                                    status.setTextColor(colorErr)
                                    status.text = r.msg
                                }
                            }
                        }
                    }
                }.start()
            }
        }

        btnCopy.setOnClickListener {
            val t = tvResult.text?.toString().orEmpty()
            if (t.isNotBlank()) copy(t)
        }

        v.findViewById<View>(R.id.btnPanelClose).setOnClickListener { closePanel() }

        runCatching { wm.addView(v, p) }
        panelView = v
        panelOpen = true
    }

    private fun closePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelOpen = false
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Reply", text))
        Toast.makeText(this, "Copied ✓ — now paste it in your chat", Toast.LENGTH_SHORT).show()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("bubble", "AI Reply bubble", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val b = NotificationCompat.Builder(this, "bubble")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AI Reply bubble is on")
            .setContentText("Drag the ball anywhere. Tap it to write a reply.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return b.build()
    }

    private fun startForegroundNow() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        closePanel()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        super.onDestroy()
    }
}
