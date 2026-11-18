package com.divefix.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import org.junit.Rule
import org.junit.Test

class LayoutShots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "Theme.DiveFix")

    private fun fake(w: Int, h: Int, seed: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it); val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(40 + seed * 9, 120 + seed * 5, 140), Color.rgb(10, 40 + seed * 4, 70), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = Color.rgb(200, 120 + seed * 7, 80); c.drawCircle(w * .4f + seed * 9, h * .55f, h * .12f, p)
    }

    private fun fill(root: View, singleView: Boolean, columns: Int = 3, sel: Set<Int> = emptySet()) {
        val ctx = root.context; val dp = ctx.resources.displayMetrics.density
        root.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            if (singleView) { setNavigationIcon(R.drawable.ic_arrow_back); title = "DJI_20261102_0042_D.JPG"; subtitle = "4 of 12 · fixed on phone"; menu.add("Remove").setShowAsAction(2) }
            else { title = "DiveFix"; subtitle = "18 photos · 4 videos" }
            menu.add("Camera card folder…")
        }
        val grid = root.findViewById<GridView>(R.id.grid)
        grid.numColumns = columns
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = 24
            override fun getItem(p: Int) = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, c: View?, parent: android.view.ViewGroup) = object : FrameLayout(ctx) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
            }.apply {
                addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(fake(300, 300, p)) })
                if (p % 3 == 0) addView(LinearLayout(ctx).apply {
                    setBackgroundResource(R.drawable.pill); setPadding((5 * dp).toInt(), (3 * dp).toInt(), (5 * dp).toInt(), (3 * dp).toInt())
                    addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_phone); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()))
                    if (p % 2 == 0) addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_sd); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, (4 * dp).toInt(), (4 * dp).toInt(), 0) })
                if (p == 2 || p == 7) addView(FrameLayout(ctx).apply {
                    setBackgroundResource(R.drawable.pill); setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_asterisk) }, FrameLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), 0, 0) })
                if (p in sel) addView(FrameLayout(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0x406CCFDD); setStroke((3 * dp).toInt(), 0xFF6CCFDD.toInt()) }
                    addView(ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_check); setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
                        imageTintList = android.content.res.ColorStateList.valueOf(0xFF00363D.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF6CCFDD.toInt()) }
                    }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (6 * dp).toInt(), (6 * dp).toInt()) })
                }, FrameLayout.LayoutParams(-1, -1))
                if (p == 1 || p == 5) addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.scrim_bottom); setPadding((6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                    addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_play); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()))
                    addView(TextView(ctx).apply { text = "0:42"; setTextColor(Color.WHITE); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall); setTextColor(Color.WHITE) })
                }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
        }
        root.findViewById<View>(R.id.empty).visibility = View.GONE
        root.findViewById<View>(R.id.grid).visibility = View.VISIBLE
        val bar = root.findViewById<LinearLayout>(R.id.modeBar)
        val title = root.findViewById<TextView>(R.id.modeTitle); val sub = root.findViewById<TextView>(R.id.modeSub)
        val icon = root.findViewById<ImageView>(R.id.modeIcon)
        if (singleView) {
            bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF004F58.toInt()); cornerRadius = 12 * dp }
            icon.setImageResource(R.drawable.ic_tune); icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFA2EEFF.toInt())
            title.text = "Only this photo"; sub.text = "Individual settings with a gradient"
            title.setTextColor(0xFFA2EEFF.toInt()); sub.setTextColor(0xFFA2EEFF.toInt())
            root.findViewById<View>(R.id.modeReset).visibility = View.VISIBLE
        } else {
            bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF272A2B.toInt()); cornerRadius = 12 * dp }
            icon.setImageResource(R.drawable.ic_photos); icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFE1E3E3.toInt())
            title.text = "All files"; sub.text = "Changes apply to all except 2 individually edited (✱)"
            title.setTextColor(0xFFE1E3E3.toInt()); sub.setTextColor(0xFFA8B0B2.toInt())
            root.findViewById<View>(R.id.addMore).visibility = if (sel.isEmpty()) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.gridCompare).apply {
                visibility = View.VISIBLE
                (layoutParams as FrameLayout.LayoutParams).marginEnd = ((if (sel.isEmpty()) 84 else 16) * dp).toInt()
            }
            if (sel.isNotEmpty()) {
                root.findViewById<MaterialToolbar>(R.id.toolbar).apply {
                    menu.clear(); setNavigationIcon(R.drawable.ic_close); this.title = "${sel.size} selected"; this.subtitle = null
                    menu.add("Remove").setShowAsAction(2); menu.add("Select all")
                }
                bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF004F58.toInt()); cornerRadius = 12 * dp }
                icon.setImageResource(R.drawable.ic_tune); icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFA2EEFF.toInt())
                title.text = "Only the ${sel.size} selected"; sub.text = "Each change is set on all selected files and makes them individual (✱)"
                title.setTextColor(0xFFA2EEFF.toInt()); sub.setTextColor(0xFFA2EEFF.toInt())
            }
        }
        if (singleView) {
            root.findViewById<View>(R.id.single).visibility = View.VISIBLE
            root.findViewById<ImageView>(R.id.image).setImageBitmap(fake(1280, 720, 3))
            root.findViewById<View>(R.id.videoRow).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.time).text = "0:12 / 0:42"
            root.findViewById<Slider>(R.id.timeline).apply { valueTo = 42f; value = 12f }
            root.findViewById<LineOverlay>(R.id.lineOverlay).apply {
                imgRect.set(0f, (393 * dp - 393 * dp * 720 / 1280) / 2, 393 * dp, (393 * dp + 393 * dp * 720 / 1280) / 2)
                drawing = true; line = floatArrayOf(.25f, .75f, .62f, .3f)
            }
            root.findViewById<View>(R.id.drawBar).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.drawHint).text = "Drag again to redraw · ● full effect, ○ none"
            val on = android.content.res.ColorStateList.valueOf(0xFF00363D.toInt())
            root.findViewById<View>(R.id.gradientGroup).background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF6CCFDD.toInt()); cornerRadius = 24 * dp }
            root.findViewById<com.google.android.material.button.MaterialButton>(R.id.gradientBtn).apply { backgroundTintList = android.content.res.ColorStateList.valueOf(0); iconTint = on }
            listOf(R.id.gradientSave, R.id.gradientDiscard).forEach { root.findViewById<com.google.android.material.button.MaterialButton>(it).apply { visibility = View.VISIBLE; iconTint = on } }
            root.findViewById<View>(R.id.videoRow).visibility = View.GONE
            root.findViewById<View>(R.id.histo).visibility = View.GONE
            root.findViewById<Histo>(R.id.histo).data = Array(3) { ch -> IntArray(64) { i -> (1000 * Math.exp(-Math.pow((i - 20.0 - ch * 8) / 9.0, 2.0))).toInt() + 20 } }
        }
        val chips = root.findViewById<ChipGroup>(R.id.presets)
        listOf("Shallow", "Medium", "Deep").forEachIndexed { k, n ->
            val chip = android.view.LayoutInflater.from(ctx).inflate(R.layout.chip_preset, chips, false) as Chip
            chip.text = n; chip.id = View.generateViewId(); chips.addView(chip); if (k == 1) chip.isChecked = true
        }
        chips.addView(android.view.LayoutInflater.from(ctx).inflate(R.layout.chip_add, chips, false))
        val box = root.findViewById<LinearLayout>(R.id.sliders)
        val names = arrayOf("Red restore", "Green", "Blue", "Brightness", "Contrast", "Saturation", "Vibrance")
        val values = floatArrayOf(60f, -27f, -50f, 10f, 25f, 10f, 30f)
        val dots = intArrayOf(0xFFE5604D.toInt(), 0xFF5CC27F.toInt(), 0xFF5B8DEF.toInt(), 0, 0, 0, 0)
        for (i in names.indices) {
            val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val icons = intArrayOf(0, 0, 0, R.drawable.ic_brightness, R.drawable.ic_contrast, R.drawable.ic_saturation, R.drawable.ic_vibrance)
            row.addView(FrameLayout(ctx).apply {
                if (dots[i] != 0) addView(View(ctx).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(dots[i]) } }, FrameLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt(), Gravity.CENTER))
                else addView(ImageView(ctx).apply { setImageResource(icons[i]); imageTintList = android.content.res.ColorStateList.valueOf(0xFFA8B0B2.toInt()) }, FrameLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt(), Gravity.CENTER))
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            row.addView(TextView(ctx).apply { text = names[i]; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }, LinearLayout.LayoutParams((88 * dp).toInt(), -2))
            row.addView(Slider(ctx).apply { valueFrom = if (i == 0 || i == 6) 0f else -100f; valueTo = 100f; stepSize = 1f; value = values[i]; labelBehavior = LabelFormatter.LABEL_GONE; isTickVisible = false }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(ctx).apply { text = values[i].toInt().let { if (it > 0 && i in 1..5) "+$it" else "$it" }; gravity = Gravity.END; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(0xFFA8B0B2.toInt()) }, LinearLayout.LayoutParams((36 * dp).toInt(), -2))
            box.addView(row)
        }
        root.findViewById<MaterialButtonToggleGroup>(R.id.dest).check(R.id.destBoth)
        root.findViewById<TextView>(R.id.go).apply { text = "Correct 12"; isEnabled = true }
    }

    @Test fun tablet() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_C)
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<GridView>(R.id.grid).apply { numColumns = GridView.AUTO_FIT; columnWidth = (150 * root.resources.displayMetrics.density).toInt() }
        fill(root, false, GridView.AUTO_FIT)
        paparazzi.snapshot(root)
    }

    @Test fun grid() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, false); paparazzi.snapshot(root)
    }

    @Test fun selection() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, false, sel = setOf(1, 3, 4)); paparazzi.snapshot(root)
    }

    @Test fun single() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, true); paparazzi.snapshot(root)
    }
}
