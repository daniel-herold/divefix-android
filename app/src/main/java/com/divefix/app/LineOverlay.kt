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
 * Draws the gradient over the photo and, in draw mode, lets the user drag a new one (also starting outside the photo).
 * Stored as x0, y0, x1, y1, kind in 0..1 image coordinates.
 *  kind 0, linear: full effect behind the start's perpendicular, fading to none at the end's.
 *  kind 1, circle: centre at the start, radius to the end; full effect in the centre, none at the circle and beyond.
 *  kind 2, circle: the opposite, none in the centre, full effect at the circle and beyond.
 */
class LineOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    val imgRect = RectF()  // where the photo is shown inside this view
    var line: FloatArray? = null
        set(value) { field = value; invalidate() }
    var drawing = false
        set(value) { field = value; invalidate() }
    var onLine: ((FloatArray) -> Unit)? = null
    var kind = 0  // the tool used for the next drag

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
                if (hypot(e.x - downX, e.y - downY) > 12 * d) floatArrayOf(s[0], s[1], x, y, kind.toFloat()).let { line = it; onLine?.invoke(it) }
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
        val k = l.getOrElse(4) { 0f }.toInt()
        if (k != 0) {  // circle: dashed border, centre and edge handle
            c.drawCircle(ax, ay, len, guide)
            c.drawLine(ax, ay, bx, by, guide)
            if (k == 1) { c.drawCircle(ax, ay, 7 * d, dot); c.drawCircle(bx, by, 7 * d, stroke) } else { c.drawCircle(ax, ay, 7 * d, stroke); c.drawCircle(bx, by, 7 * d, dot) }
            c.drawText(if (k == 1) "100%" else "0%", ax + 10 * d, ay - 10 * d, label)
            c.drawText(if (k == 1) "0%" else "100%", bx + 10 * d, by - 10 * d, label)
            return
        }
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
