package com.aikeyboardmobile

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * OPTIONAL service: when enabled, it watches for the text the user is currently
 * typing/reading in other apps so the reply panel can pre-fill it.
 *
 * The app is fully usable WITHOUT this service (the user can paste the message
 * into the panel themselves), so being blocked by Android 13+ "Restricted setting"
 * never blocks the core experience.
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var lastText: String = ""

        @Volatile
        var connected: Boolean = false
    }

    private var lastGrab = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val t = event.text?.joinToString(" ")?.trim()
                if (!t.isNullOrEmpty()) lastText = t
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> grabFocused()
        }
    }

    /** Best-effort: find the focused editable node and remember its text. */
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
    }
}
