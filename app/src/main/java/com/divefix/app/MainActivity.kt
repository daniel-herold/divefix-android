package com.divefix.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val ids = arrayOf("red", "green", "blue", "bright", "contrast", "sat", "pop")
    private val names = arrayOf("Red restore", "Green", "Blue", "Brightness", "Contrast", "Saturation", "Vibrance (pop)")
    private val mins = floatArrayOf(0f, -30f, -40f, -.2f, .8f, .5f, 0f)
    private val maxs = floatArrayOf(100f, 10f, 10f, .2f, 1.6f, 2f, 100f)
    private val steps = floatArrayOf(1f, 1f, 1f, .01f, .01f, .01f, 1f)
    private val neutral = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 0f)
    private val tints = intArrayOf(0xFFF0704F.toInt(), 0xFF5CC27F.toInt(), 0xFF5B8DEF.toInt(), 0xFF4FB3C4.toInt(), 0xFF4FB3C4.toInt(), 0xFF4FB3C4.toInt(), 0xFFE7B53C.toInt())
    private val defaults = """{"Shallow 0-5 m":{"red":30,"green":0,"blue":-10,"bright":0,"contrast":1.05,"sat":1.05,"pop":20},"Medium 5-15 m":{"red":60,"green":-8,"blue":-20,"bright":0.02,"contrast":1.1,"sat":1.1,"pop":30},"Deep 15 m+":{"red":100,"green":-15,"blue":-30,"bright":0.04,"contrast":1.15,"sat":1.15,"pop":40}}"""
    @Suppress("DEPRECATION")
    private val exifTags = arrayOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE
    )

    private val n = ids.size
    private val v = neutral.copyOf()
    private val bars = arrayOfNulls<SeekBar>(n)
    private val vals = arrayOfNulls<TextView>(n)
    private var files = listOf<Uri>()
    private var cur = 0
    private var previewSeq = 0
    @Volatile private var renderSeq = 0
    private val renderer = Executors.newSingleThreadExecutor()
    private var srcBmp: Bitmap? = null
    private var srcPx: IntArray? = null
    private var outBmp: Bitmap? = null
    private var transformer: Transformer? = null
    private var running = false
    private var cancelled = false
    private var saved = 0
    private var failed = 0
    private var lastError: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("divefix", MODE_PRIVATE) }
    private lateinit var presets: JSONObject
    private lateinit var preview: ImageView
    private lateinit var full: Dialog
    private lateinit var fullImg: ImageView
    private lateinit var fullHint: TextView
    private lateinit var fileLabel: TextView
    private lateinit var badge: TextView
    private lateinit var removeBtn: Button
    private lateinit var spinner: Spinner
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var go: Button
    private lateinit var stop: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1B20.toInt()); setPadding(pad, pad, pad, pad); fitsSystemWindows = true
        }

        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Preview, tap for full screen"
            setOnClickListener { openFull() }
        }
        badge = TextView(this).apply {
            text = "✓ Fixed copy exists"; setTextColor(Color.WHITE); textSize = 13f; setPadding(pad, pad / 3, pad, pad / 3); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xE01F7A8C.toInt()); cornerRadius = 6 * dp }
        }
        removeBtn = Button(this).apply { text = "✕ Remove"; alpha = .9f; visibility = View.GONE; setOnClickListener { removeCurrent() } }
        root.addView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(pad / 2, pad / 2, 0, 0) })
            addView(removeBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * .36f).toInt()))

        // Full-screen viewer: hold anywhere for the original, Back closes it.
        fullImg = ImageView(this).apply {
            setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { setImageBitmap(srcBmp); fullHint.visibility = View.GONE }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setImageBitmap(outBmp ?: srcBmp); view.performClick() }
                }
                true
            }
        }
        fullHint = TextView(this).apply {
            text = "Hold anywhere for original · Back to return"; setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        full = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(FrameLayout(this@MainActivity).apply {
                addView(fullImg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(fullHint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 4 * pad })
            })
        }

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(Button(this).apply {
            text = "‹"; minWidth = 0; minimumWidth = (48 * dp).toInt()
            setOnClickListener { if (files.isNotEmpty()) { cur = (cur - 1 + files.size) % files.size; loadPreview() } }
        })
        fileLabel = TextView(this).apply {
            text = "Pick or share photos and videos to start"; setTextColor(0xFF8FA3A9.toInt()); isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        nav.addView(fileLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(Button(this).apply {
            text = "›"; minWidth = 0; minimumWidth = (48 * dp).toInt()
            setOnClickListener { if (files.isNotEmpty()) { cur = (cur + 1) % files.size; loadPreview() } }
        })
        nav.addView(Button(this).apply {
            text = "Hold: original"
            setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { preview.setImageBitmap(srcBmp) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { preview.setImageBitmap(outBmp ?: srcBmp); view.performClick() }
                }
                true
            }
        })
        root.addView(nav)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val presetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        spinner = Spinner(this)
        presetRow.addView(spinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        presetRow.addView(Button(this).apply { text = "Save"; setOnClickListener { savePreset() } })
        presetRow.addView(Button(this).apply { text = "Delete"; setOnClickListener { deletePreset() } })
        box.addView(presetRow)
        for (i in 0 until n) {
            val head = LinearLayout(this)
            head.addView(TextView(this).apply { text = names[i]; setTextColor(0xFFE3ECEE.toInt()); textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            vals[i] = TextView(this).apply { setTextColor(0xFF8FA3A9.toInt()) }
            head.addView(vals[i])
            bars[i] = SeekBar(this).apply {
                max = ((maxs[i] - mins[i]) / steps[i]).roundToInt()
                progressTintList = ColorStateList.valueOf(tints[i]); thumbTintList = ColorStateList.valueOf(tints[i])
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        if (user) { v[i] = mins[i] + p * steps[i]; showValue(i); render() }
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            box.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
            box.addView(bars[i], LinearLayout.LayoutParams(MATCH_PARENT, (36 * dp).toInt()))
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(0xFF4FB3C4.toInt())
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(this).apply { setTextColor(0xFF8FA3A9.toInt()); text = "Corrected files go to DCIM/DiveFix" }
        root.addView(status)
        val actions = LinearLayout(this)
        pick = Button(this).apply {
            text = "Pick files"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, 1)
            }
        }
        go = Button(this).apply {
            text = "Correct 0 files"; isEnabled = false; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(0xFFD9502F.toInt())
            setOnClickListener { startBatch() }
        }
        stop = Button(this).apply {
            text = "Cancel"; visibility = View.GONE
            setOnClickListener { cancelled = true; transformer?.cancel(); finishBatch() }
        }
        actions.addView(pick)
        actions.addView(go, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(stop)
        root.addView(actions)
        setContentView(root)

        presets = JSONObject(prefs.getString("presets", null) ?: defaults)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                presets.optJSONObject(p?.getItemAtPosition(pos) as? String ?: return)?.let { o ->
                    for (i in 0 until n) {
                        v[i] = o.optDouble(ids[i], neutral[i].toDouble()).toFloat()
                        bars[i]!!.progress = ((v[i] - mins[i]) / steps[i]).roundToInt()
                        showValue(i)
                    }
                    render()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        refreshPresets(null)
        takeShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShared(intent)
    }

    @Suppress("DEPRECATION")
    private fun takeShared(i: Intent?) {
        val list = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: listOf()
            else -> return
        }
        if (list.isNotEmpty()) setFiles(list)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        setFiles(data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data))
    }

    override fun onDestroy() {
        transformer?.cancel()
        renderer.shutdownNow()
        super.onDestroy()
    }

    private fun setFiles(list: List<Uri>) {
        if (running) { status.text = "Wait for the current batch to finish first"; return }
        files = list
        cur = 0
        updateCount()
        loadPreview()
    }

    private fun updateCount() {
        go.text = "Correct ${files.size} file${if (files.size == 1) "" else "s"}"
        go.isEnabled = files.isNotEmpty() && !running
        removeBtn.visibility = if (files.isNotEmpty() && !running) View.VISIBLE else View.GONE
    }

    private fun removeCurrent() {
        if (running || files.isEmpty()) return
        files = files.filterIndexed { k, _ -> k != cur }
        cur = cur.coerceAtMost(files.size - 1).coerceAtLeast(0)
        updateCount()
        if (files.isNotEmpty()) { loadPreview(); return }
        previewSeq++; srcBmp = null; srcPx = null; outBmp = null
        preview.setImageBitmap(null); badge.visibility = View.GONE
        fileLabel.text = "Pick or share photos and videos to start"
    }

    // Our earlier output for this file in DCIM/DiveFix, if any (only files DiveFix created are visible to it).
    private fun existing(base: String, video: Boolean): Uri? {
        val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return contentResolver.query(
            col, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("DCIM/DiveFix/", base + if (video) ".mp4" else ".jpg"), null
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(col, it.getLong(0)) else null }
    }

    private fun refreshBadge() {
        if (files.isEmpty()) return
        val uri = files[cur]
        val seq = previewSeq
        thread {
            val has = runCatching { existing(nameOf(uri).substringBeforeLast('.') + "_fix", isVideo(uri)) != null }.getOrDefault(false)
            runOnUiThread { if (seq == previewSeq) badge.visibility = if (has) View.VISIBLE else View.GONE }
        }
    }

    private fun showValue(i: Int) {
        vals[i]!!.text = if (steps[i] >= 1f) "%.0f".format(v[i]) else "%.2f".format(v[i])
    }

    // 3x4 row-major colour matrix on 0..1 RGB: channel mix (red restore), saturation, contrast, brightness.
    private fun matrix(): FloatArray {
        val a = v[0] / 100f; val g = 1 + v[1] / 100f; val b = 1 + v[2] / 100f; val br = v[3]; val c = v[4]; val s = v[5]
        val mix = arrayOf(floatArrayOf(1f, a * .5f, a * .2f), floatArrayOf(0f, g, 0f), floatArrayOf(0f, 0f, b))
        val lw = floatArrayOf(.2126f, .7152f, .0722f)
        val out = FloatArray(12)
        for (i in 0..2) {
            for (j in 0..2) {
                var t = 0f
                for (k in 0..2) t += ((1 - s) * lw[k] + (if (i == k) s else 0f)) * mix[k][j]
                out[i * 4 + j] = c * t
            }
            out[i * 4 + 3] = .5f * (1 - c) + br
        }
        return out
    }

    // The one colour function used by the preview, photo export and (baked into a LUT) video export.
    // Vibrance boosts muted colours strongly and already-saturated ones barely, so the water pops without neon fish.
    private fun fx(r: Float, g: Float, b: Float, m: FloatArray, pop: Float): Int {
        var nr = (m[0] * r + m[1] * g + m[2] * b + m[3]).coerceIn(0f, 1f)
        var ng = (m[4] * r + m[5] * g + m[6] * b + m[7]).coerceIn(0f, 1f)
        var nb = (m[8] * r + m[9] * g + m[10] * b + m[11]).coerceIn(0f, 1f)
        if (pop > 0f) {
            val chroma = maxOf(nr, ng, nb) - minOf(nr, ng, nb)
            val k = 1 + pop * (1 - chroma) * (1 - chroma)
            val l = .2126f * nr + .7152f * ng + .0722f * nb
            nr = (l + (nr - l) * k).coerceIn(0f, 1f); ng = (l + (ng - l) * k).coerceIn(0f, 1f); nb = (l + (nb - l) * k).coerceIn(0f, 1f)
        }
        return Color.rgb((nr * 255 + .5f).toInt(), (ng * 255 + .5f).toInt(), (nb * 255 + .5f).toInt())
    }

    private fun render() {
        val px = srcPx ?: return
        val src = srcBmp ?: return
        val seq = ++renderSeq
        val m = matrix(); val pop = v[6] / 100f
        renderer.execute {
            if (seq != renderSeq) return@execute
            val out = IntArray(px.size)
            for (k in px.indices) { val p = px[k]; out[k] = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop) }
            val bmp = Bitmap.createBitmap(out, src.width, src.height, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (seq != renderSeq) return@runOnUiThread
                outBmp = bmp
                preview.setImageBitmap(bmp)
                if (full.isShowing) fullImg.setImageBitmap(bmp)
            }
        }
    }

    private fun openFull() {
        if (srcBmp == null) return
        fullImg.setImageBitmap(outBmp ?: srcBmp)
        fullHint.visibility = View.VISIBLE
        full.show()
        ui.postDelayed({ fullHint.visibility = View.GONE }, 3000)
    }

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun isVideo(uri: Uri) = contentResolver.getType(uri)?.startsWith("video") == true

    private fun loadPreview() {
        if (files.isEmpty()) return
        val uri = files[cur]
        val seq = ++previewSeq
        fileLabel.text = "${cur + 1}/${files.size}  ${nameOf(uri)}"
        badge.visibility = View.GONE
        refreshBadge()
        thread {
            val bmp = runCatching {
                if (isVideo(uri)) MediaMetadataRetriever().run {
                    setDataSource(this@MainActivity, uri)
                    val mid = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 500
                    val f = getScaledFrameAtTime(mid, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
                    release()
                    f?.copy(Bitmap.Config.ARGB_8888, false)
                } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = 1440f / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            val px = bmp?.let { IntArray(it.width * it.height).also { a -> it.getPixels(a, 0, it.width, 0, 0, it.width, it.height) } }
            runOnUiThread {
                if (seq != previewSeq) return@runOnUiThread
                srcBmp = bmp; srcPx = px; outBmp = null
                preview.setImageBitmap(bmp)
                if (bmp == null) status.text = "Can't preview this file" else render()
            }
        }
    }

    private fun refreshPresets(select: String?) {
        val list = presets.keys().asSequence().toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
        if (select != null && select in list) spinner.setSelection(list.indexOf(select))
    }

    private fun savePreset() {
        val input = EditText(this).apply { setText(spinner.selectedItem as? String ?: ""); selectAll() }
        AlertDialog.Builder(this).setTitle("Save preset").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                presets.put(name, JSONObject().apply { for (i in 0 until n) put(ids[i], v[i].toDouble()) })
                prefs.edit().putString("presets", presets.toString()).apply()
                refreshPresets(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset() {
        val name = spinner.selectedItem as? String ?: return
        AlertDialog.Builder(this).setMessage("Delete preset \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                presets.remove(name)
                prefs.edit().putString("presets", presets.toString()).apply()
                refreshPresets(null)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startBatch() {
        running = true; cancelled = false; saved = 0; failed = 0; lastError = null
        go.isEnabled = false; pick.isEnabled = false; stop.visibility = View.VISIBLE; removeBtn.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        process(0, matrix(), v[6] / 100f)
    }

    private fun finishBatch() {
        running = false; transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pick.isEnabled = true; stop.visibility = View.GONE
        updateCount(); refreshBadge()
        if (!cancelled) bar.progress = 1000
        status.text = (if (cancelled) "Stopped. " else "Done. ") + "$saved saved to DCIM/DiveFix" +
            (if (failed > 0) ", $failed failed" + (lastError?.let { " ($it)" } ?: "") else "")
    }

    private fun process(i: Int, m: FloatArray, pop: Float) {
        if (!running) return
        if (cancelled || i >= files.size) { finishBatch(); return }
        val uri = files[i]
        val name = nameOf(uri)
        val base = name.substringBeforeLast('.') + "_fix"
        status.text = "${i + 1} of ${files.size}: $name"
        bar.progress = i * 1000 / files.size

        if (!isVideo(uri)) {
            thread {
                val result = runCatching {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.isMutableRequired = true
                    }
                    val w = bmp.width
                    val row = IntArray(w)
                    for (y in 0 until bmp.height) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1)
                        for (x in 0 until w) { val p = row[x]; row[x] = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop) }
                        bmp.setPixels(row, 0, w, 0, y, w, 1)
                    }
                    existing(base, false)?.let { old -> runCatching { contentResolver.delete(old, null, null) } } // replace the earlier fix
                    val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.jpg"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DiveFix"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })!!
                    contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bmp.recycle()
                    runCatching { // capture date, GPS and camera info; orientation is already applied to the pixels
                        val src = contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
                        contentResolver.openFileDescriptor(out, "rw")!!.use { fd ->
                            ExifInterface(fd.fileDescriptor).apply { exifTags.forEach { t -> src.getAttribute(t)?.let { setAttribute(t, it) } }; saveAttributes() }
                        }
                    }
                    contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                runOnUiThread {
                    if (result.isSuccess) saved++ else { failed++; lastError = result.exceptionOrNull()?.message }
                    process(i + 1, m, pop)
                }
            }
            return
        }

        val tmp = File(cacheDir, "export.mp4").apply { delete() }
        val mr = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) }
        val bitrate = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 60_000_000
        val taken = runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)!!)!!.time
        }.getOrNull()
        mr.release()
        // Bake fx() into a 33x33x33 LUT; the GPU interpolates it trilinearly per pixel.
        val lut = Array(33) { r -> Array(33) { g -> IntArray(33) { b -> fx(r / 32f, g / 32f, b / 32f, m, pop) } } }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(listOf(), listOf<Effect>(SingleColorLut.createFromCube(lut))))
            .build()
        val t = Transformer.Builder(this)
            .setEncoderFactory(DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build())
            // Media3's own muxer, so the original recording date is written into the new MP4.
            .setMuxerFactory(InAppMuxer.Factory.Builder().setMetadataProvider { entries ->
                if (taken != null) {
                    entries.removeIf { it is Mp4TimestampData }
                    entries.add(Mp4TimestampData(Mp4TimestampData.unixTimeToMp4TimeSeconds(taken), Mp4TimestampData.unixTimeToMp4TimeSeconds(taken)))
                }
            }.build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    thread {
                        val result = runCatching {
                            existing(base, true)?.let { old -> runCatching { contentResolver.delete(old, null, null) } } // replace the earlier fix
                            val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.mp4"); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DiveFix"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                                taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                            })!!
                            contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } }
                            contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                        }
                        tmp.delete()
                        runOnUiThread {
                            if (result.isSuccess) saved++ else { failed++; lastError = result.exceptionOrNull()?.message }
                            process(i + 1, m, pop)
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    tmp.delete(); failed++; lastError = exportException.message
                    process(i + 1, m, pop)
                }
            })
            .build()
        transformer = t
        t.start(
            Composition.Builder(EditedMediaItemSequence(listOf(item)))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build(),
            tmp.absolutePath
        )
        val holder = ProgressHolder()
        ui.post(object : Runnable {
            override fun run() {
                if (!running || transformer !== t) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    bar.progress = (i * 1000 + holder.progress * 10) / files.size
                    status.text = "${i + 1} of ${files.size}: $name  ${holder.progress}%"
                }
                ui.postDelayed(this, 500)
            }
        })
    }
}
