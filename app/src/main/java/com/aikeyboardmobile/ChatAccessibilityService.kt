package com.aikeyboardmobile

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * OPTIONAL service — but when enabled it does the magic:
 *
 * 1. grabs the text you are reading/typing (legacy behaviour);
 * 2. reads the WHOLE visible chat — messages you sent AND messages you got —
 *    and keeps an up-to-date transcript;
 * 3. when the reply panel asks, it can scroll the chat UP a few pages and
 *    fold older history into the transcript too.
 *
 * Everything stays on the phone. The transcript is only used to build better
 * AI replies (Bubble panel) and is never uploaded anywhere by this service.
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var lastText: String = ""

        @Volatile
        var connected: Boolean = false

        /** "Them: … / Me: …" transcript of the current chat, oldest first. */
        @Volatile
        var lastTranscript: String = ""

        @Volatile
        var transcriptMessages: Int = 0

        /** Package the transcript was captured from. */
        @Volatile
        var transcriptPackage: String = ""

        @Volatile
        private var instance: ChatAccessibilityService? = null

        private val CHAT_PACKAGES = setOf(
            "com.whatsapp", "com.gbwhatsapp", "com.whatsapp.w4b", "com.gbwhatsapp2",
            "org.telegram.messenger", "com.facebook.orca", "com.instagram.android",
            "com.linkedin.android", "com.Slack"
        )

        /** Ask the service to capture the chat now (optionally scrolling up for history). */
        fun requestCapture(withHistory: Boolean) {
            instance?.captureAsync(withHistory)
        }

        /** Best-effort transcript for the AI prompt (empty when service is off). */
        fun chatContext(): String = lastTranscript
    }

    private val bg = Handler(Looper.getMainLooper())
    private var lastPassive = 0L
    private var capturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val t = event.text?.joinToString(" ")?.trim()
                if (!t.isNullOrEmpty()) lastText = t
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                grabFocused()
                val pkg = event.packageName?.toString() ?: return
                if (pkg in CHAT_PACKAGES) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPassive > 1200 && !capturing) {
                        lastPassive = now
                        captureAsync(withHistory = false)
                    }
                }
            }
        }
    }

    /** Capture on a worker thread; optionally scroll up to fold in history. */
    private fun captureAsync(withHistory: Boolean) {
        if (capturing) return
        capturing = true
        Thread {
            try {
                val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
                if (pkg !in CHAT_PACKAGES) return@Thread

                if (withHistory) {
                    // newest page first, then scroll upward collecting older pages
                    val pages = ArrayList<List<Line>>(3)
                    var root = rootInActiveWindow ?: return@Thread
                    var collected = collectLines(root, pkg)
                    if (collected.isNotEmpty()) pages.add(collected)

                    var rounds = 0
                    while (rounds < 4) {
                        if (Thread.currentThread().isInterrupted) break
                        root = rootInActiveWindow ?: break
                        val scroller = findScrollable(root) ?: break
                        val ok = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        if (!ok) break
                        Thread.sleep(450)
                        root = rootInActiveWindow ?: break
                        val older = collectLines(root, pkg)
                        if (older.isEmpty()) break
                        // stop when a page adds nothing new at all
                        val known = pages.flatten().map { it.key }.toSet()
                        if (older.all { it.key in known }) break
                        pages.add(older)
                        rounds++
                    }

                    // Merge: oldest page first, top→bottom inside a page = chronological
                    val merged = LinkedHashMap<String, Line>()
                    pages.reversed().forEach { page ->
                        page.forEach { line -> merged.putIfAbsent(line.key, line) }
                    }
                    val lines = merged.values.toList()
                    transcriptPackage = pkg
                    transcriptMessages = lines.size
                    lastTranscript = lines.joinToString("\n") { it.render() }
                } else {
                    val root = rootInActiveWindow ?: return@Thread
                    val lines = collectLines(root, pkg)
                    if (lines.isNotEmpty()) {
                        // Passive captures never shrink an existing richer transcript
                        if (lines.size >= transcriptMessages || lastTranscript.isBlank() ||
                            transcriptPackage != pkg
                        ) {
                            transcriptPackage = pkg
                            transcriptMessages = lines.size
                            lastTranscript = lines.joinToString("\n") { it.render() }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                capturing = false
            }
        }.start()
    }

    private data class Line(val them: Boolean, val text: String, val key: String) {
        fun render() = (if (them) "Them: " else "Me: ") + text
    }

    /** Walk the visible tree and pull chat lines out of it. */
    private fun collectLines(root: AccessibilityNodeInfo, pkg: String): List<Line> {
        val rect = android.graphics.Rect()
        try { root.getBoundsInScreen(rect) } catch (_: Exception) {}
        val width = if (rect.width() > 0) rect.width() else 1080
        val out = ArrayList<Line>()
        val seenKeys = HashSet<String>()
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, 0))
        var visited = 0
        var lastRender = ""

        while (queue.isNotEmpty() && visited < 450) {
            val cur = queue.removeFirst()
            visited++
            val n = cur.node
            val id = n.viewIdResourceName?.lowercase().orEmpty()

            // Descend (depth-capped)
            if (cur.depth < 26) {
                for (i in 0 until n.childCount) {
                    try { n.getChild(i)?.let { queue.add(Node(it, cur.depth + 1)) } } catch (_: Exception) {}
                }
            }

            val dirHint = when {
                id.contains("outgoing") -> false
                id.contains("incoming") -> true
                else -> null
            }

            val txt = n.text?.toString()?.trim() ?: continue
            if (txt.isEmpty() || txt == lastRender) continue
            if (isNoise(txt, id)) continue

            val them = dirHint ?: guessSide(n, width)
            val key = (if (them) "T" else "M") + "\u0000" + txt
            if (seenKeys.add(key)) {
                out.add(Line(them, txt, key))
                lastRender = txt
            }
        }
        return out
    }

    private class Node(val node: AccessibilityNodeInfo, val depth: Int)

    /** Direction fallback: WhatsApp/Telegram align bubbles left (them) / right (me). */
    private fun guessSide(n: AccessibilityNodeInfo, width: Int): Boolean {
        return try {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            val center = r.centerX()
            center < width / 2
        } catch (_: Exception) {
            true
        }
    }

    private fun isNoise(text: String, id: String): Boolean {
        val t = text.trim()
        if (t.length > 700) return true // giant blocks (quotes, forwarded walls) still allowed? cap huge ones
        if (t.matches(Regex("[0-9:./ -]{1,14}"))) return true // times / dates / counts
        if (t.equals("today", true) || t.equals("yesterday", true)) return true
        if (id.contains("date") || id.contains("time") || id.contains("status") ||
            id.contains("timestamp") || id.contains("divider") || id.contains("conversation_title")
        ) return true
        if (t == "•" || t == "…" || t == "Typing…" || t == "typing…") return true
        if (t.startsWith("http") && t.length < 24) return true
        return false
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst()
            seen++
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) {}
            }
        }
        return null
    }

    /** Best-effort: find the focused editable node and remember its text. */
    private var lastGrab = 0L
    private fun grabFocused() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGrab < 400) return
        lastGrab = now
        try {
            val root = rootInActiveWindow ?: return
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < 80) {
                val n = queue.removeFirst()
                seen++
                if (n.isEditable && n.isFocused) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrEmpty()) {
                        lastText = t
                        return
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        instance = null
    }
}
