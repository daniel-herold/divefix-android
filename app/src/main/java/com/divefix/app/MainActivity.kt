package com.divefix.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
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
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val ids = arrayOf("red", "green", "blue", "bright", "contrast", "sat")
    private val names = arrayOf("Red restore", "Green", "Blue", "Brightness", "Contrast", "Saturation")
    private val mins = floatArrayOf(0f, -30f, -40f, -.2f, .8f, .5f)
    private val maxs = floatArrayOf(100f, 10f, 10f, .2f, 1.6f, 2f)
    private val steps = floatArrayOf(1f, 1f, 1f, .01f, .01f, .01f)
    private val tints = intArrayOf(0xFFF0704F.toInt(), 0xFF5CC27F.toInt(), 0xFF5B8DEF.toInt(), 0xFF4FB3C4.toInt(), 0xFF4FB3C4.toInt(), 0xFF4FB3C4.toInt())
    private val defaults = """{"Shallow 0-5 m":{"red":30,"green":0,"blue":-10,"bright":0,"contrast":1.05,"sat":1.1},"Medium 5-15 m":{"red":60,"green":-8,"blue":-20,"bright":0.02,"contrast":1.1,"sat":1.2},"Deep 15 m+":{"red":100,"green":-15,"blue":-30,"bright":0.04,"contrast":1.15,"sat":1.3}}"""
    private val exifTags = arrayOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH
    )

    private val v = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f)
    private val bars = arrayOfNulls<SeekBar>(6)
    private val vals = arrayOfNulls<TextView>(6)
    private var files = listOf<Uri>()
    private var cur = 0
    private var previewSeq = 0
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
    private lateinit var fileLabel: TextView
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

        preview = ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * .36f).toInt()))

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(Button(this).apply {
            text = "‹"; minWidth = 0; minimumWidth = (48 * dp).toInt()
            setOnClickListener { if (files.isNotEmpty()) { cur = (cur - 1 + files.size) % files.size; loadPreview() } }
        })
        fileLabel = TextView(this).apply {
            text = "Pick photos or videos to start"; setTextColor(0xFF8FA3A9.toInt()); isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE
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
                    MotionEvent.ACTION_DOWN -> { preview.colorFilter = null }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { applyPreview(); view.performClick() }
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
        for (i in 0 until 6) {
            val head = LinearLayout(this)
            head.addView(TextView(this).apply { text = names[i]; setTextColor(0xFFE3ECEE.toInt()); textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            vals[i] = TextView(this).apply { setTextColor(0xFF8FA3A9.toInt()) }
            head.addView(vals[i])
            bars[i] = SeekBar(this).apply {
                max = ((maxs[i] - mins[i]) / steps[i]).roundToInt()
                progressTintList = ColorStateList.valueOf(tints[i]); thumbTintList = ColorStateList.valueOf(tints[i])
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        if (user) { v[i] = mins[i] + p * steps[i]; showValue(i); applyPreview() }
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
                    for (i in 0 until 6) {
                        v[i] = o.optDouble(ids[i], if (i >= 4) 1.0 else 0.0).toFloat()
                        bars[i]!!.progress = ((v[i] - mins[i]) / steps[i]).roundToInt()
                        showValue(i)
                    }
                    applyPreview()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        refreshPresets(null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        files = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        cur = 0
        go.text = "Correct ${files.size} file${if (files.size == 1) "" else "s"}"
        go.isEnabled = files.isNotEmpty() && !running
        loadPreview()
    }

    override fun onDestroy() {
        transformer?.cancel()
        super.onDestroy()
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

    private fun applyPreview() {
        val m = matrix()
        preview.colorFilter = ColorMatrixColorFilter(floatArrayOf(
            m[0], m[1], m[2], 0f, m[3] * 255, m[4], m[5], m[6], 0f, m[7] * 255, m[8], m[9], m[10], 0f, m[11] * 255, 0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun isVideo(uri: Uri) = contentResolver.getType(uri)?.startsWith("video") == true

    private fun loadPreview() {
        if (files.isEmpty()) return
        val uri = files[cur]
        val seq = ++previewSeq
        fileLabel.text = "${cur + 1}/${files.size}  ${nameOf(uri)}"
        thread {
            val bmp = runCatching {
                if (isVideo(uri)) MediaMetadataRetriever().run {
                    setDataSource(this@MainActivity, uri)
                    val mid = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 500
                    val f = getScaledFrameAtTime(mid, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1600, 1600)
                    release(); f
                } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    d.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 1600))
                }
            }.getOrNull()
            runOnUiThread {
                if (seq != previewSeq) return@runOnUiThread
                preview.setImageBitmap(bmp); applyPreview()
                if (bmp == null) status.text = "Can't preview this file"
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
                val n = input.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                presets.put(n, JSONObject().apply { for (i in 0 until 6) put(ids[i], v[i].toDouble()) })
                prefs.edit().putString("presets", presets.toString()).apply()
                refreshPresets(n)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset() {
        val n = spinner.selectedItem as? String ?: return
        AlertDialog.Builder(this).setMessage("Delete preset \"$n\"?")
            .setPositiveButton("Delete") { _, _ ->
                presets.remove(n)
                prefs.edit().putString("presets", presets.toString()).apply()
                refreshPresets(null)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startBatch() {
        running = true; cancelled = false; saved = 0; failed = 0; lastError = null
        go.isEnabled = false; pick.isEnabled = false; stop.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        process(0, matrix())
    }

    private fun finishBatch() {
        running = false; transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        go.isEnabled = files.isNotEmpty(); pick.isEnabled = true; stop.visibility = View.GONE
        if (!cancelled) bar.progress = 1000
        status.text = (if (cancelled) "Stopped. " else "Done. ") + "$saved saved to DCIM/DiveFix" +
            (if (failed > 0) ", $failed failed" + (lastError?.let { " ($it)" } ?: "") else "")
    }

    private fun process(i: Int, m: FloatArray) {
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
                        for (x in 0 until w) {
                            val p = row[x]
                            val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val b = (p and 255) / 255f
                            row[x] = Color.rgb(
                                ((m[0] * r + m[1] * g + m[2] * b + m[3]).coerceIn(0f, 1f) * 255 + .5f).toInt(),
                                ((m[4] * r + m[5] * g + m[6] * b + m[7]).coerceIn(0f, 1f) * 255 + .5f).toInt(),
                                ((m[8] * r + m[9] * g + m[10] * b + m[11]).coerceIn(0f, 1f) * 255 + .5f).toInt()
                            )
                        }
                        bmp.setPixels(row, 0, w, 0, y, w, 1)
                    }
                    val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.jpg"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DiveFix"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })!!
                    contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bmp.recycle()
                    runCatching { // keep capture date, GPS and camera info; orientation is already applied to the pixels
                        val src = contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
                        contentResolver.openFileDescriptor(out, "rw")!!.use { fd ->
                            ExifInterface(fd.fileDescriptor).apply { exifTags.forEach { t -> src.getAttribute(t)?.let { setAttribute(t, it) } }; saveAttributes() }
                        }
                    }
                    contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                runOnUiThread {
                    if (result.isSuccess) saved++ else { failed++; lastError = result.exceptionOrNull()?.message }
                    process(i + 1, m)
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
        // Same matrix as the preview, as a column-major 4x4 for the GPU (offsets ride on alpha = 1).
        val gl = floatArrayOf(m[0], m[4], m[8], 0f, m[1], m[5], m[9], 0f, m[2], m[6], m[10], 0f, m[3], m[7], m[11], 1f)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(listOf(), listOf<Effect>(RgbMatrix { _, _ -> gl })))
            .build()
        val t = Transformer.Builder(this)
            .setEncoderFactory(DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    thread {
                        val result = runCatching {
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
                            process(i + 1, m)
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    tmp.delete(); failed++; lastError = exportException.message
                    process(i + 1, m)
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
