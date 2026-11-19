package com.divefix.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * Views for the grid, shared by the app and the layout screenshots.
 * Cell children: 0 thumbnail, 1 saved-on (phone/card), 2 bottom bar (play icon, length, spacer, time), 3 ✱ individual, 4 selected.
 * Header children: 0 texts (title, details), 1 select button.
 */
object GridViews {
    fun cell(ctx: Context): FrameLayout {
        val dp = ctx.resources.displayMetrics.density
        val icon = { res: Int -> ImageView(ctx).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(Color.WHITE) } }
        val small = (14 * dp).toInt()
        val label = { TextView(ctx).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall); setTextColor(Color.WHITE); fontFeatureSettings = "tnum" } }
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { val d = getDrawable(0); recycle(); d }
        val primary = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, Color.CYAN)
        return object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)  // square
        }.apply {
            setBackgroundColor(0xFF1D2021.toInt()); foreground = ripple
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(LinearLayout(ctx).apply {
                setBackgroundResource(R.drawable.pill); setPadding((5 * dp).toInt(), (3 * dp).toInt(), (5 * dp).toInt(), (3 * dp).toInt())
                addView(icon(R.drawable.ic_phone), LinearLayout.LayoutParams(small, small))
                addView(icon(R.drawable.ic_sd), LinearLayout.LayoutParams(small, small).apply { marginStart = (2 * dp).toInt() })
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, (4 * dp).toInt(), (4 * dp).toInt(), 0) })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.scrim_bottom)
                setPadding((6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                addView(icon(R.drawable.ic_play), LinearLayout.LayoutParams(small, small))
                addView(label().apply { setPadding((3 * dp).toInt(), 0, 0, 0) })
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(label())
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.pill); setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                addView(icon(R.drawable.ic_asterisk), FrameLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()))
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), 0, 0) })
            addView(FrameLayout(ctx).apply {  // selected: tinted, framed, check in the middle
                background = GradientDrawable().apply { setColor((primary and 0x00FFFFFF) or 0x40000000); setStroke((3 * dp).toInt(), primary) }
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check); setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    imageTintList = ColorStateList.valueOf(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
                }, FrameLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt(), Gravity.CENTER))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /** Fills the bottom bar: video length on the left, capture time on the right; hidden when neither applies. */
    fun bindBottom(cell: FrameLayout, length: String?, time: String?) {
        val bar = cell.getChildAt(2) as LinearLayout
        bar.visibility = if (length != null || time != null) View.VISIBLE else View.GONE
        bar.getChildAt(0).visibility = if (length != null) View.VISIBLE else View.GONE
        (bar.getChildAt(1) as TextView).apply { text = length; visibility = if (length != null) View.VISIBLE else View.GONE }
        (bar.getChildAt(3) as TextView).apply { text = time; visibility = if (time != null) View.VISIBLE else View.GONE }
    }

    fun header(ctx: Context): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding((12 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
                })
                addView(TextView(ctx).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); fontFeatureSettings = "tnum"
                    setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle))
        }
    }

    fun bindHeader(header: LinearLayout, title: String, details: String, action: String) {
        val texts = header.getChildAt(0) as LinearLayout
        (texts.getChildAt(0) as TextView).text = title
        (texts.getChildAt(1) as TextView).text = details
        (header.getChildAt(1) as MaterialButton).text = action
    }
}
