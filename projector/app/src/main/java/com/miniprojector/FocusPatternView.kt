package com.miniprojector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/**
 * Focus chart: a fine grid and circles to find the sharp distance, and the
 * words "TOP" / "LEFT" / "RIGHT" to check that the wall shows the right way round.
 */
class FocusPatternView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }
    private val thick = Paint(grid).apply { strokeWidth = 4f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 0) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)

        val step = min(w, h) / 8f
        var x = w / 2 % step
        while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += step }
        var y = h / 2 % step
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += step }

        canvas.drawRect(thick.strokeWidth / 2, thick.strokeWidth / 2, w - thick.strokeWidth / 2, h - thick.strokeWidth / 2, thick)
        val cx = w / 2
        val cy = h / 2
        for (i in 1..3) canvas.drawCircle(cx, cy, step * i, thick)
        canvas.drawLine(cx - step / 2, cy, cx + step / 2, cy, thick)
        canvas.drawLine(cx, cy - step / 2, cx, cy + step / 2, thick)

        // Arrow pointing up, under the word TOP.
        val a = step * 0.6f
        val top = step * 0.35f
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(cx, top)
            lineTo(cx - a / 2, top + a)
            lineTo(cx + a / 2, top + a)
            close()
        }, arrow)

        text.textSize = step * 0.5f
        canvas.drawText(context.getString(R.string.pattern_top), cx, top + a + text.textSize * 1.1f, text)
        text.textSize = step * 0.4f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.pattern_left), step * 0.3f, cy + text.textSize / 3, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.pattern_right), w - step * 0.3f, cy + text.textSize / 3, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = step * 0.28f
        canvas.drawText(context.getString(R.string.pattern_hint), cx, h - step * 0.4f, text)
    }
}
