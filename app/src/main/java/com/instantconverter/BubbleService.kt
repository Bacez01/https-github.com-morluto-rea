package com.instantconverter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Foreground service that draws the floating bubble over every app.
 *
 * - Collapsed: a small round bubble parked in a corner of the screen; it can be
 *   dragged and snaps to the nearest corner when released.
 * - Expanded: the bubble grows slightly into a card showing the amount converted
 *   into CFA francs.
 * - Any touch outside the expanded card collapses it back into its corner.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_SHOW = "com.instantconverter.action.SHOW"
        const val ACTION_CONVERT = "com.instantconverter.action.CONVERT"
        const val ACTION_STOP = "com.instantconverter.action.STOP"
        const val EXTRA_TEXT = "text"
        /** True when the user explicitly asked for a conversion (menu, app). */
        const val EXTRA_EXPLICIT = "explicit"

        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: BubbleService? = null
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val i = Intent(context, BubbleService::class.java).setAction(ACTION_SHOW)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }

        /**
         * Sends [text] to the bubble. Returns false when the bubble can't be
         * displayed (overlay permission missing).
         */
        fun convert(context: Context, text: String, explicit: Boolean): Boolean {
            instance?.let {
                it.showConversion(text, explicit)
                return true
            }
            if (!Settings.canDrawOverlays(context)) return false
            val i = Intent(context, BubbleService::class.java)
                .setAction(ACTION_CONVERT)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_EXPLICIT, explicit)
            context.startForegroundService(i)
            return true
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var bubble: TextView
    private lateinit var card: LinearLayout
    private lateinit var tvSource: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvRate: TextView

    private var attached = false
    private var expanded = false
    private var cornerRight = true
    private var cornerBottom = true
    private var snapAnimator: ValueAnimator? = null
    private var lastResultText: String? = null
    private var lastClipboardText: String? = null

    private val accent = Color.parseColor("#1B7F5A")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("bubble", MODE_PRIVATE)
        cornerRight = prefs.getBoolean("right", true)
        cornerBottom = prefs.getBoolean("bottom", true)
        buildViews()
        instance = this
        RateRepository.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        attachIfNeeded()
        if (intent?.action == ACTION_CONVERT) {
            intent.getStringExtra(EXTRA_TEXT)?.let {
                showConversion(it, intent.getBooleanExtra(EXTRA_EXPLICIT, true))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        snapAnimator?.cancel()
        if (attached) {
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
            attached = false
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) root.post { moveToCorner(animate = false) }
    }

    // ---------------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- views

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        root = FrameLayout(this)

        bubble = TextView(this).apply {
            text = getString(R.string.bubble_label)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setStroke(dp(2f), Color.WHITE)
            }
            elevation = dp(6f).toFloat()
            alpha = 0.92f
        }
        val size = dp(54f)
        root.addView(bubble, FrameLayout.LayoutParams(size, size).apply {
            setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(26f).toFloat()
                setColor(accent)
                setStroke(dp(2f), Color.WHITE)
            }
            elevation = dp(8f).toFloat()
            visibility = View.GONE
            minimumWidth = dp(150f)
        }
        tvSource = TextView(this).apply {
            setTextColor(Color.parseColor("#D8F3E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        tvResult = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }
        tvRate = TextView(this).apply {
            setTextColor(Color.parseColor("#B5E3CF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        card.addView(tvSource)
        card.addView(tvResult)
        card.addView(tvRate)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(6f), dp(6f), dp(6f), dp(6f)) })

        // Taps outside our window arrive as ACTION_OUTSIDE thanks to FLAG_WATCH_OUTSIDE_TOUCH.
        // Long-pressing the expanded card copies the result (handled in the listener).
        root.setOnTouchListener(DragTouchListener())

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun attachIfNeeded() {
        if (attached) return
        val pos = cornerPosition(measureRoot())
        params.x = pos.x
        params.y = pos.y
        wm.addView(root, params)
        attached = true
    }

    // ---------------------------------------------------------------- geometry

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun measureRoot(): Point {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(spec, spec)
        return Point(root.measuredWidth, root.measuredHeight)
    }

    /** Top-left coordinates that put a window of [size] in the current corner. */
    private fun cornerPosition(size: Point): Point {
        val screen = screenSize()
        val marginX = dp(6f)
        val marginTop = dp(90f)
        val marginBottom = dp(110f)
        val x = if (cornerRight) screen.x - size.x - marginX else marginX
        val y = if (cornerBottom) screen.y - size.y - marginBottom else marginTop
        return Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    private fun moveToCorner(animate: Boolean) {
        if (!attached) return
        snapAnimator?.cancel()
        val target = cornerPosition(measureRoot())
        if (!animate) {
            params.x = target.x
            params.y = target.y
            safeUpdate()
            return
        }
        val startX = params.x
        val startY = params.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (startX + (target.x - startX) * f).toInt()
                params.y = (startY + (target.y - startY) * f).toInt()
                safeUpdate()
            }
            start()
        }
    }

    private fun safeUpdate() {
        if (!attached) return
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun saveCorner() {
        getSharedPreferences("bubble", MODE_PRIVATE).edit()
            .putBoolean("right", cornerRight)
            .putBoolean("bottom", cornerBottom)
            .apply()
    }

    // ---------------------------------------------------------------- expand / collapse

    fun showConversion(text: String, explicit: Boolean) {
        val parsed = AmountParser.parse(text, requireCurrency = !explicit)
        if (parsed == null) {
            if (!explicit) return // automatic detection: stay quiet
            tvSource.text = ellipsize(text)
            tvResult.text = getString(R.string.no_amount)
            tvRate.text = getString(R.string.no_amount_hint)
            lastResultText = null
        } else {
            val conv = RateRepository.converter(this)
            if (parsed.currency != null) {
                val xof = conv.formatXof(conv.toXof(parsed.value, parsed.currency))
                tvSource.text = conv.formatSource(parsed.value, parsed.currency)
                tvResult.text = xof
                tvRate.text = conv.formatRate(parsed.currency)
                lastResultText = xof
            } else {
                val eur = conv.formatXof(conv.toXof(parsed.value, Currency.EUR))
                val usd = conv.formatXof(conv.toXof(parsed.value, Currency.USD))
                tvSource.text = getString(R.string.unknown_currency)
                tvResult.text = "${conv.formatSource(parsed.value, Currency.EUR)} → $eur\n" +
                    "${conv.formatSource(parsed.value, Currency.USD)} → $usd"
                tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                tvRate.text = "${conv.formatRate(Currency.EUR)} · ${conv.formatRate(Currency.USD)}"
                lastResultText = eur
            }
            if (parsed.currency != null) tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }
        attachIfNeeded()
        expand()
    }

    private fun showIdleCard() {
        tvSource.text = getString(R.string.app_name)
        tvResult.text = getString(R.string.idle_title)
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        tvRate.text = getString(R.string.idle_hint)
        lastResultText = null
        expand()
    }

    private fun ellipsize(s: String): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length > 40) t.take(39) + "…" else t
    }

    private fun expand() {
        bubble.visibility = View.GONE
        card.visibility = View.VISIBLE
        expanded = true
        moveToCorner(animate = false)
        // Grow from the corner the bubble is parked in.
        card.post {
            card.pivotX = if (cornerRight) card.width.toFloat() else 0f
            card.pivotY = if (cornerBottom) card.height.toFloat() else 0f
            card.scaleX = 0.55f
            card.scaleY = 0.55f
            card.alpha = 0.4f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        card.animate().cancel()
        card.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(140)
            .withEndAction {
                card.visibility = View.GONE
                card.alpha = 1f
                card.scaleX = 1f
                card.scaleY = 1f
                bubble.visibility = View.VISIBLE
                bubble.scaleX = 0.7f
                bubble.scaleY = 0.7f
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                moveToCorner(animate = false)
            }
            .start()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)!!
        cm.setPrimaryClip(ClipData.newPlainText("FCFA", text))
        Toast.makeText(this, getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- touch

    private inner class DragTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var downTime = 0L
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    // Click outside the window: close and tidy the bubble into its corner.
                    collapse()
                    return false
                }
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downRawX = e.rawX
                    downRawY = e.rawY
                    downX = params.x
                    downY = params.y
                    downTime = e.eventTime
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && !expanded && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (downX + dx).toInt()
                        params.y = (downY + dy).toInt()
                        safeUpdate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        val screen = screenSize()
                        cornerRight = params.x + root.width / 2 > screen.x / 2
                        cornerBottom = params.y + root.height / 2 > screen.y / 2
                        saveCorner()
                        moveToCorner(animate = true)
                    } else if (expanded) {
                        if (e.eventTime - downTime >= longPressTimeout) {
                            lastResultText?.let { copyToClipboard(it) }
                        } else {
                            collapse()
                        }
                    } else {
                        v.performClick()
                        onBubbleTapped()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        moveToCorner(animate = true)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun isConvertible(clip: String?): Boolean =
        clip != null && !Regex("""(?i)f?cfa|xof""").containsMatchIn(clip) &&
            AmountParser.parse(clip, requireCurrency = false) != null

    private fun onBubbleTapped() {
        // A freshly copied amount wins; otherwise re-open the last conversion,
        // or a short help card.
        readClipboard { clip ->
            when {
                clip != lastClipboardText && isConvertible(clip) -> {
                    lastClipboardText = clip
                    showConversion(clip!!, explicit = true)
                }
                tvResult.text.isNullOrEmpty() -> showIdleCard()
                else -> expand()
            }
        }
    }

    /**
     * Called by the accessibility service right after it pressed "Copy" in a
     * browser's selection toolbar: converts what was just copied, and stays
     * quiet when the selection holds no amount.
     */
    fun convertClipboard() {
        readClipboard { clip ->
            if (isConvertible(clip)) {
                lastClipboardText = clip
                showConversion(clip!!, explicit = true)
            }
        }
    }

    /**
     * Since Android 10 only the focused window may read the clipboard, so the
     * bubble briefly takes focus, reads it, then gives focus back.
     */
    private fun readClipboard(callback: (String?) -> Unit) {
        val cm = getSystemService(ClipboardManager::class.java)!!
        val read = {
            try {
                cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()?.take(200)
            } catch (_: Exception) {
                null
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback(read())
            return
        }
        var done = false
        lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
        val finish = {
            if (!done) {
                done = true
                root.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                val text = read()
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                safeUpdate()
                callback(text)
            }
        }
        listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) finish() }
        root.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        safeUpdate()
        root.postDelayed({ finish() }, 500)
    }
}
