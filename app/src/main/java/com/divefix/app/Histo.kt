package com.divefix.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View

/** Small semi-transparent RGB histogram (64 bins per channel). */
class Histo @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var data: Array<IntArray>? = null
        set(value) { field = value; invalidate() }
    private val paints = intArrayOf(0x88FF5A4A.toInt(), 0x8850D070.toInt(), 0x885A8CFF.toInt()).map { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it } }
    private val path = Path()

    init {
        background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = 8 * resources.displayMetrics.density }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(c: Canvas) {
        val d = data ?: return
        val top = d.maxOf { h -> (1 until 63).maxOf { h[it] } }.coerceAtLeast(1)  // ignore clipped ends when scaling
        val w = width.toFloat(); val hh = height.toFloat()
        for (ch in 0..2) {
            path.reset(); path.moveTo(0f, hh)
            for (i in 0 until 64) path.lineTo(i * w / 63, hh - minOf(1f, d[ch][i].toFloat() / top) * hh * .9f)
            path.lineTo(w, hh); path.close()
            c.drawPath(path, paints[ch])
        }
    }
}
