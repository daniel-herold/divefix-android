package com.divefix.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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

    private fun fill(root: View, singleView: Boolean, columns: Int = 3, sel: Set<Int> = emptySet(), drawingShot: Boolean = true, groupEdit: Boolean = false) {
        val ctx = root.context; val dp = ctx.resources.displayMetrics.density
        root.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            if (singleView) {
                setNavigationIcon(R.drawable.ic_arrow_back); title = "DJI_20261102_0042_D.JPG"; subtitle = "4 of 12 · fixed on phone"
                menu.add("Crop and rotate").setIcon(R.drawable.ic_crop).setShowAsAction(2)
                menu.add("Select").apply { setShowAsAction(2); actionView = com.google.android.material.button.MaterialButton(ctx).apply {
                    text = "Selected"; setIconResource(R.drawable.ic_check); insetTop = 0; insetBottom = 0; minHeight = (36 * dp).toInt(); iconPadding = (4 * dp).toInt() } }
                menu.add("Remove from set")
            }
            else { title = "DiveFix"; subtitle = "9 of 22 selected · 18 photos · 4 videos"; menu.add("Sort and filter").setIcon(R.drawable.ic_filter).setShowAsAction(2) }
            menu.add("Camera card folder…")
        }
        val grid = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.grid)
        // rows: header, files, header, files (like two dives)
        val rows: List<Any> = listOf("Dive 1|Sat 2 Nov · 10:12–11:04 · 9 photos · 2 videos") + (0 until 11) + listOf("Dive 2|Sat 2 Nov · 12:31–13:18 · 13 photos") + (11 until 24)
        val lm = androidx.recyclerview.widget.GridLayoutManager(ctx, columns)
        lm.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(p: Int) = if (rows[p] is String) lm.spanCount else 1 }
        grid.layoutManager = lm
        grid.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(p: Int) = if (rows[p] is String) 1 else 0
            override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int) = object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                if (type == 1) GridViews.header(ctx) else GridViews.cell(ctx).apply {
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2).apply { setMargins((1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt()) }
                }) {}
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                val r = rows[pos]
                if (r is String) { val (t, d) = r.split("|"); GridViews.bindHeader(h.itemView as LinearLayout, t, d, if (sel.isNotEmpty() && t == "Dive 1") "Deselect" else "Select"); return }
                val p = r as Int; val cell = h.itemView as FrameLayout
                (cell.getChildAt(0) as ImageView).setImageBitmap(fake(300, 300, p))
                val where = cell.getChildAt(1) as LinearLayout
                where.visibility = if (p % 3 == 0) View.VISIBLE else View.GONE; where.getChildAt(1).visibility = if (p % 2 == 0) View.VISIBLE else View.GONE
                GridViews.bindBottom(cell, if (p == 1 || p == 5) "0:42" else null, "%02d:%02d".format(10 + p / 11, (12 + p * 4) % 60))
                cell.getChildAt(3).visibility = if (p == 2 || p == 7) View.VISIBLE else View.GONE
                cell.getChildAt(4).visibility = View.GONE
                val img = cell.getChildAt(0) as ImageView
                if (p !in sel) { img.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }); img.alpha = .55f }
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
            root.findViewById<View>(R.id.addMore).visibility = View.VISIBLE
            root.findViewById<View>(R.id.gridCompare).apply {
                visibility = View.VISIBLE
                (layoutParams as FrameLayout.LayoutParams).marginEnd = (16 * dp).toInt()
            }
            val switch = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.modeSwitch)
            switch.visibility = View.VISIBLE; switch.text = "Edit selected"
            if (groupEdit) {  // grid with "Edit selected" on
                bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF004F58.toInt()); cornerRadius = 12 * dp }
                icon.setImageResource(R.drawable.ic_tune); icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFA2EEFF.toInt())
                title.text = "Only the ${sel.size} selected"; sub.text = "Each change is set on all selected files and makes them individual (✱)"
                title.setTextColor(0xFFA2EEFF.toInt()); sub.setTextColor(0xFFA2EEFF.toInt())
                switch.text = "Edit all"; switch.setTextColor(0xFFA2EEFF.toInt())
            }
        }
        if (singleView) {
            root.findViewById<View>(R.id.single).visibility = View.VISIBLE
            root.findViewById<ImageView>(R.id.image).setImageBitmap(fake(1280, 720, 3))
            root.findViewById<View>(R.id.videoRow).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.time).text = "0:12.48 / 0:42"
            root.findViewById<Slider>(R.id.timeline).apply { valueTo = 42f; value = 12f }
            root.findViewById<View>(R.id.videoRow).visibility = View.GONE
            val primary = android.content.res.ColorStateList.valueOf(0xFF6CCFDD.toInt()); val onPrimary = android.content.res.ColorStateList.valueOf(0xFF00363D.toInt())
            val dark = android.content.res.ColorStateList.valueOf(0xFF323536.toInt()); val onDark = android.content.res.ColorStateList.valueOf(0xFFE1E3E3.toInt())
            val tools = listOf(R.id.gradLinear, R.id.gradIn, R.id.gradOut).map { root.findViewById<com.google.android.material.button.MaterialButton>(it) }
            root.findViewById<LineOverlay>(R.id.lineOverlay).apply {
                imgRect.set(0f, (393 * dp - 393 * dp * 720 / 1280) / 2, 393 * dp, (393 * dp + 393 * dp * 720 / 1280) / 2)
                drawing = drawingShot; line = if (drawingShot) floatArrayOf(.25f, .75f, .62f, .3f, 0f) else null
            }
            if (drawingShot) {  // drawing a line: one pill with the three tools, ✓ and ✕; compare as an icon
                root.findViewById<View>(R.id.drawBar).visibility = View.VISIBLE
                root.findViewById<TextView>(R.id.drawHint).text = "Drag a line: ● full effect → ○ none"
                root.findViewById<View>(R.id.gradientGroup).background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF323536.toInt()); cornerRadius = 28 * dp }
                tools.forEachIndexed { k, b -> b.backgroundTintList = if (k == 0) primary else android.content.res.ColorStateList.valueOf(0); b.iconTint = if (k == 0) onPrimary else onDark }
                listOf(R.id.gradientSave, R.id.gradientDiscard).forEach { root.findViewById<com.google.android.material.button.MaterialButton>(it).apply { visibility = View.VISIBLE; iconTint = onDark } }
                root.findViewById<View>(R.id.compare).visibility = View.GONE
                root.findViewById<View>(R.id.compareIcon).visibility = View.VISIBLE
                listOf(R.id.prev, R.id.next, R.id.histo).forEach { root.findViewById<View>(it).visibility = View.GONE }
            } else {  // a circle gradient is applied: that tool is accent-filled, the others dark
                tools.forEachIndexed { k, b -> b.backgroundTintList = if (k == 1) primary else dark; b.iconTint = if (k == 1) onPrimary else onDark }
            }
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
        fill(root, false, 6, sel = setOf(0, 1, 3, 4, 7, 10, 12, 13, 17))
        paparazzi.snapshot(root)
    }

    @Test fun grid() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, false, sel = setOf(0, 1, 3, 4, 7, 10, 12, 13, 17)); paparazzi.snapshot(root)
    }

    @Test fun selection() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, false, sel = setOf(0, 1, 3, 4, 7, 10, 12, 13, 17), groupEdit = true); paparazzi.snapshot(root)
    }

    @Test fun singleVideo() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, true, drawingShot = false)
        root.findViewById<View>(R.id.gradientGroup).visibility = View.GONE
        root.findViewById<View>(R.id.frameTools).visibility = View.VISIBLE
        root.findViewById<View>(R.id.videoRow).visibility = View.VISIBLE
        paparazzi.snapshot(root)
    }

    @Test fun singleCircle() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, true, drawingShot = false); paparazzi.snapshot(root)
    }

    @Test fun single() {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<View>(R.id.media).layoutParams.height = (393 * root.resources.displayMetrics.density).toInt()
        fill(root, true); paparazzi.snapshot(root)
    }
}
