package com.divefix.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Crop rectangle over the photo: drag a corner or edge to resize, drag inside to move.
 * crop = l, t, r, b in 0..1 of the (turned) picture; imgRect is where the whole picture sits in this view.
 */
class CropOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    val imgRect = RectF()
    var crop = floatArrayOf(0f, 0f, 1f, 1f)
        set(value) { field = value; invalidate() }
    var active = false
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val thirds = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f * d }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private var grab = 0  // bit flags: 1 left, 2 top, 4 right, 8 bottom; 15 = move
    private var lastX = 0f
    private var lastY = 0f

    private fun box() = RectF(imgRect.left + crop[0] * imgRect.width(), imgRect.top + crop[1] * imgRect.height(),
        imgRect.left + crop[2] * imgRect.width(), imgRect.top + crop[3] * imgRect.height())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active || imgRect.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = box(); val reach = 28 * d
                val inY = e.y > b.top - reach && e.y < b.bottom + reach; val inX = e.x > b.left - reach && e.x < b.right + reach
                grab = 0
                if (inY && abs(e.x - b.left) < reach) grab = grab or 1
                if (inY && abs(e.x - b.right) < reach) grab = if (grab and 1 != 0 && abs(e.x - b.left) < abs(e.x - b.right)) grab else (grab and 1.inv()) or 4
                if (inX && abs(e.y - b.top) < reach) grab = grab or 2
                if (inX && abs(e.y - b.bottom) < reach) grab = if (grab and 2 != 0 && abs(e.y - b.top) < abs(e.y - b.bottom)) grab else (grab and 2.inv()) or 8
                if (grab == 0 && b.contains(e.x, e.y)) grab = 15
                lastX = e.x; lastY = e.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / imgRect.width(); val dy = (e.y - lastY) / imgRect.height()
                lastX = e.x; lastY = e.y
                val c = crop.copyOf(); val min = 0.05f
                if (grab == 15) {  // move the whole box, keeping its size
                    val mx = dx.coerceIn(-c[0], 1f - c[2]); val my = dy.coerceIn(-c[1], 1f - c[3])
                    c[0] += mx; c[2] += mx; c[1] += my; c[3] += my
                } else {
                    if (grab and 1 != 0) c[0] = (c[0] + dx).coerceIn(0f, c[2] - min)
                    if (grab and 4 != 0) c[2] = (c[2] + dx).coerceIn(c[0] + min, 1f)
                    if (grab and 2 != 0) c[1] = (c[1] + dy).coerceIn(0f, c[3] - min)
                    if (grab and 8 != 0) c[3] = (c[3] + dy).coerceIn(c[1] + min, 1f)
                }
                crop = c
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!active || imgRect.isEmpty) return
        val b = box()
        c.drawRect(imgRect.left, imgRect.top, imgRect.right, b.top, shade)
        c.drawRect(imgRect.left, b.bottom, imgRect.right, imgRect.bottom, shade)
        c.drawRect(imgRect.left, b.top, b.left, b.bottom, shade)
        c.drawRect(b.right, b.top, imgRect.right, b.bottom, shade)
        for (k in 1..2) {  // rule of thirds
            val x = b.left + b.width() * k / 3; val y = b.top + b.height() * k / 3
            c.drawLine(x, b.top, x, b.bottom, thirds); c.drawLine(b.left, y, b.right, y, thirds)
        }
        c.drawRect(b, frame)
        val l = 18 * d
        for ((x, y, sx, sy) in listOf(floatArrayOf(b.left, b.top, 1f, 1f), floatArrayOf(b.right, b.top, -1f, 1f), floatArrayOf(b.left, b.bottom, 1f, -1f), floatArrayOf(b.right, b.bottom, -1f, -1f))) {
            c.drawLine(x, y, x + sx * l, y, corner); c.drawLine(x, y, x, y + sy * l, corner)
        }
    }
}
