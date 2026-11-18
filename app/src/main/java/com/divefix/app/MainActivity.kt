package com.divefix.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import android.app.Dialog
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import android.graphics.drawable.ColorDrawable
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.PopupWindow
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    // Each adjustment is stored as the value the colour maths uses (v) and shown as -100..+100 or 0..100, 0 = no change.
    private val ids = arrayOf("red", "green", "blue", "bright", "contrast", "sat", "pop")
    private val names = arrayOf("Red restore", "Green", "Blue", "Brightness", "Contrast", "Saturation", "Vibrance")
    private val base = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 0f)
    private val scale = floatArrayOf(1f, .3f, .4f, .002f, .004f, .01f, 1f)
    private val dmin = floatArrayOf(0f, -100f, -100f, -100f, -100f, -100f, 0f)
    private val dots = intArrayOf(0xFFE5604D.toInt(), 0xFF5CC27F.toInt(), 0xFF5B8DEF.toInt(), 0, 0, 0, 0)
    // Shallow ~0-5 m, Medium ~5-15 m, Deep ~15 m+. Red is absorbed first, so it needs the most help, then blue and green are
    // pulled back; deeper water is darker and hazier, so brightness, contrast and colour get a little more too.
    private val defaults = """{"Shallow":{"red":45,"green":-3,"blue":-8,"bright":0,"contrast":1.08,"sat":1.05,"pop":15},"Medium":{"red":80,"green":-6,"blue":-12,"bright":0.02,"contrast":1.12,"sat":1.1,"pop":25},"Deep":{"red":100,"green":-9,"blue":-20,"bright":0.04,"contrast":1.16,"sat":1.15,"pop":35}}"""
    private val icons = intArrayOf(0, 0, 0, R.drawable.ic_brightness, R.drawable.ic_contrast, R.drawable.ic_saturation, R.drawable.ic_vibrance)
    private val hints = arrayOf<String?>(null, null, null,
        "Lightens or darkens the whole picture evenly. Deeper dives are darker, so raise it a little there.",
        "Pushes darks and lights apart. Water haze makes dive shots look flat; more contrast gives them depth.",
        "Makes every colour stronger or weaker by the same amount. Use it sparingly, too much looks artificial.",
        "Strengthens muted colours a lot and already-strong ones barely, so blues and corals come alive without neon fish.")
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

    /** Grid thumbnail: source pixels plus the last corrected rendering (ver = settings version it was rendered with). */
    class Thumb(val px: IntArray, val w: Int, val h: Int, val dur: Long, val outName: String, var phone: Boolean, var out: Bitmap?, var key: Int)

    /** Individual settings for one file: slider values plus an optional gradient line (x0, y0, x1, y1 in 0..1 image coordinates). */
    class Edit(val v: FloatArray, var line: FloatArray? = null)

    private val n = ids.size
    private val g = base.copyOf()  // settings for all files
    private var v = g  // what the sliders edit: g in the grid, the shown file's own settings in the single view
    private val edits = HashMap<Uri, Edit>()  // individually edited files
    private val videoOf = HashMap<Uri, Boolean>()
    private val sliders = arrayOfNulls<Slider>(n)
    private val vals = arrayOfNulls<TextView>(n)
    private val dp by lazy { resources.displayMetrics.density }
    private var tablet = false
    private var files = listOf<Uri>()
    private var cur = 0
    private var single = false
    private var holding = false
    private var applyingPreset = false
    private var fileTitle = ""
    private var fileSub = ""
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
    private var dest = 0  // 0 = phone, 1 = camera card, 2 = both
    private var pendingDest = 0
    private var batchDest = 0
    @Volatile private var cardTree: Uri? = null
    @Volatile private var cardDir: Uri? = null  // the DiveFix folder on the card
    @Volatile private var cardFiles = ConcurrentHashMap<String, String>()  // file name -> document id in that folder
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("divefix", MODE_PRIVATE) }
    private lateinit var presets: JSONObject

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var singleBox: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var image: ImageView
    private lateinit var originalTag: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var histo: Histo
    private lateinit var overlay: LineOverlay
    private lateinit var gradientBtn: MaterialButton
    private lateinit var drawBar: View
    private lateinit var drawHint: TextView
    private lateinit var gradientGroup: LinearLayout
    private lateinit var gradientSave: View
    private lateinit var gradientDiscard: View
    private var drawMode = false
    private lateinit var addMore: View
    private lateinit var emptyBox: View
    private lateinit var modeBar: LinearLayout
    private lateinit var modeIcon: ImageView
    private lateinit var modeTitle: TextView
    private lateinit var modeSub: TextView
    private lateinit var modeReset: View
    private lateinit var videoRow: View
    private lateinit var playBtn: MaterialButton
    private lateinit var timeline: Slider
    private lateinit var timeTv: TextView
    private lateinit var chips: ChipGroup
    private lateinit var bottomBar: View
    private lateinit var destGroup: MaterialButtonToggleGroup
    private lateinit var go: Button
    private lateinit var idleRow: View
    private lateinit var runRow: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var runText: TextView
    private lateinit var full: Dialog
    private lateinit var fullRoot: FrameLayout
    private lateinit var rotBox: FrameLayout
    private lateinit var fullImg: ImageView
    private lateinit var fullHisto: Histo
    private lateinit var fullHint: TextView
    private lateinit var fullOriginal: TextView
    private val destIds = intArrayOf(R.id.destPhone, R.id.destCard, R.id.destBoth)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) addFiles(it) }
    private val cardPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { onCardChosen(it) }
    private val backToGrid = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = exitSingle() }
    private val applyFx = Runnable { player?.setVideoEffects(if (holding) listOf() else listOf<Effect>(lut(matrix(), v[6] / 100f))) }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.playWhenReady) return
            videoPos = p.currentPosition
            timeline.value = videoPos.toFloat().coerceIn(timeline.valueFrom, timeline.valueTo); timeTv.text = times()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onBackPressedDispatcher.addCallback(this, backToGrid)
        if (!tablet) findViewById<View>(R.id.media).layoutParams.height = resources.displayMetrics.widthPixels  // square: 3 x 3 grid

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> chooseCard()
                3 -> removeCurrent()
                4 -> setFiles(listOf())
            }
            true
        }
        toolbar.setNavigationOnClickListener { exitSingle() }
        findViewById<View>(R.id.emptyAdd).setOnClickListener { filePicker.launch(arrayOf("image/*", "video/*")) }
        emptyBox = findViewById(R.id.empty)
        addMore = findViewById<View>(R.id.addMore).apply { setOnClickListener { filePicker.launch(arrayOf("image/*", "video/*")) } }

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
        grid = findViewById<GridView>(R.id.grid).apply {
            if (tablet) { numColumns = GridView.AUTO_FIT; columnWidth = (150 * dp).toInt() } else numColumns = 3
            selector = GradientDrawable()  // no legacy highlight; cells have their own ripple
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> cur = pos; enterSingle() }
        }

        // ---------- single view ----------
        singleBox = findViewById(R.id.single)
        surface = findViewById(R.id.surface)
        image = findViewById<ImageView>(R.id.image).apply { setOnClickListener { openFull() } }
        originalTag = findViewById(R.id.originalTag)
        histo = findViewById(R.id.histo)
        overlay = findViewById<LineOverlay>(R.id.lineOverlay).apply {
            onLine = { l -> adopt(); edits[files[cur]]?.line = l; render(); updateMode(); updateDrawBar() }
        }
        drawBar = findViewById(R.id.drawBar)
        drawHint = findViewById(R.id.drawHint)
        gradientGroup = findViewById(R.id.gradientGroup)
        gradientBtn = findViewById<MaterialButton>(R.id.gradientBtn).apply { setOnClickListener { setDrawMode(!drawMode) } }  // tapping again keeps the line
        gradientSave = findViewById<View>(R.id.gradientSave).apply { setOnClickListener { setDrawMode(false) } }
        gradientDiscard = findViewById<View>(R.id.gradientDiscard).apply {
            setOnClickListener { edits[files[cur]]?.line = null; render(); updateMode(); setDrawMode(false) }
        }
        prevBtn = findViewById<Button>(R.id.prev).apply { setOnClickListener { if (files.isNotEmpty()) { cur = (cur - 1 + files.size) % files.size; loadPreview() } } }
        nextBtn = findViewById<Button>(R.id.next).apply { setOnClickListener { if (files.isNotEmpty()) { cur = (cur + 1) % files.size; loadPreview() } } }
        findViewById<View>(R.id.compare).setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { hold(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold(false); view.performClick() }
            }
            false  // let the button show its pressed state
        }
        singleBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> player?.let { fitSurface(it.videoSize) }; syncOverlay() }
        videoRow = findViewById(R.id.videoRow)
        playBtn = findViewById<MaterialButton>(R.id.play).apply { setOnClickListener { if (player?.playWhenReady == true) pause() else play() } }
        timeTv = findViewById(R.id.time)
        timeline = findViewById<Slider>(R.id.timeline).apply {
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                videoPos = value.toLong(); timeTv.text = times()
                if (player?.playWhenReady == true) player?.seekTo(videoPos) else loadFrame(false)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) { if (player?.playWhenReady != true) loadFrame(true) }
            })
        }

        // ---------- adjustments: one compact row per slider ----------
        val box = findViewById<LinearLayout>(R.id.sliders)
        val muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        for (i in 0 until n) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    if (dots[i] != 0) addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dots[i]) } },
                        FrameLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt(), Gravity.CENTER))
                    else addView(ImageView(context).apply { setImageResource(icons[i]); imageTintList = ColorStateList.valueOf(muted) },
                        FrameLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt(), Gravity.CENTER))
                }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
                addView(TextView(context).apply { text = names[i]; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) })
                hints[i]?.let { hint ->
                    background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { val d = getDrawable(0); recycle(); d }
                    contentDescription = "${names[i]}. $hint"
                    setOnClickListener { showHint(this, hint) }
                }
            }
            row.addView(label, LinearLayout.LayoutParams((112 * dp).toInt(), (40 * dp).toInt()))
            val s = Slider(this).apply {
                valueFrom = dmin[i]; valueTo = 100f; stepSize = 1f; value = 0f
                labelBehavior = LabelFormatter.LABEL_GONE; isTickVisible = false; contentDescription = names[i]
                addOnChangeListener { _, value, fromUser -> if (fromUser) { v[i] = base[i] + value * scale[i]; showValue(i); userChanged() } }
                val reset = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean { v[i] = base[i]; setSlider(i); userChanged(); return true }
                })
                setOnTouchListener { _, e -> reset.onTouchEvent(e); false }  // double-tap resets to neutral
            }
            sliders[i] = s
            row.addView(s, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            vals[i] = TextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(muted)
                gravity = Gravity.END; fontFeatureSettings = "tnum"
            }
            row.addView(vals[i], LinearLayout.LayoutParams((36 * dp).toInt(), WRAP_CONTENT))
            box.addView(row)
            setSlider(i)
        }

        // ---------- which files the sliders change ----------
        modeBar = findViewById(R.id.modeBar)
        modeIcon = findViewById(R.id.modeIcon)
        modeTitle = findViewById(R.id.modeTitle)
        modeSub = findViewById(R.id.modeSub)
        modeReset = findViewById<View>(R.id.modeReset).apply { setOnClickListener { resetIndividual() } }

        // ---------- presets ----------
        presets = JSONObject(prefs.getString("presets", null) ?: defaults)
        if (prefs.getInt("presetsVersion", 1) < 2) {  // replace the first-generation default presets, keep the user's own
            val old = setOf("Shallow 0-5 m", "Medium 5-15 m", "Deep 15 m+")
            val merged = JSONObject(defaults)
            presets.keys().asSequence().toList().filter { it !in old && !merged.has(it) }.forEach { merged.put(it, presets.get(it)) }
            presets = merged
            prefs.edit().putString("presets", presets.toString()).putInt("presetsVersion", 2).apply()
        }
        chips = findViewById(R.id.presets)
        chips.setOnCheckedStateChangeListener { g, checked ->
            if (applyingPreset || checked.isEmpty()) return@setOnCheckedStateChangeListener
            applyPreset(g.findViewById<Chip>(checked[0]).text.toString())
        }
        buildChips(presets.keys().asSequence().firstOrNull())
        presets.keys().asSequence().firstOrNull()?.let { applyPreset(it) }

        // ---------- bottom bar ----------
        bottomBar = findViewById(R.id.bottomBar)
        idleRow = findViewById(R.id.idleRow)
        runRow = findViewById(R.id.runRow)
        progress = findViewById(R.id.progress)
        runText = findViewById(R.id.runText)
        go = findViewById<Button>(R.id.go).apply { setOnClickListener { startBatch() } }
        findViewById<View>(R.id.cancel).setOnClickListener { cancelled = true; transformer?.cancel(); finishBatch() }
        dest = prefs.getInt("dest", 0)
        destGroup = findViewById(R.id.dest)
        destGroup.check(destIds[dest])
        destGroup.addOnButtonCheckedListener { _, id, checked ->
            val pos = destIds.indexOf(id)
            if (!checked || pos == dest) return@addOnButtonCheckedListener
            if (pos > 0 && prefs.getString("cardTree", null) == null) { pendingDest = pos; chooseCard() } else setDest(pos)
        }

        // ---------- full screen: landscape images are turned to fill a portrait screen ----------
        fullImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        fullHisto = Histo(this)
        fullHint = pillText("Hold anywhere to see the original · Back to return")
        fullOriginal = pillText("Original").apply { visibility = View.GONE }
        rotBox = FrameLayout(this).apply {
            addView(fullImg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fullOriginal, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (24 * dp).toInt() })
            addView(fullHisto, FrameLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.histo_w), resources.getDimensionPixelSize(R.dimen.histo_h), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (16 * dp).toInt(), (16 * dp).toInt()) })
            addView(fullHint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (88 * dp).toInt() })
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
        full = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { setContentView(fullRoot); setOnDismissListener { hold(false) } }

        showMode()
        updateMode()
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
        if (list.isNotEmpty()) addFiles(list)
    }

    override fun onResume() {
        super.onResume()
        thread { openCard(false); runOnUiThread { adapter.notifyDataSetChanged(); refreshFileInfo() } }  // the camera may have been plugged in meanwhile
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

    private fun pillText(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.pill)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setTextColor(Color.WHITE)
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
    }

    // Small explanation bubble under a slider label; tap anywhere to close.
    private fun showHint(anchor: View, text: String) {
        val bubble = TextView(this).apply {
            this.text = text; maxWidth = (280 * dp).toInt()
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface))
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { setColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHighest)); cornerRadius = 12 * dp }
        }
        val pop = PopupWindow(bubble, WRAP_CONTENT, WRAP_CONTENT, true).apply { elevation = 6 * dp; isOutsideTouchable = true; setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        pop.showAsDropDown(anchor, 0, (2 * dp).toInt())
        ui.postDelayed({ if (pop.isShowing) pop.dismiss() }, 6000)
    }

    private fun say(msg: String, action: String? = null, fn: (() -> Unit)? = null) {
        Snackbar.make(root, msg, if (action != null) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG).apply {
            anchorView = bottomBar
            if (action != null && fn != null) setAction(action) { fn() }
        }.show()
    }

    // ---------- selection & modes ----------

    // Replaces the selection (used for "Clear selection").
    private fun setFiles(list: List<Uri>) {
        if (running) { say("Wait for the current batch to finish first"); return }
        files = listOf(); edits.clear()
        addFiles(list)
    }

    // Adds files to the selection, skipping ones that are already in it.
    private fun addFiles(list: List<Uri>) {
        if (running) { say("Wait for the current batch to finish first"); return }
        val known = files.toHashSet()
        val added = list.filter { known.add(it) }
        added.forEach { videoOf[it] = isVideo(it) }
        val wasEmpty = files.isEmpty()
        files = files + added
        if (single) exitSingle()
        updateCount()
        updateMode()
        adapter.notifyDataSetChanged()
        if (wasEmpty) grid.setSelection(0) else if (added.isNotEmpty()) grid.smoothScrollToPosition(files.size - 1)
        if (added.size < list.size) say("${list.size - added.size} already selected, skipped")
    }

    private fun updateCount() {
        go.text = if (files.isEmpty()) "Correct" else "Correct ${files.size}"
        go.isEnabled = files.isNotEmpty() && !running
        emptyBox.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        addMore.visibility = if (files.isNotEmpty() && !single && !running) View.VISIBLE else View.GONE
        updateToolbar()
    }

    private fun counts(): String {
        val videos = files.count { videoOf[it] == true }; val photos = files.size - videos
        return "$photos photo${if (photos == 1) "" else "s"} · $videos video${if (videos == 1) "" else "s"}"
    }

    private fun updateToolbar() {
        val menu = toolbar.menu
        menu.clear()
        if (single) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.navigationContentDescription = "Back to grid"
            toolbar.title = fileTitle; toolbar.subtitle = fileSub
            menu.add(0, 3, 0, "Remove").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        } else {
            toolbar.navigationIcon = null
            toolbar.title = "DiveFix"; toolbar.subtitle = if (files.isEmpty()) null else counts()
            if (files.isNotEmpty()) menu.add(0, 4, 2, "Clear selection")
        }
        menu.add(0, 2, 1, "Camera card folder…")
        for (k in 0 until menu.size()) menu.getItem(k).isEnabled = !running || menu.getItem(k).itemId == 2
    }

    private fun showMode() {
        singleBox.visibility = if (single) View.VISIBLE else View.GONE
        backToGrid.isEnabled = single
        updateCount()
    }

    // Points the sliders at what they now edit: the shown file's settings in the single view, the all-files settings in the grid.
    private fun selectTarget() {
        val uri = files.getOrNull(cur)
        v = if (single && uri != null) edits[uri]?.v ?: g.copyOf() else g
        for (i in 0 until n) setSlider(i)
        syncChips()
        updateMode()
    }

    // The first change in the single view makes the shown file individual; later changes in the grid leave it alone.
    private fun adopt() {
        if (!single || files.isEmpty()) return
        val uri = files[cur]
        if (!edits.containsKey(uri)) { edits[uri] = Edit(v); updateMode() }
    }

    private fun resetIndividual() {
        val uri = files.getOrNull(cur) ?: return
        edits.remove(uri)
        overlay.line = null
        selectTarget()
        updateDrawBar()
        render()
        say("This ${if (videoOf[uri] == true) "video" else "photo"} uses the settings for all files again")
    }

    private fun updateMode() {
        val uri = files.getOrNull(cur)
        val one = single && uri != null
        val e = if (one) edits[uri] else null
        val bg: Int; val fg: Int; val sub: Int
        if (one) {
            bg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorPrimaryContainer)
            fg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorOnPrimaryContainer); sub = fg
            modeIcon.setImageResource(R.drawable.ic_tune)
            modeTitle.text = "Only this ${if (videoOf[uri] == true) "video" else "photo"}"
            modeSub.text = when {
                e == null -> "Follows the settings for all files. Any change makes it individual."
                e.line != null -> "Individual settings with a gradient"
                else -> "Individual settings"
            }
        } else {
            bg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorSurfaceContainerHigh)
            fg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorOnSurface)
            sub = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorOnSurfaceVariant)
            modeIcon.setImageResource(R.drawable.ic_photos)
            modeTitle.text = "All files"
            modeSub.text = if (edits.isEmpty()) "Changes apply to every file"
                else "Changes apply to all except ${edits.size} individually edited (✱)"
        }
        modeBar.background = GradientDrawable().apply { setColor(bg); cornerRadius = 12 * dp }
        modeIcon.imageTintList = ColorStateList.valueOf(fg)
        modeTitle.setTextColor(fg); modeSub.setTextColor(sub)
        modeReset.visibility = if (e != null) View.VISIBLE else View.GONE
    }

    private fun enterSingle() {
        single = true
        showMode()
        loadPreview()
    }

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        overlay.drawing = on
        overlay.line = edits[files.getOrNull(cur)]?.line
        drawBar.visibility = if (on) View.VISIBLE else View.GONE
        histo.visibility = if (on || player?.playWhenReady == true) View.GONE else View.VISIBLE  // room for the expanded tool
        updateDrawBar()
    }

    // Off: plain tool button. Drawing: the tool fills with the accent colour. Once a line exists it expands to [tool | ✓ keep | ✕ discard].
    private fun updateDrawBar() {
        val has = edits[files.getOrNull(cur)]?.line != null
        val expanded = drawMode && has
        drawHint.text = if (has) "Drag again to redraw · ● full effect, ○ none" else "Drag across the photo"
        val color = { attr: Int -> MaterialColors.getColor(gradientBtn, attr) }
        val primary = color(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary)
        TransitionManager.beginDelayedTransition(gradientGroup, AutoTransition().setDuration(150))
        gradientSave.visibility = if (expanded) View.VISIBLE else View.GONE
        gradientDiscard.visibility = gradientSave.visibility
        gradientGroup.background = if (expanded) GradientDrawable().apply { setColor(primary); cornerRadius = 24 * dp } else null
        gradientBtn.backgroundTintList = ColorStateList.valueOf(when {
            expanded -> Color.TRANSPARENT
            drawMode -> primary
            else -> color(com.google.android.material.R.attr.colorSecondaryContainer)
        })
        gradientBtn.iconTint = ColorStateList.valueOf(if (drawMode) onPrimary else color(com.google.android.material.R.attr.colorOnSecondaryContainer))
        (gradientSave as MaterialButton).iconTint = ColorStateList.valueOf(onPrimary)
        (gradientDiscard as MaterialButton).iconTint = ColorStateList.valueOf(onPrimary)
    }

    // Keeps the overlay's idea of where the photo sits in sync with the image view (fit-center).
    private fun syncOverlay() {
        val b = srcBmp ?: return
        val w = image.width.toFloat(); val h = image.height.toFloat()
        if (w == 0f || h == 0f) return
        val sc = minOf(w / b.width, h / b.height)
        overlay.imgRect.set((w - b.width * sc) / 2, (h - b.height * sc) / 2, (w + b.width * sc) / 2, (h + b.height * sc) / 2)
        overlay.invalidate()
    }

    private fun exitSingle() {
        releasePlayer()
        fileSeq++; frameSeq++
        frameExec.execute { mmr?.release(); mmr = null }
        single = false
        setDrawMode(false)
        showMode()
        selectTarget()
        adapter.notifyDataSetChanged()
        if (files.isNotEmpty()) grid.smoothScrollToPosition(cur)
    }

    private fun removeCurrent() {
        if (running || files.isEmpty()) return
        edits.remove(files[cur])
        files = files.filterIndexed { k, _ -> k != cur }
        cur = cur.coerceAtMost(files.size - 1).coerceAtLeast(0)
        adapter.notifyDataSetChanged()
        if (files.isEmpty()) exitSingle() else { updateCount(); loadPreview() }
    }

    // ---------- sliders & presets ----------

    private fun setSlider(i: Int) {
        val d = ((v[i] - base[i]) / scale[i]).roundToInt().toFloat().coerceIn(dmin[i], 100f)
        v[i] = base[i] + d * scale[i]
        sliders[i]!!.value = d
        showValue(i)
    }

    private fun showValue(i: Int) {
        val d = ((v[i] - base[i]) / scale[i]).roundToInt()
        vals[i]!!.text = if (d > 0 && dmin[i] < 0) "+$d" else "$d"
    }

    private fun userChanged() {
        adopt()
        syncChips()  // checks a preset chip only if the settings still match it
        render()
    }

    private fun applyPreset(name: String) {
        val o = presets.optJSONObject(name) ?: return
        adopt()
        for (i in 0 until n) { v[i] = o.optDouble(ids[i], base[i].toDouble()).toFloat(); setSlider(i) }
        render()
    }

    private fun syncChips() {
        val match = presets.keys().asSequence().firstOrNull { name ->
            presets.optJSONObject(name)?.let { o -> (0 until n).all { abs(o.optDouble(ids[it], base[it].toDouble()).toFloat() - v[it]) < scale[it] / 2 } } == true
        }
        applyingPreset = true
        chips.clearCheck()
        for (k in 0 until chips.childCount) (chips.getChildAt(k) as? Chip)?.let { if (it.isCheckable && it.text == match) it.isChecked = true }
        applyingPreset = false
    }

    private fun buildChips(select: String?) {
        applyingPreset = true
        chips.removeAllViews()
        for (name in presets.keys().asSequence().toList()) {
            val chip = layoutInflater.inflate(R.layout.chip_preset, chips, false) as Chip
            chip.id = View.generateViewId(); chip.text = name
            chip.setOnLongClickListener { presetMenu(name); true }
            chips.addView(chip)
            if (name == select) chip.isChecked = true
        }
        chips.addView((layoutInflater.inflate(R.layout.chip_add, chips, false) as Chip).apply { id = View.generateViewId(); setOnClickListener { askName("Save preset", "") { savePresetAs(it) } } })
        applyingPreset = false
    }

    private fun currentAsJson() = JSONObject().apply { for (i in 0 until n) put(ids[i], v[i].toDouble()) }

    private fun storePresets(select: String?) {
        prefs.edit().putString("presets", presets.toString()).apply()
        buildChips(select)
    }

    private fun savePresetAs(name: String) {
        presets.put(name, currentAsJson())
        storePresets(name)
        say("Preset \"$name\" saved")
    }

    private fun presetMenu(name: String) {
        MaterialAlertDialogBuilder(this).setTitle(name)
            .setItems(arrayOf("Update with current settings", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> { presets.put(name, currentAsJson()); storePresets(name); say("Preset \"$name\" updated") }
                    1 -> askName("Rename preset", name) { new ->
                        if (new != name) { presets.put(new, presets.getJSONObject(name)); presets.remove(name); storePresets(new) }
                    }
                    2 -> MaterialAlertDialogBuilder(this).setMessage("Delete preset \"$name\"?")
                        .setPositiveButton("Delete") { _, _ -> presets.remove(name); storePresets(null) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        val input = TextInputEditText(this).apply { setText(initial); setSingleLine(); selectAll() }
        val field = TextInputLayout(this).apply { hint = "Name"; addView(input) }
        val box = FrameLayout(this).apply { setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0); addView(field) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- colour pipeline ----------

    // 3x4 row-major colour matrix on 0..1 RGB: channel mix (red restore), saturation, contrast, brightness.
    private fun matrix(v: FloatArray = this.v): FloatArray {
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

    // One pixel: corrected colour mixed with the original by w (1 = full correction, 0 = original).
    private fun shade(p: Int, m: FloatArray, pop: Float, w: Float): Int {
        if (w <= 0f) return p or 0xFF000000.toInt()
        val c = fx((p shr 16 and 255) / 255f, (p shr 8 and 255) / 255f, (p and 255) / 255f, m, pop)
        if (w >= 1f) return c
        val r0 = p shr 16 and 255; val g0 = p shr 8 and 255; val b0 = p and 255
        return Color.rgb((r0 + ((c shr 16 and 255) - r0) * w + .5f).toInt(), (g0 + ((c shr 8 and 255) - g0) * w + .5f).toInt(), (b0 + ((c and 255) - b0) * w + .5f).toInt())
    }

    // The gradient line in pixels, or null if there is none (or it is too short to mean anything).
    private fun usableLine(l: FloatArray?, w: Int, h: Int) = l?.takeIf { val dx = (it[2] - it[0]) * w; val dy = (it[3] - it[1]) * h; dx * dx + dy * dy > 4f }

    // One image row. With a line: full effect behind the start's perpendicular, fading linearly to none at the end's.
    private fun correctRow(src: IntArray, dst: IntArray, off: Int, w: Int, y: Int, h: Int, m: FloatArray, pop: Float, line: FloatArray?) {
        if (line == null) { for (x in 0 until w) dst[off + x] = shade(src[off + x], m, pop, 1f); return }
        val ax = line[0] * w; val ay = line[1] * h; val dx = (line[2] - line[0]) * w; val dy = (line[3] - line[1]) * h
        val l2 = dx * dx + dy * dy
        val t0 = ((.5f - ax) * dx + (y + .5f - ay) * dy) / l2; val dt = dx / l2
        for (x in 0 until w) dst[off + x] = shade(src[off + x], m, pop, 1f - (t0 + x * dt))
    }

    private fun correct(px: IntArray, w: Int, h: Int, s: FloatArray, line: FloatArray?): IntArray {
        val m = matrix(s); val pop = s[6] / 100f; val l = usableLine(line, w, h)
        val out = IntArray(px.size)
        for (y in 0 until h) correctRow(px, out, y * w, w, y, h, m, pop, l)
        return out
    }

    private fun settingsFor(uri: Uri) = edits[uri]?.v ?: g
    private fun keyFor(uri: Uri) = settingsFor(uri).contentHashCode() * 31 + (edits[uri]?.line?.contentHashCode() ?: 0)

    private fun lut(m: FloatArray, pop: Float) =
        SingleColorLut.createFromCube(Array(33) { r -> Array(33) { g -> IntArray(33) { b -> fx(r / 32f, g / 32f, b / 32f, m, pop) } } })

    private fun hist(px: IntArray) = Array(3) { IntArray(64) }.also { h ->
        for (p in px) { h[0][(p shr 18) and 63]++; h[1][(p shr 10) and 63]++; h[2][(p shr 2) and 63]++ }
    }

    private fun render() {
        val seq = ++renderSeq
        if (player != null) { ui.removeCallbacks(applyFx); ui.postDelayed(applyFx, 150) }
        if (!single) { renderThumbs((0 until grid.childCount).mapNotNull { grid.getChildAt(it).tag as? Uri }, seq); return }
        val px = srcPx ?: return
        val src = srcBmp ?: return
        val s = v.copyOf(); val line = edits[files.getOrNull(cur)]?.line?.copyOf()
        renderer.execute {
            if (seq != renderSeq) return@execute
            val out = correct(px, src.width, src.height, s, line)
            val bmp = Bitmap.createBitmap(out, src.width, src.height, Bitmap.Config.ARGB_8888)
            val h = hist(out)
            runOnUiThread {
                if (seq != renderSeq) return@runOnUiThread
                outBmp = bmp; outHist = h; display()
                if (player?.playWhenReady != true) {
                    image.visibility = View.VISIBLE; histo.visibility = if (drawMode) View.GONE else View.VISIBLE
                    if (player != null) releasePlayer()  // the corrected still now covers the paused video, so the player can go
                }
            }
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
        if (holding == on) return
        holding = on
        originalTag.visibility = if (on) View.VISIBLE else View.GONE
        if (overlay.drawing) drawBar.visibility = if (on) View.INVISIBLE else View.VISIBLE
        fullOriginal.visibility = originalTag.visibility
        display()
        if (player != null) { ui.removeCallbacks(applyFx); applyFx.run() }
    }

    // ---------- grid thumbnails ----------

    private fun makeCell(): FrameLayout {
        val icon = { res: Int -> ImageView(this).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(Color.WHITE) } }
        val small = (14 * dp).toInt()
        val ripple = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { val d = getDrawable(0); recycle(); d }
        return object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }.apply {
            setBackgroundColor(0xFF1D2021.toInt()); foreground = ripple
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(LinearLayout(context).apply {  // where a fixed copy exists: phone and/or card
                setBackgroundResource(R.drawable.pill); setPadding((5 * dp).toInt(), (3 * dp).toInt(), (5 * dp).toInt(), (3 * dp).toInt())
                addView(icon(R.drawable.ic_phone), LinearLayout.LayoutParams(small, small))
                addView(icon(R.drawable.ic_sd), LinearLayout.LayoutParams(small, small).apply { marginStart = (2 * dp).toInt() })
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, (4 * dp).toInt(), (4 * dp).toInt(), 0) })
            addView(LinearLayout(context).apply {  // video length
                gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.scrim_bottom)
                setPadding((6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                addView(icon(R.drawable.ic_play), LinearLayout.LayoutParams(small, small))
                addView(TextView(context).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall); setTextColor(Color.WHITE)
                    fontFeatureSettings = "tnum"; setPadding((3 * dp).toInt(), 0, 0, 0)
                })
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(FrameLayout(context).apply {  // ✱ individually edited
                setBackgroundResource(R.drawable.pill); setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                addView(icon(R.drawable.ic_asterisk), FrameLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()))
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), 0, 0) })
        }
    }

    private fun cellFor(uri: Uri) = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.tag == uri } as? FrameLayout

    private fun bindCell(cell: FrameLayout, uri: Uri) {
        val img = cell.getChildAt(0) as ImageView
        val where = cell.getChildAt(1) as LinearLayout
        val vid = cell.getChildAt(2) as LinearLayout
        cell.getChildAt(3).visibility = if (edits.containsKey(uri)) View.VISIBLE else View.GONE
        val t = thumbs.get(uri)
        if (t == null) {
            img.setImageBitmap(null); where.visibility = View.GONE; vid.visibility = View.GONE
            if (loading.add(uri)) {
                val s = settingsFor(uri).copyOf(); val line = edits[uri]?.line?.copyOf(); val key = keyFor(uri)
                thumbLoader.execute {
                    val th = loadThumb(uri, s, line, key)
                    runOnUiThread { loading.remove(uri); thumbs.put(uri, th); cellFor(uri)?.let { bindCell(it, uri) } }
                }
            }
            return
        }
        img.setImageBitmap(t.out)
        val card = cardFiles.containsKey(t.outName)
        where.visibility = if (t.phone || card) View.VISIBLE else View.GONE
        where.getChildAt(0).visibility = if (t.phone) View.VISIBLE else View.GONE
        where.getChildAt(1).visibility = if (card) View.VISIBLE else View.GONE
        vid.visibility = if (t.dur >= 0) View.VISIBLE else View.GONE
        (vid.getChildAt(1) as TextView).text = "%d:%02d".format(t.dur / 60000, t.dur / 1000 % 60)
        if (t.key != keyFor(uri)) renderThumbs(listOf(uri), renderSeq)
    }

    private fun loadThumb(uri: Uri, s: FloatArray, line: FloatArray?, key: Int): Thumb {
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
        val outName = outName(uri, video)
        val phone = runCatching { existing(outName, video) != null }.getOrDefault(false)
        if (bmp == null) return Thumb(IntArray(0), 0, 0, dur, outName, phone, null, key)
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val out = correct(px, bmp.width, bmp.height, s, line)
        return Thumb(px, bmp.width, bmp.height, dur, outName, phone, Bitmap.createBitmap(out, bmp.width, bmp.height, Bitmap.Config.ARGB_8888), key)
    }

    // Re-renders thumbnails whose settings changed; each file uses its own settings, so grid edits skip individual files.
    private fun renderThumbs(list: List<Uri>, seq: Int) {
        val jobs = list.map { uri -> Triple(uri, settingsFor(uri).copyOf(), edits[uri]?.line?.copyOf()) to keyFor(uri) }
        thumbRenderer.execute {
            for ((job, key) in jobs) {
                val (uri, s, line) = job
                if (seq != renderSeq) return@execute
                val t = thumbs.get(uri) ?: continue
                if (t.key == key || t.w == 0) continue
                val bmp = Bitmap.createBitmap(correct(t.px, t.w, t.h, s, line), t.w, t.h, Bitmap.Config.ARGB_8888)
                runOnUiThread { if (seq == renderSeq) { t.out = bmp; t.key = key; (cellFor(uri)?.getChildAt(0) as? ImageView)?.setImageBitmap(bmp) } }
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
        fileTitle = nameOf(uri); fileSub = "${cur + 1} of ${files.size}"
        updateToolbar()
        refreshFileInfo()
        prevBtn.visibility = if (files.size > 1) View.VISIBLE else View.GONE
        nextBtn.visibility = prevBtn.visibility
        videoRow.visibility = if (video) View.VISIBLE else View.GONE
        gradientBtn.visibility = if (video) View.GONE else View.VISIBLE  // gradients are for photos
        setDrawMode(false)
        selectTarget()
        srcBmp = null; srcPx = null; outBmp = null; srcHist = null; outHist = null
        image.setImageBitmap(null); histo.data = null
        videoPos = 0; videoDur = 0; timeline.value = 0f; timeline.valueTo = 1f; timeTv.text = ""
        if (video) {
            frameExec.execute {
                mmr?.release(); mmr = null
                if (fs != fileSeq) return@execute
                val r = runCatching { MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) } }.getOrNull()
                mmr = r
                val d = r?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                runOnUiThread { if (fs == fileSeq) { videoDur = d; timeline.valueTo = maxOf(1f, d.toFloat()); timeTv.text = times(); loadFrame(true) } }
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
            if (bmp == null) { say("Can't open ${fileTitle}"); return@runOnUiThread }
            srcBmp = bmp; srcPx = px; srcHist = h; outBmp = null; outHist = null
            syncOverlay()
            if (full.isShowing) orientFull()
            render()
        }
    }

    private fun times() = "%d:%02d / %d:%02d".format(videoPos / 60000, videoPos / 1000 % 60, videoDur / 60000, videoDur / 1000 % 60)

    // ---------- video playback (live correction through the same LUT as the export) ----------

    private fun play() {
        if (running) { say("Playback is paused while files are being corrected"); return }
        if (videoDur > 0 && videoPos >= videoDur - 200) videoPos = 0
        // Always start a fresh player at the current position: resuming a paused one with live colour effects can stall.
        if (player != null) releasePlayer()
        run {
            val p = ExoPlayer.Builder(this).build()
            player = p
            p.setVideoSurfaceView(surface)
            p.setVideoEffects(listOf<Effect>(lut(matrix(), v[6] / 100f)))
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { if (player?.playWhenReady == true) image.visibility = View.INVISIBLE }
                override fun onVideoSizeChanged(videoSize: VideoSize) { fitSurface(videoSize) }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playBtn.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    playBtn.contentDescription = if (isPlaying) "Pause" else "Play"
                }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) pause() }
                override fun onPlayerError(error: PlaybackException) { say("Can't play this video (${error.errorCodeName})"); releasePlayer(); loadFrame(true) }
            })
            p.setMediaItem(MediaItem.fromUri(files[cur]), videoPos)
            p.prepare()
            if (holding) applyFx.run()
            p.play()
        }
        surface.visibility = View.VISIBLE
        histo.visibility = View.GONE
        ui.removeCallbacks(ticker); ui.post(ticker)
    }

    private fun pause() {
        val p = player ?: return
        p.pause()
        videoPos = p.currentPosition.coerceIn(0L, videoDur)
        timeline.value = videoPos.toFloat().coerceIn(timeline.valueFrom, timeline.valueTo); timeTv.text = times()
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
        playBtn.setIconResource(R.drawable.ic_play); playBtn.contentDescription = "Play"
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

    // ---------- files & destinations ----------

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun isVideo(uri: Uri) = contentResolver.getType(uri)?.startsWith("video") == true

    private fun outName(uri: Uri, video: Boolean) = nameOf(uri).substringBeforeLast('.') + "_fix" + if (video) ".mp4" else ".jpg"

    // Our earlier output for this file in DCIM/DiveFix, if any (only files DiveFix created are visible to it).
    private fun existing(fileName: String, video: Boolean): Uri? {
        val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return contentResolver.query(
            col, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("DCIM/DiveFix/", fileName), null
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(col, it.getLong(0)) else null }
    }

    // Toolbar subtitle in the single view: position plus where a fixed copy already exists.
    private fun refreshFileInfo() {
        if (files.isEmpty() || !single) return
        val uri = files[cur]
        val fs = fileSeq
        thread {
            val video = isVideo(uri)
            val name = outName(uri, video)
            val phone = runCatching { existing(name, video) != null }.getOrDefault(false)
            val card = cardFiles.containsKey(name)
            val where = when { phone && card -> " · fixed on phone and card"; phone -> " · fixed on phone"; card -> " · fixed on card"; else -> "" }
            runOnUiThread { if (fs == fileSeq) { fileSub = "${cur + 1} of ${files.size}$where"; toolbar.subtitle = fileSub } }
        }
    }

    private fun setDest(pos: Int) {
        dest = pos
        prefs.edit().putInt("dest", pos).apply()
    }

    private fun chooseCard() {
        say("Choose the camera card's top folder. DiveFix saves into a DiveFix folder there.")
        cardPicker.launch(null)
    }

    private fun onCardChosen(tree: Uri?) {
        if (tree == null) { destGroup.check(destIds[dest]); return }
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("cardTree", tree.toString()).apply()
        if (pendingDest > 0) { setDest(pendingDest); pendingDest = 0 }
        thread {
            val ok = openCard(true)
            runOnUiThread {
                say(if (ok) "Camera card ready. Fixed files go to its DiveFix folder." else "DiveFix can't write to that folder")
                adapter.notifyDataSetChanged(); refreshFileInfo()
            }
        }
    }

    // Finds (or creates) the DiveFix folder on the saved card and lists what is in it. False if the card isn't reachable.
    private fun openCard(create: Boolean): Boolean {
        val tree = prefs.getString("cardTree", null)?.let { Uri.parse(it) } ?: return false
        return runCatching {
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var dirId: String? = null
            contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId), cols, null, null, null)!!.use { c ->
                while (c.moveToNext()) if (c.getString(1) == "DiveFix" && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirId = c.getString(0)
            }
            val dir = dirId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                ?: if (create) DocumentsContract.createDocument(contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, "DiveFix")!! else null
            val list = ConcurrentHashMap<String, String>()
            if (dir != null) contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir)), cols, null, null, null)!!.use { c ->
                while (c.moveToNext()) list[c.getString(1)] = c.getString(0)
            }
            cardTree = tree; cardDir = dir; cardFiles = list
            dir != null || !create
        }.getOrElse { cardTree = null; cardDir = null; cardFiles = ConcurrentHashMap(); false }
    }

    // Copies a finished export to the chosen destinations, replacing an earlier fix of the same file in each.
    private fun save(tmp: File, name: String, video: Boolean, taken: Long?) {
        val errors = mutableListOf<String>()
        if (batchDest != 1) runCatching {
            existing(name, video)?.let { old -> runCatching { contentResolver.delete(old, null, null) } }
            val out = contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DiveFix"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (video) taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            })!!
            runCatching {
                contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } }
                contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.onFailure { contentResolver.delete(out, null, null); throw it }
        }.onFailure { errors += "phone: ${it.message}" }
        if (batchDest != 0) runCatching {
            val tree = cardTree!!; val dir = cardDir ?: error("camera card not connected")
            val old = cardFiles[name]
            val target = old?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                ?: DocumentsContract.createDocument(contentResolver, dir, if (video) "video/mp4" else "image/jpeg", name)!!
            runCatching { contentResolver.openOutputStream(target, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } } }
                .onFailure { if (old == null) runCatching { DocumentsContract.deleteDocument(contentResolver, target) }; throw it }
            cardFiles[name] = DocumentsContract.getDocumentId(target)
        }.onFailure { errors += "card: ${it.message}" }
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("; "))
    }

    private fun tally(r: Result<*>) {
        if (r.isSuccess) saved++ else { failed++; lastError = r.exceptionOrNull()?.message }
    }

    // ---------- batch export ----------

    private fun startBatch() {
        releasePlayer()
        running = true; cancelled = false; saved = 0; failed = 0; lastError = null; batchDest = dest
        destGroup.isEnabled = false; updateCount()
        val plan = files.map { settingsFor(it).copyOf() to edits[it]?.line?.copyOf() }  // each file with its own settings
        thread {
            val ok = batchDest == 0 || openCard(true)
            runOnUiThread {
                if (!ok) {
                    running = false; destGroup.isEnabled = true; updateCount()
                    say("Camera card not found. Connect the camera in USB mode.", "Choose card") { chooseCard() }
                    return@runOnUiThread
                }
                idleRow.visibility = View.GONE; runRow.visibility = View.VISIBLE
                progress.setProgressCompat(0, false)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                process(0, plan)
            }
        }
    }

    private fun finishBatch() {
        running = false; transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        idleRow.visibility = View.VISIBLE; runRow.visibility = View.GONE
        destGroup.isEnabled = true
        updateCount(); refreshFileInfo()
        val where = listOf("phone", "camera card", "phone and card")[batchDest]
        val msg = (if (cancelled) "Stopped. " else "") + "$saved saved to $where" +
            (if (failed > 0) ". $failed failed" + (lastError?.let { ": $it" } ?: "") else "")
        if (failed > 0) say(msg, "OK") {} else say(msg)
        val snap = thumbs.snapshot()  // refresh the location marks in the grid
        thread {
            snap.forEach { (_, t) -> t.phone = runCatching { existing(t.outName, t.dur >= 0) != null }.getOrDefault(t.phone) }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    private fun process(i: Int, plan: List<Pair<FloatArray, FloatArray?>>) {
        if (!running) return
        if (cancelled || i >= files.size) { finishBatch(); return }
        val m = matrix(plan[i].first); val pop = plan[i].first[6] / 100f
        val uri = files[i]
        val name = nameOf(uri)
        val base = name.substringBeforeLast('.') + "_fix"
        runText.text = "${i + 1} of ${files.size} · $name"
        progress.setProgressCompat(i * 1000 / files.size, true)

        if (!isVideo(uri)) {
            thread {
                val tmp = File(cacheDir, "export.jpg")
                val result = runCatching {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.isMutableRequired = true
                    }
                    val w = bmp.width
                    val row = IntArray(w)
                    val line = usableLine(plan[i].second, w, bmp.height)
                    for (y in 0 until bmp.height) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1)
                        correctRow(row, row, 0, w, y, bmp.height, m, pop, line)
                        bmp.setPixels(row, 0, w, 0, y, w, 1)
                    }
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    bmp.recycle()
                    runCatching { // capture date, GPS and camera info; orientation is already applied to the pixels
                        val src = contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
                        ExifInterface(tmp.absolutePath).apply { exifTags.forEach { t -> src.getAttribute(t)?.let { setAttribute(t, it) } }; saveAttributes() }
                    }
                    save(tmp, "$base.jpg", false, null)
                }
                tmp.delete()
                runOnUiThread { tally(result); process(i + 1, plan) }
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
                        val result = runCatching { save(tmp, "$base.mp4", true, taken) }
                        tmp.delete()
                        runOnUiThread { tally(result); process(i + 1, plan) }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    tmp.delete(); failed++; lastError = exportException.message
                    process(i + 1, plan)
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
                    progress.setProgressCompat((i * 1000 + holder.progress * 10) / files.size, true)
                    runText.text = "${i + 1} of ${files.size} · $name · ${holder.progress}%"
                }
                ui.postDelayed(this, 500)
            }
        })
    }
}
