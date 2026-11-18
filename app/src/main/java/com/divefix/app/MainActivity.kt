package com.divefix.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
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
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.SingleColorLut
import androidx.media3.exoplayer.ExoPlayer
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
    private val ink = 0xFFE3ECEE.toInt()
    private val muted = 0xFF8FA3A9.toInt()

    /** Grid thumbnail: source pixels plus the last corrected rendering (ver = settings version it was rendered with). */
    class Thumb(val px: IntArray, val w: Int, val h: Int, val dur: Long, var fixed: Boolean, var out: Bitmap?, var ver: Int)

    /** Semi-transparent RGB histogram overlay. */
    inner class Histo(ctx: Context) : View(ctx) {
        var data: Array<IntArray>? = null
            set(value) { field = value; invalidate() }
        private val paints = intArrayOf(0x88FF5A4A.toInt(), 0x8850D070.toInt(), 0x885A8CFF.toInt()).map { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it } }
        private val path = Path()
        init { background = GradientDrawable().apply { setColor(0x59000000); cornerRadius = 6 * resources.displayMetrics.density } }
        override fun onDraw(c: Canvas) {
            val d = data ?: return
            val top = d.maxOf { h -> (1 until 63).maxOf { h[it] } }.coerceAtLeast(1)  // ignore clipped ends when scaling
            val w = width.toFloat(); val hh = height.toFloat()
            for (ch in 0..2) {
                path.reset(); path.moveTo(0f, hh)
                for (i in 0 until 64) path.lineTo(i * w / 63, hh - minOf(1f, d[ch][i].toFloat() / top) * hh * .92f)
                path.lineTo(w, hh); path.close()
                c.drawPath(path, paints[ch])
            }
        }
    }

    private val n = ids.size
    private val v = neutral.copyOf()
    private val bars = arrayOfNulls<SeekBar>(n)
    private val vals = arrayOfNulls<TextView>(n)
    private val dp by lazy { resources.displayMetrics.density }
    private var tablet = false
    private var files = listOf<Uri>()
    private var cur = 0
    private var single = false
    private var holding = false
    @Volatile private var renderSeq = 0
    @Volatile private var frameSeq = 0
    private var fileSeq = 0
    private val renderer = Executors.newSingleThreadExecutor()
    private val thumbRenderer = Executors.newSingleThreadExecutor()
    private val thumbLoader = Executors.newFixedThreadPool(3)
    private val frameExec = Executors.newSingleThreadExecutor()
    private var mmr: MediaMetadataRetriever? = null  // only touched on frameExec
    private val thumbs = object : LruCache<Uri, Thumb>(48 shl 20) { override fun sizeOf(key: Uri, value: Thumb) = value.px.size * 8 + 1 }
    private val loading = HashSet<Uri>()
    private var srcBmp: Bitmap? = null
    private var srcPx: IntArray? = null
    private var outBmp: Bitmap? = null
    private var srcHist: Array<IntArray>? = null
    private var outHist: Array<IntArray>? = null
    private var player: ExoPlayer? = null
    private var videoPos = 0L
    private var videoDur = 0L
    private var transformer: Transformer? = null
    private var running = false
    private var cancelled = false
    private var saved = 0
    private var failed = 0
    private var lastError: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("divefix", MODE_PRIVATE) }
    private lateinit var presets: JSONObject
    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var singleBox: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var image: ImageView
    private lateinit var histo: Histo
    private lateinit var badge: TextView
    private lateinit var removeBtn: Button
    private lateinit var fileLabel: TextView
    private lateinit var singleCtrls: List<View>
    private lateinit var videoRow: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var timeTv: TextView
    private lateinit var full: Dialog
    private lateinit var fullRoot: FrameLayout
    private lateinit var rotBox: FrameLayout
    private lateinit var fullImg: ImageView
    private lateinit var fullHisto: Histo
    private lateinit var fullHint: TextView
    private lateinit var spinner: Spinner
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var go: Button
    private lateinit var stop: Button

    private val applyFx = Runnable { player?.setVideoEffects(if (holding) listOf() else listOf<Effect>(lut(matrix(), v[6] / 100f))) }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.playWhenReady) return
            videoPos = p.currentPosition; seek.progress = videoPos.toInt(); timeTv.text = times()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val pad = (12 * dp).toInt()

        // ---------- grid ----------
        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(p: Int) = files[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val cell = convert as? FrameLayout ?: makeCell()
                cell.tag = files[p]
                bindCell(cell, files[p])
                return cell
            }
        }
        grid = GridView(this).apply {
            if (tablet) { numColumns = GridView.AUTO_FIT; columnWidth = (140 * dp).toInt() } else numColumns = 3
            horizontalSpacing = (2 * dp).toInt(); verticalSpacing = (2 * dp).toInt(); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> cur = pos; enterSingle() }
        }
        val emptyTv = TextView(this).apply {
            text = "Pick files, or share photos and videos to DiveFix from your gallery"; gravity = Gravity.CENTER; setTextColor(muted); setPadding(pad * 2, 0, pad * 2, 0)
        }
        grid.emptyView = emptyTv

        // ---------- single view ----------
        surface = SurfaceView(this).apply { visibility = View.GONE }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Preview, tap for full screen"; setOnClickListener { openFull() } }
        histo = Histo(this)
        badge = TextView(this).apply {
            text = "✓ Fixed copy exists"; setTextColor(Color.WHITE); textSize = 13f; setPadding(pad, pad / 3, pad, pad / 3); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xE01F7A8C.toInt()); cornerRadius = 6 * dp }
        }
        removeBtn = Button(this).apply { text = "✕ Remove"; alpha = .9f; setOnClickListener { removeCurrent() } }
        val hw = if (tablet) 200 else 112
        singleBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(histo, FrameLayout.LayoutParams((hw * dp).toInt(), (hw * dp / 2).toInt(), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, pad / 2, pad / 2) })
            addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(pad / 2, pad / 2, 0, 0) })
            addView(removeBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> player?.let { fitSurface(it.videoSize) } }
        }
        val content = FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyTv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(singleBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // ---------- navigation + video controls ----------
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prev = Button(this).apply { text = "‹"; minWidth = 0; minimumWidth = (48 * dp).toInt(); setOnClickListener { if (files.isNotEmpty()) { cur = (cur - 1 + files.size) % files.size; loadPreview() } } }
        val next = Button(this).apply { text = "›"; minWidth = 0; minimumWidth = (48 * dp).toInt(); setOnClickListener { if (files.isNotEmpty()) { cur = (cur + 1) % files.size; loadPreview() } } }
        fileLabel = TextView(this).apply { setTextColor(muted); isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val holdBtn = Button(this).apply {
            text = "Original"
            setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { hold(true) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold(false); view.performClick() }
                }
                true
            }
        }
        val gridBtn = Button(this).apply { text = "▦"; minWidth = 0; minimumWidth = (48 * dp).toInt(); setOnClickListener { exitSingle() } }
        nav.addView(prev); nav.addView(fileLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); nav.addView(next); nav.addView(holdBtn); nav.addView(gridBtn)
        singleCtrls = listOf(prev, next, holdBtn, gridBtn)

        playBtn = Button(this).apply { text = "▶"; minWidth = 0; minimumWidth = (48 * dp).toInt(); setOnClickListener { if (player?.playWhenReady == true) pause() else play() } }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(0xFF4FB3C4.toInt()); thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (!user) return
                    videoPos = p.toLong(); timeTv.text = times()
                    if (player?.playWhenReady == true) player?.seekTo(videoPos) else loadFrame(false)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { if (player?.playWhenReady != true) loadFrame(true) }
            })
        }
        timeTv = TextView(this).apply { setTextColor(muted); textSize = 13f }
        videoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(playBtn); addView(seek, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f)); addView(timeTv)
        }

        // ---------- presets + sliders ----------
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val presetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        spinner = Spinner(this)
        presetRow.addView(spinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        presetRow.addView(Button(this).apply { text = "Save"; setOnClickListener { savePreset() } })
        presetRow.addView(Button(this).apply { text = "Delete"; setOnClickListener { deletePreset() } })
        box.addView(presetRow)
        for (i in 0 until n) {
            val label = TextView(this).apply { text = names[i]; setTextColor(ink); textSize = if (tablet) 13f else 15f }
            vals[i] = TextView(this).apply { setTextColor(muted); textSize = if (tablet) 12f else 14f; gravity = Gravity.END }
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
            if (tablet) {  // compact: label, slider and value on one line
                box.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label, LinearLayout.LayoutParams((100 * dp).toInt(), WRAP_CONTENT))
                    addView(bars[i], LinearLayout.LayoutParams(0, (32 * dp).toInt(), 1f))
                    addView(vals[i], LinearLayout.LayoutParams((40 * dp).toInt(), WRAP_CONTENT))
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 3 })
            } else {
                box.addView(LinearLayout(this).apply {
                    addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(vals[i])
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
                box.addView(bars[i], LinearLayout.LayoutParams(MATCH_PARENT, (36 * dp).toInt()))
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }

        // ---------- batch controls ----------
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(0xFF4FB3C4.toInt()) }
        status = TextView(this).apply { setTextColor(muted); text = "Corrected files go to DCIM/DiveFix" }
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
        stop = Button(this).apply { text = "Cancel"; visibility = View.GONE; setOnClickListener { cancelled = true; transformer?.cancel(); finishBatch() } }
        actions.addView(pick); actions.addView(go, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); actions.addView(stop)

        // ---------- layout: phone stacks everything, tablet puts a compact control panel beside a big grid ----------
        val root: LinearLayout
        if (tablet) {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)); addView(nav); addView(videoRow)
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)); addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(status); addView(actions)
            }
            root = LinearLayout(this).apply {
                addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(right, LinearLayout.LayoutParams((340 * dp).toInt(), MATCH_PARENT).apply { leftMargin = pad })
            }
        } else {
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content, LinearLayout.LayoutParams(MATCH_PARENT, resources.displayMetrics.widthPixels - 2 * pad))  // square: 3x3 grid
                addView(nav); addView(videoRow)
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(status); addView(actions)
            }
        }
        root.apply { setBackgroundColor(0xFF0F1B20.toInt()); setPadding(pad, pad, pad, pad); fitsSystemWindows = true }
        setContentView(root)

        // ---------- full screen viewer: landscape images are turned to fill a portrait screen ----------
        fullImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        fullHisto = Histo(this)
        fullHint = TextView(this).apply {
            text = "Hold anywhere for original · Back to return"; setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(pad, pad / 2, pad, pad / 2)
        }
        rotBox = FrameLayout(this).apply {
            addView(fullImg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fullHisto, FrameLayout.LayoutParams((hw * dp).toInt(), (hw * dp / 2).toInt(), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, pad, pad) })
            addView(fullHint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 4 * pad })
        }
        fullRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(rotBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) post { orientFull() } }
            setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { fullHint.visibility = View.GONE; hold(true) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold(false); view.performClick() }
                }
                true
            }
        }
        full = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { setContentView(fullRoot) }

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
        showMode()
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

    @Deprecated("Back in the single view returns to the grid")
    override fun onBackPressed() {
        if (single) exitSingle() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        if (player?.playWhenReady == true) pause()
        super.onPause()
    }

    override fun onDestroy() {
        transformer?.cancel()
        releasePlayer()
        frameExec.execute { mmr?.release(); mmr = null }
        listOf(renderer, thumbRenderer, thumbLoader, frameExec).forEach { it.shutdown() }
        super.onDestroy()
    }

    // ---------- selection & modes ----------

    private fun setFiles(list: List<Uri>) {
        if (running) { status.text = "Wait for the current batch to finish first"; return }
        files = list
        cur = 0
        if (single) exitSingle()
        updateCount()
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun updateCount() {
        go.text = "Correct ${files.size} file${if (files.size == 1) "" else "s"}"
        go.isEnabled = files.isNotEmpty() && !running
        removeBtn.visibility = if (files.isNotEmpty() && !running) View.VISIBLE else View.GONE
        if (!single) fileLabel.text = if (files.isEmpty()) "" else "${files.size} file${if (files.size == 1) "" else "s"} · tap one to enlarge"
    }

    private fun showMode() {
        singleBox.visibility = if (single) View.VISIBLE else View.GONE
        singleCtrls.forEach { it.visibility = if (single) View.VISIBLE else View.GONE }
        if (!single) videoRow.visibility = View.GONE
        updateCount()
    }

    private fun enterSingle() {
        single = true
        showMode()
        loadPreview()
    }

    private fun exitSingle() {
        releasePlayer()
        fileSeq++; frameSeq++
        frameExec.execute { mmr?.release(); mmr = null }
        single = false
        showMode()
        adapter.notifyDataSetChanged()
        if (files.isNotEmpty()) grid.smoothScrollToPosition(cur)
    }

    private fun removeCurrent() {
        if (running || files.isEmpty()) return
        files = files.filterIndexed { k, _ -> k != cur }
        cur = cur.coerceAtMost(files.size - 1).coerceAtLeast(0)
        updateCount()
        adapter.notifyDataSetChanged()
        if (files.isEmpty()) exitSingle() else loadPreview()
    }

    private fun showValue(i: Int) {
        vals[i]!!.text = if (steps[i] >= 1f) "%.0f".format(v[i]) else "%.2f".format(v[i])
    }

    // ---------- colour pipeline ----------

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

    // The one colour function used by previews, thumbnails, photo export and (baked into a LUT) video.
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

    private fun lut(m: FloatArray, pop: Float) =
        SingleColorLut.createFromCube(Array(33) { r -> Array(33) { g -> IntArray(33) { b -> fx(r / 32f, g / 32f, b / 32f, m, pop) } } })

    private fun hist(px: IntArray) = Array(3) { IntArray(64) }.also { h ->
        for (p in px) { h[0][(p shr 18) and 63]++; h[1][(p shr 10) and 63]++; h[2][(p shr 2) and 63]++ }
    }

    private fun render() {
        val seq = ++renderSeq
        val m = matrix(); val pop = v[6] / 100f
        if (player != null) { ui.removeCallbacks(applyFx); ui.postDelayed(applyFx, 150) }
        if (!single) { renderThumbs((0 until grid.childCount).mapNotNull { grid.getChildAt(it).tag as? Uri }, m, pop, seq); return }
        val px = srcPx ?: return
        val src = srcBmp ?: return
        renderer.execute {
            if (seq != renderSeq) return@execute
            val out = IntArray(px.size)
            for (k in px.indices) { val p = px[k]; out[k] = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop) }
            val bmp = Bitmap.createBitmap(out, src.width, src.height, Bitmap.Config.ARGB_8888)
            val h = hist(out)
            runOnUiThread { if (seq == renderSeq) { outBmp = bmp; outHist = h; display() } }
        }
    }

    // Shows corrected or (while holding) original image and histogram, in the single view and full screen.
    private fun display() {
        val b = if (holding) srcBmp else outBmp ?: srcBmp
        val h = if (holding) srcHist else outHist ?: srcHist
        image.setImageBitmap(b); fullImg.setImageBitmap(b)
        histo.data = h; fullHisto.data = h
    }

    private fun hold(on: Boolean) {
        holding = on
        display()
        if (player != null) { ui.removeCallbacks(applyFx); applyFx.run() }
    }

    // ---------- grid thumbnails ----------

    private fun makeCell(): FrameLayout = object : FrameLayout(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }.apply {
        setBackgroundColor(0xFF16252B.toInt())
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(TextView(context).apply {
            text = "✓"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE01F7A8C.toInt()) }
        }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.TOP or Gravity.END).apply { setMargins(0, (4 * dp).toInt(), (4 * dp).toInt(), 0) })
        addView(TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 11f; setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding((5 * dp).toInt(), 0, 0, (3 * dp).toInt())
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }

    private fun cellFor(uri: Uri) = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.tag == uri } as? FrameLayout

    private fun bindCell(cell: FrameLayout, uri: Uri) {
        val img = cell.getChildAt(0) as ImageView
        val fixedTv = cell.getChildAt(1)
        val durTv = cell.getChildAt(2) as TextView
        val t = thumbs.get(uri)
        if (t == null) {
            img.setImageBitmap(null); fixedTv.visibility = View.GONE; durTv.visibility = View.GONE
            if (loading.add(uri)) {
                val seq = renderSeq; val m = matrix(); val pop = v[6] / 100f
                thumbLoader.execute {
                    val th = loadThumb(uri, m, pop, seq)
                    runOnUiThread { loading.remove(uri); thumbs.put(uri, th); cellFor(uri)?.let { bindCell(it, uri) } }
                }
            }
            return
        }
        img.setImageBitmap(t.out)
        fixedTv.visibility = if (t.fixed) View.VISIBLE else View.GONE
        durTv.visibility = if (t.dur >= 0) View.VISIBLE else View.GONE
        durTv.text = "▶ %d:%02d".format(t.dur / 60000, t.dur / 1000 % 60)
        if (t.ver != renderSeq) renderThumbs(listOf(uri), matrix(), v[6] / 100f, renderSeq)
    }

    private fun loadThumb(uri: Uri, m: FloatArray, pop: Float, seq: Int): Thumb {
        val video = isVideo(uri)
        val bmp = (runCatching { contentResolver.loadThumbnail(uri, Size(384, 384), null) }.getOrNull() ?: runCatching {
            if (video) MediaMetadataRetriever().run {
                setDataSource(this@MainActivity, uri); val f = getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 384); release(); f
            } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = 384f / maxOf(info.size.width, info.size.height)
                if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }.getOrNull())?.copy(Bitmap.Config.ARGB_8888, false)
        val dur = if (!video) -1L else runCatching {
            MediaMetadataRetriever().run { setDataSource(this@MainActivity, uri); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); release(); d }
        }.getOrDefault(0L)
        val fixed = runCatching { existing(nameOf(uri).substringBeforeLast('.') + "_fix", video) != null }.getOrDefault(false)
        if (bmp == null) return Thumb(IntArray(0), 0, 0, dur, fixed, null, seq)
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val out = IntArray(px.size)
        for (k in px.indices) { val p = px[k]; out[k] = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop) }
        return Thumb(px, bmp.width, bmp.height, dur, fixed, Bitmap.createBitmap(out, bmp.width, bmp.height, Bitmap.Config.ARGB_8888), seq)
    }

    private fun renderThumbs(list: List<Uri>, m: FloatArray, pop: Float, seq: Int) {
        thumbRenderer.execute {
            for (uri in list) {
                if (seq != renderSeq) return@execute
                val t = thumbs.get(uri) ?: continue
                if (t.ver == seq || t.w == 0) continue
                val out = IntArray(t.px.size)
                for (k in out.indices) { val p = t.px[k]; out[k] = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop) }
                val bmp = Bitmap.createBitmap(out, t.w, t.h, Bitmap.Config.ARGB_8888)
                runOnUiThread { if (seq == renderSeq) { t.out = bmp; t.ver = seq; (cellFor(uri)?.getChildAt(0) as? ImageView)?.setImageBitmap(bmp) } }
            }
        }
    }

    // ---------- single view: photo or video frame ----------

    private fun loadPreview() {
        if (files.isEmpty()) return
        releasePlayer()
        val uri = files[cur]
        val video = isVideo(uri)
        val fs = ++fileSeq
        val seq = ++frameSeq
        fileLabel.text = "${cur + 1}/${files.size}  ${nameOf(uri)}"
        badge.visibility = View.GONE
        refreshBadge()
        videoRow.visibility = if (video) View.VISIBLE else View.GONE
        srcBmp = null; srcPx = null; outBmp = null; srcHist = null; outHist = null
        image.setImageBitmap(null); histo.data = null
        videoPos = 0; videoDur = 0; seek.progress = 0; timeTv.text = ""
        if (video) {
            frameExec.execute {
                mmr?.release(); mmr = null
                if (fs != fileSeq) return@execute
                val r = runCatching { MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) } }.getOrNull()
                mmr = r
                val d = r?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                runOnUiThread { if (fs == fileSeq) { videoDur = d; seek.max = d.toInt(); timeTv.text = times(); loadFrame(true) } }
            }
        } else {
            frameExec.execute {
                val bmp = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val s = 1440f / maxOf(info.size.width, info.size.height)
                        if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    }.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                deliver(seq, bmp)
            }
        }
    }

    private fun loadFrame(exact: Boolean) {
        val seq = ++frameSeq
        val pos = videoPos
        frameExec.execute {
            if (seq != frameSeq) return@execute
            val bmp = runCatching {
                val r = mmr!!
                (r.getScaledFrameAtTime(pos * 1000, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
                    ?: r.getScaledFrameAtTime(pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440))!!.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return@execute
            deliver(seq, bmp)
        }
    }

    // Runs on frameExec: extracts pixels + histogram, then hands the new source frame to the UI.
    private fun deliver(seq: Int, bmp: Bitmap?) {
        val px = bmp?.let { IntArray(it.width * it.height).also { a -> it.getPixels(a, 0, it.width, 0, 0, it.width, it.height) } }
        val h = px?.let { hist(it) }
        runOnUiThread {
            if (seq != frameSeq) return@runOnUiThread
            if (bmp == null) { status.text = "Can't preview this file"; return@runOnUiThread }
            srcBmp = bmp; srcPx = px; srcHist = h; outBmp = null; outHist = null
            if (player?.playWhenReady != true) { image.visibility = View.VISIBLE; histo.visibility = View.VISIBLE }
            if (full.isShowing) orientFull()
            render()
        }
    }

    private fun times() = "%d:%02d / %d:%02d".format(videoPos / 60000, videoPos / 1000 % 60, videoDur / 60000, videoDur / 1000 % 60)

    // ---------- video playback (live correction through the same LUT as the export) ----------

    private fun play() {
        if (running) { status.text = "Playback is off while a batch is running"; return }
        if (videoDur > 0 && videoPos >= videoDur - 200) videoPos = 0
        var p = player
        if (p == null) {
            p = ExoPlayer.Builder(this).build()
            player = p
            p.setVideoSurfaceView(surface)
            p.setVideoEffects(listOf<Effect>(lut(matrix(), v[6] / 100f)))
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { if (player?.playWhenReady == true) image.visibility = View.INVISIBLE }
                override fun onVideoSizeChanged(videoSize: VideoSize) { fitSurface(videoSize) }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playBtn.text = if (isPlaying) "❚❚" else "▶" }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) pause() }
                override fun onPlayerError(error: PlaybackException) { status.text = "Playback failed: ${error.errorCodeName}"; releasePlayer(); loadFrame(true) }
            })
            p.setMediaItem(MediaItem.fromUri(files[cur]))
            p.prepare()
            if (holding) applyFx.run()
        } else image.visibility = View.INVISIBLE
        surface.visibility = View.VISIBLE
        histo.visibility = View.GONE
        p.seekTo(videoPos)
        p.play()
        ui.removeCallbacks(ticker); ui.post(ticker)
    }

    private fun pause() {
        val p = player ?: return
        p.pause()
        videoPos = p.currentPosition.coerceAtMost(videoDur)
        seek.progress = videoPos.toInt(); timeTv.text = times()
        loadFrame(true)  // the paused frame gets the exact CPU pipeline, histogram and full-screen view
    }

    private fun fitSurface(vs: VideoSize) {
        val cw = singleBox.width; val ch = singleBox.height
        if (vs.width == 0 || vs.height == 0 || cw == 0 || ch == 0) return
        val vw = vs.width * vs.pixelWidthHeightRatio; val vh = vs.height.toFloat()
        val s = minOf(cw / vw, ch / vh)
        surface.layoutParams = FrameLayout.LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
    }

    private fun releasePlayer() {
        ui.removeCallbacks(ticker); ui.removeCallbacks(applyFx)
        player?.release(); player = null
        surface.visibility = View.GONE
        playBtn.text = "▶"
        image.visibility = View.VISIBLE; histo.visibility = View.VISIBLE
    }

    // ---------- full screen ----------

    private fun openFull() {
        if (srcBmp == null) return
        if (player?.playWhenReady == true) pause()
        display()
        fullHint.visibility = View.VISIBLE
        full.show()
        fullRoot.post { orientFull() }
        ui.postDelayed({ fullHint.visibility = View.GONE }, 3000)
    }

    // Turn the image 90° when its orientation doesn't match the screen's, so it fills the screen without rotating the app.
    private fun orientFull() {
        val w = fullRoot.width; val h = fullRoot.height; val b = srcBmp ?: return
        if (w == 0 || h == 0) return
        val rotate = (b.width > b.height) != (w > h) && b.width != b.height
        rotBox.layoutParams = FrameLayout.LayoutParams(if (rotate) h else w, if (rotate) w else h, Gravity.CENTER)
        rotBox.rotation = if (rotate) 90f else 0f
    }

    // ---------- helpers ----------

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun isVideo(uri: Uri) = contentResolver.getType(uri)?.startsWith("video") == true

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
        val fs = fileSeq
        thread {
            val has = runCatching { existing(nameOf(uri).substringBeforeLast('.') + "_fix", isVideo(uri)) != null }.getOrDefault(false)
            runOnUiThread { if (fs == fileSeq) badge.visibility = if (has) View.VISIBLE else View.GONE }
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

    // ---------- batch export ----------

    private fun startBatch() {
        releasePlayer()
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
        val snap = thumbs.snapshot()  // refresh the ✓ marks in the grid
        thread {
            snap.forEach { (u, t) -> t.fixed = runCatching { existing(nameOf(u).substringBeforeLast('.') + "_fix", t.dur >= 0) != null }.getOrDefault(t.fixed) }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
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
