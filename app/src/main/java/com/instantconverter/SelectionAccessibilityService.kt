package com.instantconverter

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Watches text selection changes in other apps (browsers included). When the
 * selected text contains an amount in euros or dollars, the bubble expands
 * with the converted value — no extra tap needed.
 */
class SelectionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastText: String? = null
    private var lastTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Make sure the bubble is up as soon as detection is active.
        if (BubbleService.instance == null) BubbleService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        if (event.packageName == packageName) return

        val selected = extractSelection(event) ?: return
        if (selected.isBlank() || selected.length > 200) return
        if (AmountParser.parse(selected) == null) return

        // Selection handles fire many events while dragging: wait until it settles.
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val now = System.currentTimeMillis()
            if (selected == lastText && now - lastTime < 3000) return@Runnable
            lastText = selected
            lastTime = now
            BubbleService.convert(this, selected, explicit = false)
        }
        pending = r
        handler.postDelayed(r, 350)
    }

    private fun extractSelection(event: AccessibilityEvent): String? {
        val node = event.source
        try {
            val full: CharSequence? = node?.text ?: event.text.takeIf { it.isNotEmpty() }?.joinToString("")
            if (full.isNullOrEmpty()) return null
            var start = event.fromIndex
            var end = event.toIndex
            if ((start < 0 || end < 0 || start == end) && node != null) {
                start = node.textSelectionStart
                end = node.textSelectionEnd
            }
            if (start < 0 || end < 0 || start == end) return null
            val a = minOf(start, end).coerceIn(0, full.length)
            val b = maxOf(start, end).coerceIn(0, full.length)
            if (a == b) return null
            return full.subSequence(a, b).toString()
        } catch (e: Exception) {
            return null
        } finally {
            @Suppress("DEPRECATION")
            node?.recycle()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pending?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
