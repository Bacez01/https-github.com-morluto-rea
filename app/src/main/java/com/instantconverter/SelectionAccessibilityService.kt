package com.instantconverter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Makes the bubble react as soon as an amount is selected.
 *
 * 1. Text fields: Android reports the selection directly
 *    (TYPE_VIEW_TEXT_SELECTION_CHANGED), the selected text is read from it.
 * 2. Web pages: browsers don't report selections of ordinary page text. Instead,
 *    when the "Copy / Share / Select all" toolbar pops up in a browser, the
 *    service presses "Copy" itself and the bubble converts the copied text
 *    ("auto-copy", can be turned off in the app).
 */
class SelectionAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS = "settings"
        const val KEY_AUTO_COPY = "auto_copy"

        fun autoCopyEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_COPY, true)

        /** Browsers whose selection toolbar is watched for auto-copy. */
        val BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.google.android.apps.chrome", "org.chromium.chrome",
            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
            "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
            "com.brave.browser", "com.vivaldi.browser", "com.duckduckgo.mobile.android",
            "com.mi.globalbrowser", "com.android.browser", "com.heytap.browser",
            "com.huawei.browser", "com.yandex.browser", "com.kiwibrowser.browser",
            "com.google.android.googlequicksearchbox",
        )

        private val COPY_LABELS = setOf(
            "copier", "copy", "copiar", "kopieren", "copia", "kopiëren", "kopiuj", "kopyala",
            "копировать", "salin", "nakili", "sao chép", "复制", "複製", "コピー", "복사", "نسخ",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelection: Runnable? = null
    private var lastText: String? = null
    private var lastTime = 0L
    private var lastAutoCopy = 0L
    private var toolbarVisible = false

    private val scanToolbar = Runnable { checkSelectionToolbar() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Make sure the bubble is up as soon as detection is active.
        if (BubbleService.instance == null) BubbleService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg != null && pkg !in BROWSERS && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
                if (!autoCopyEnabled(this)) return
                // Many events arrive while the page moves: look once things settle.
                handler.removeCallbacks(scanToolbar)
                handler.postDelayed(scanToolbar, 180)
            }
        }
    }

    // ------------------------------------------------------------ text fields

    private fun onSelectionChanged(event: AccessibilityEvent) {
        val selected = extractSelection(event) ?: return
        if (selected.isBlank() || selected.length > 200) return
        if (AmountParser.parse(selected) == null) return

        // Selection handles fire many events while dragging: wait until it settles.
        pendingSelection?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val now = System.currentTimeMillis()
            if (selected == lastText && now - lastTime < 3000) return@Runnable
            lastText = selected
            lastTime = now
            BubbleService.convert(this, selected, explicit = false)
        }
        pendingSelection = r
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
        }
    }

    // ------------------------------------------------------------ web pages (auto-copy)

    private fun checkSelectionToolbar() {
        val copy = findCopyButton()
        if (copy == null) {
            toolbarVisible = false
            return
        }
        // Press "Copy" once per appearance of the toolbar.
        if (toolbarVisible) return
        val now = System.currentTimeMillis()
        if (now - lastAutoCopy < 1200) return
        toolbarVisible = true
        lastAutoCopy = now
        if (!copy.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        handler.postDelayed({
            val bubble = BubbleService.instance
            if (bubble != null) bubble.convertClipboard() else BubbleService.start(this)
        }, 250)
    }

    /** Looks for the "Copy" button of a text selection toolbar shown by a browser. */
    private fun findCopyButton(): AccessibilityNodeInfo? {
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        val list = try {
            windows
        } catch (e: Exception) {
            return null
        }
        for (w in list) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            ) continue
            w.getBoundsInScreen(bounds)
            // The toolbar is a small popup, not the page itself.
            if (bounds.height() <= 0 || bounds.height() > screenHeight / 2) continue
            val root = w.root ?: continue
            if (root.packageName?.toString() !in BROWSERS) continue
            findCopyIn(root, depth = 0, budget = intArrayOf(80))?.let { return it }
        }
        return null
    }

    private fun findCopyIn(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): AccessibilityNodeInfo? {
        if (depth > 8 || budget[0]-- <= 0) return null
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase()
        if (label != null && label in COPY_LABELS && node.isEnabled) {
            val clickable = if (node.isClickable) node else node.parent?.takeIf { it.isClickable }
            val siblings = clickable?.parent?.childCount ?: 0
            // A real selection toolbar holds several actions next to "Copy".
            if (clickable != null && siblings >= 2) return clickable
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findCopyIn(child, depth + 1, budget)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pendingSelection?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(scanToolbar)
        super.onDestroy()
    }
}
