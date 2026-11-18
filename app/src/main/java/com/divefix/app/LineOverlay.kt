package com.divefix.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Draws the gradient line over the photo and, in draw mode, lets the user drag a new one.
 * The line is stored as x0, y0, x1, y1 in 0..1 image coordinates: full effect behind the start's
 * perpendicular, fading to none at the end's perpendicular.
 */
class LineOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    val imgRect = RectF()  // where the photo is shown inside this view
    var line: FloatArray? = null
        set(value) { field = value; invalidate() }
    var drawing = false
        set(value) { field = value; invalidate() }
    var onLine: ((FloatArray) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val shadow = 0x99000000.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d; setShadowLayer(3 * d, 0f, 0f, shadow) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f); setShadowLayer(2 * d, 0f, 0f, shadow)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(3 * d, 0f, 0f, shadow) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * d; isFakeBoldText = true; setShadowLayer(3 * d, 0f, 0f, Color.BLACK) }
    private var start: FloatArray? = null
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawing || imgRect.isEmpty) return false  // not drawing: let the photo underneath get the tap
        val x = (e.x - imgRect.left) / imgRect.width()
        val y = (e.y - imgRect.top) / imgRect.height()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { start = floatArrayOf(x, y); downX = e.x; downY = e.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> start?.let { s ->
                if (hypot(e.x - downX, e.y - downY) > 12 * d) floatArrayOf(s[0], s[1], x, y).let { line = it; onLine?.invoke(it) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> start = null
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val l = line ?: return
        if (!drawing) return
        val ax = imgRect.left + l[0] * imgRect.width(); val ay = imgRect.top + l[1] * imgRect.height()
        val bx = imgRect.left + l[2] * imgRect.width(); val by = imgRect.top + l[3] * imgRect.height()
        val len = hypot(bx - ax, by - ay)
        if (len < 1f) return
        val px = -(by - ay) / len * 5000f; val py = (bx - ax) / len * 5000f
        c.save()
        c.clipRect(imgRect)
        c.drawLine(ax - px, ay - py, ax + px, ay + py, guide)  // full effect up to here
        c.drawLine(bx - px, by - py, bx + px, by + py, guide)  // no effect from here
        c.restore()
        c.drawLine(ax, ay, bx, by, stroke)
        c.drawCircle(ax, ay, 7 * d, dot)
        c.drawCircle(bx, by, 7 * d, stroke)
        c.drawText("100%", ax + 10 * d, ay - 10 * d, label)
        c.drawText("0%", bx + 10 * d, by - 10 * d, label)
    }
}
