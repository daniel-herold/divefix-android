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
import android.widget.Button
import android.widget.FrameLayout
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
import android.widget.ScrollView
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.google.android.material.slider.RangeSlider
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.hypot
import kotlin.math.sqrt
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
    private val defaults = """{"Shallow":{"red":20,"green":-1.5,"blue":-4,"bright":0,"contrast":1.04,"sat":1,"pop":10},"Medium":{"red":40,"green":-3,"blue":-8,"bright":0.01,"contrast":1.06,"sat":1.05,"pop":15},"Deep":{"red":60,"green":-4.5,"blue":-12,"bright":0.02,"contrast":1.08,"sat":1.1,"pop":20}}"""
    private val defaultsV2 = """{"Shallow":{"red":45,"green":-3,"blue":-8,"bright":0,"contrast":1.08,"sat":1.05,"pop":15},"Medium":{"red":80,"green":-6,"blue":-12,"bright":0.02,"contrast":1.12,"sat":1.1,"pop":25},"Deep":{"red":100,"green":-9,"blue":-20,"bright":0.04,"contrast":1.16,"sat":1.15,"pop":35}}"""
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
    class Thumb(val px: IntArray, val w: Int, val h: Int, val dur: Long, val outName: String, var phone: Boolean, var out: Bitmap?, var key: Int) {
        var orig: Bitmap? = null  // uncorrected, made on demand for "hold to compare" in the grid
        fun original() = orig ?: (if (w > 0) Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) else null).also { orig = it }
    }

    /** Individual settings for one file: slider values plus an optional gradient line (x0, y0, x1, y1 in 0..1 image coordinates). */
    class Edit(val v: FloatArray, var line: FloatArray? = null)

    private val n = ids.size
    private val g = base.copyOf()  // settings for all files
    private var v = g  // what the sliders edit: g in the grid, the shown file's own settings in the single view
    private val edits = HashMap<Uri, Edit>()  // individually edited files
    private val videoOf = HashMap<Uri, Boolean>()
    private val selected = LinkedHashSet<Uri>()  // marked for export (colour in the grid); the others are just included (grey)
    private var editSelected = false  // grid: sliders change only the selected files

    /** Per-file geometry: quarter turns clockwise, crop (l, t, r, b in 0..1 of the turned picture), video trim in ms (-1 = none). */
    class Geo(var rot: Int = 0, var crop: FloatArray? = null, var trimStart: Long = -1, var trimEnd: Long = -1) {
        val isSet get() = rot != 0 || crop != null || trimStart >= 0 || trimEnd >= 0
        fun copy() = Geo(rot, crop?.copyOf(), trimStart, trimEnd)
    }
    private val geos = HashMap<Uri, Geo>()
    private var geoMode = false
    private var videoFps = 30f
    private var baseBmp: Bitmap? = null  // decoded picture or frame before turning
    private val grey = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val exifClock = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val sliders = arrayOfNulls<Slider>(n)
    private val vals = arrayOfNulls<TextView>(n)
    private val dp by lazy { resources.displayMetrics.density }
    private var tablet = false
    private var files = listOf<Uri>()  // everything added, in the order it was added
    private var shown = listOf<Uri>()  // what the grid shows (filtered and sorted); the single view steps through this
    private var items = listOf<Any>()  // grid rows: Dive headers and files
    private var batch = listOf<Uri>()  // files being saved

    /** What DiveFix knows about a file: capture time as camera wall-clock millis (or -1), video length, saved on the phone. */
    class Meta(val name: String, val video: Boolean, val time: Long, val dur: Long, var phone: Boolean)
    /** Files taken close together: a new dive starts after a 30 min break, or a 10 min break once a dive has lasted 100 min. */
    class Dive(val n: Int, val start: Long, val end: Long, val all: List<Uri>) { var shownHere = listOf<Uri>() }
    private val meta = ConcurrentHashMap<Uri, Meta>()
    private val metaExec = Executors.newSingleThreadExecutor()
    private var filter = 0  // 0 all, 1 raw (no saved copy), 2 saved, 3 individual
    private var sort = 0  // 0 oldest first, 1 newest first, 2 name
    private var groupDives = true
    private var showTimes = false
    private val wall = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
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
    private lateinit var grid: RecyclerView
    private lateinit var gridLm: GridLayoutManager
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var noMatch: TextView
    private lateinit var cropOverlay: CropOverlay
    private lateinit var frameTools: View
    private lateinit var geoGroup: View
    private lateinit var trimRow: View
    private lateinit var trimSlider: RangeSlider
    private lateinit var trimTime: TextView
    private lateinit var selectBtn: MaterialButton
    private lateinit var modeSwitch: MaterialButton
    private lateinit var singleBox: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var image: ImageView
    private lateinit var originalTag: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var histo: Histo
    private lateinit var overlay: LineOverlay
    private lateinit var gradBtns: List<MaterialButton>  // linear, circle (full inside), circle (full outside)
    private lateinit var compareBtn: View
    private lateinit var compareIcon: View
    private var drawKind = 0
    private lateinit var drawBar: View
    private lateinit var drawHint: TextView
    private lateinit var gradientGroup: LinearLayout
    private lateinit var gradientSave: View
    private lateinit var gradientDiscard: View
    private var drawMode = false
    private lateinit var addMore: View
    private lateinit var gridCompare: View
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
    private val backToGrid = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = back() }
    private val applyFx = Runnable { player?.setVideoEffects(videoEffects()) }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.playWhenReady) return
            videoPos = p.currentPosition
            if (videoPos >= trimEnd()) { pause(); return }  // playback stops at the trim end
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
                5 -> { selected.addAll(shown); selectionChanged() }
                6 -> removeUnselected()
                10 -> { selected.removeAll(shown.toSet()); selectionChanged() }
                11 -> setGeoMode(!geoMode)
                7 -> showHelp()
                9 -> showSortSheet()
                8 -> showAbout()
            }
            true
        }
        toolbar.setNavigationOnClickListener { back() }
        selectBtn = MaterialButton(this).apply {  // single view: grey "Select" or accent "Selected"
            setOnClickListener { shown.getOrNull(cur)?.let { toggle(it) } }
            insetTop = 0; insetBottom = 0; minHeight = (36 * dp).toInt(); iconPadding = (4 * dp).toInt()
        }
        findViewById<View>(R.id.emptyAdd).setOnClickListener { filePicker.launch(arrayOf("image/*", "video/*")) }
        emptyBox = findViewById(R.id.empty)
        addMore = findViewById<View>(R.id.addMore).apply { setOnClickListener { filePicker.launch(arrayOf("image/*", "video/*")) } }
        gridCompare = findViewById(R.id.gridCompare)

        // ---------- grid ----------
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun getItemViewType(p: Int) = if (items[p] is Dive) 1 else 0
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                val v = if (type == 1) GridViews.header(this@MainActivity).apply {
                    (getChildAt(1) as View).setOnClickListener { (tag as? Dive)?.let { selectDive(it) } }
                } else GridViews.cell(this@MainActivity).apply {
                    val gap = (1 * dp).toInt()
                    layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(gap, gap, gap, gap) }
                    setOnClickListener { (tag as? Uri)?.let { cur = shown.indexOf(it); enterSingle() } }  // tap opens, long-press selects
                    setOnLongClickListener { (tag as? Uri)?.let { toggle(it) }; true }
                }
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
                when (val it = items[p]) {
                    is Dive -> { h.itemView.tag = it; bindHeader(h.itemView as LinearLayout, it) }
                    is Uri -> { h.itemView.tag = it; bindCell(h.itemView as FrameLayout, it) }
                }
            }
        }
        gridLm = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(p: Int) = if (items.getOrNull(p) is Dive) spanCount else 1 }
        }
        grid = findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = gridLm; adapter = this@MainActivity.adapter; itemAnimator = null
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->  // tablets fit as many ~140 dp columns as there is room for
                val spans = if (tablet) maxOf(3, ((r - l) / (140 * dp)).toInt()) else 3
                if (spans != gridLm.spanCount) { gridLm.spanCount = spans; post { this@MainActivity.adapter.notifyDataSetChanged() } }
            }
        }
        noMatch = findViewById(R.id.noMatch)
        filter = 0; sort = prefs.getInt("sort", 0); groupDives = prefs.getBoolean("groupDives", true); showTimes = prefs.getBoolean("showTimes", false)

        // ---------- single view ----------
        singleBox = findViewById(R.id.single)
        surface = findViewById(R.id.surface)
        image = findViewById<ImageView>(R.id.image).apply { setOnClickListener { openFull() } }
        originalTag = findViewById(R.id.originalTag)
        histo = findViewById(R.id.histo)
        overlay = findViewById<LineOverlay>(R.id.lineOverlay).apply {
            onLine = { l -> adopt(); edits[shown[cur]]?.line = l; render(); updateMode(); updateDrawBar() }
        }
        drawBar = findViewById(R.id.drawBar)
        drawHint = findViewById(R.id.drawHint)
        gradientGroup = findViewById(R.id.gradientGroup)
        gradBtns = listOf(R.id.gradLinear, R.id.gradIn, R.id.gradOut).mapIndexed { k, id ->
            findViewById<MaterialButton>(id).apply { setOnClickListener { drawKind = k; if (drawMode) { this@MainActivity.overlay.kind = k; updateDrawBar() } else setDrawMode(true) } }
        }
        gradientSave = findViewById<View>(R.id.gradientSave).apply { setOnClickListener { setDrawMode(false) } }
        gradientDiscard = findViewById<View>(R.id.gradientDiscard).apply {
            setOnClickListener { edits[shown[cur]]?.line = null; render(); updateMode(); setDrawMode(false) }
        }
        prevBtn = findViewById<Button>(R.id.prev).apply { setOnClickListener { if (shown.isNotEmpty()) { cur = (cur - 1 + shown.size) % shown.size; loadPreview() } } }
        nextBtn = findViewById<Button>(R.id.next).apply { setOnClickListener { if (shown.isNotEmpty()) { cur = (cur + 1) % shown.size; loadPreview() } } }
        val compareTouch = View.OnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { hold(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold(false); view.performClick() }
            }
            false  // let the button show its pressed state
        }
        compareBtn = findViewById<View>(R.id.compare).apply { setOnTouchListener(compareTouch) }
        compareIcon = findViewById<View>(R.id.compareIcon).apply { setOnTouchListener(compareTouch) }
        gridCompare.setOnTouchListener(compareTouch)
        singleBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> player?.let { fitSurface(it.videoSize) }; syncOverlay() }
        videoRow = findViewById(R.id.videoRow)
        playBtn = findViewById<MaterialButton>(R.id.play).apply { setOnClickListener { if (player?.playWhenReady == true) pause() else play() } }
        timeTv = findViewById(R.id.time)
        cropOverlay = findViewById(R.id.cropOverlay)
        frameTools = findViewById(R.id.frameTools)
        geoGroup = findViewById(R.id.geoGroup)
        trimRow = findViewById(R.id.trimRow)
        trimTime = findViewById(R.id.trimTime)
        findViewById<View>(R.id.frameBack).setOnClickListener { stepFrame(-1) }
        findViewById<View>(R.id.frameFwd).setOnClickListener { stepFrame(1) }
        findViewById<View>(R.id.saveFrame).setOnClickListener { saveFrame() }
        findViewById<View>(R.id.geoRotate).setOnClickListener { turnCurrent() }
        findViewById<View>(R.id.geoSave).setOnClickListener { commitGeo() }
        findViewById<View>(R.id.geoDiscard).setOnClickListener { resetGeo() }
        trimSlider = findViewById<RangeSlider>(R.id.trimSlider).apply {
            addOnChangeListener { sl, _, fromUser ->  // the dragged end shows its frame
                if (!fromUser) return@addOnChangeListener
                videoPos = sl.values[sl.activeThumbIndex.coerceIn(0, 1)].toLong(); updateTrimText(); loadFrame(false)
            }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {}
                override fun onStopTrackingTouch(slider: RangeSlider) { loadFrame(true) }
            })
        }
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
                addOnChangeListener { _, value, fromUser -> if (fromUser) { v[i] = base[i] + value * scale[i]; showValue(i); userChanged(i) } }
                val reset = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean { v[i] = base[i]; setSlider(i); userChanged(i); return true }
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
        modeSwitch = findViewById<MaterialButton>(R.id.modeSwitch).apply { setOnClickListener { editSelected = !editSelected; selectTarget() } }

        // ---------- presets ----------
        presets = JSONObject(prefs.getString("presets", null) ?: defaults)
        if (prefs.getInt("presetsVersion", 1) < 2) {  // replace the first-generation default presets, keep the user's own
            val old = setOf("Shallow 0-5 m", "Medium 5-15 m", "Deep 15 m+")
            val merged = JSONObject(defaults)
            presets.keys().asSequence().toList().filter { it !in old && !merged.has(it) }.forEach { merged.put(it, presets.get(it)) }
            presets = merged
            prefs.edit().putString("presets", presets.toString()).putInt("presetsVersion", 2).apply()
        }
        if (prefs.getInt("presetsVersion", 1) < 3) {  // gentler defaults; a default the user has changed is kept
            val v2 = JSONObject(defaultsV2); val v3 = JSONObject(defaults)
            for (name in v3.keys().asSequence().toList()) {
                val mine = presets.optJSONObject(name); val was = v2.optJSONObject(name)
                if (mine == null || (was != null && ids.all { abs(mine.optDouble(it) - was.optDouble(it)) < 1e-6 })) presets.put(name, v3.getJSONObject(name))
            }
            prefs.edit().putString("presets", presets.toString()).putInt("presetsVersion", 3).apply()
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
        fullHint = pillText("Pinch to zoom · Hold for the original · Back to return")
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
        }
        // Touches go to the (possibly rotated) box, so gestures follow the picture's own orientation.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val holdLater = Runnable { hold(true) }
        var lastX = 0f; var lastY = 0f; var downX = 0f; var downY = 0f; var multi = false; var fx = 0f; var fy = 0f
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { fx = d.focusX; fy = d.focusY; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY, d.focusX - fx, d.focusY - fy); fx = d.focusX; fy = d.focusY; return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { ui.removeCallbacks(holdLater); hold(false); if (zoom > 1.01f) resetZoom() else zoomBy(2.5f, e.x, e.y, 0f, 0f); return true }
        })
        rotBox.setOnTouchListener { view, e ->
            pinch.onTouchEvent(e); taps.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fullHint.visibility = View.GONE; lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; multi = false; hold(true) }  // original right away; panning or pinching switches back
                MotionEvent.ACTION_POINTER_DOWN -> { multi = true; ui.removeCallbacks(holdLater); hold(false) }
                MotionEvent.ACTION_MOVE -> if (!multi && e.pointerCount == 1) {
                    if (hypot(e.x - downX, e.y - downY) > slop) { ui.removeCallbacks(holdLater); if (zoom > 1.01f) { hold(false); zoomBy(1f, e.x, e.y, e.x - lastX, e.y - lastY) } }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(holdLater); hold(false); view.performClick() }
            }
            true
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
        thread { openCard(false); runOnUiThread { if (!single) rebuild(); refreshFileInfo() } }  // the camera may have been plugged in meanwhile
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

    private fun showHelp() {
        val sections = listOf(
            "What DiveFix does" to "Water swallows red light first, so dive photos and videos turn blue-green. DiveFix brings the colours back with a few simple settings and saves corrected copies. Your originals are never changed, and everything runs offline on your phone.",
            "Your set and what gets saved" to "Everything you add is in your set and shown in the grid. Selected files are in colour, the others grey. Long-press a thumbnail, or use Select in the single view, to switch. Correct saves the selected files the grid shows; in the single view it saves just that file.",
            "All files or individual" to "In the grid, the settings apply to all files (or only to the selected ones with Edit selected). In the single view, changes apply only to that file: it becomes individual (✱) and later grid changes skip it. Reset returns it to the settings for all files.",
            "Crop, trim and rotate" to "Use the crop (photos) or scissors (videos) button at the top of the single view. Drag the frame or the trim ends, ↻ turns by 90°, ✓ keeps, ✕ undoes. These only affect the saved copy.",
            "Photos from videos" to "Pause a video, step frame by frame with the arrow buttons and tap the camera to save that frame as a new photo in DCIM/DiveFix/Frames. It keeps the video's time, is added to your set as selected and can be corrected like any photo.",
            "Presets" to "Shallow, Medium and Deep are starting points for typical depths. Save your own with + Save preset; long-press a preset to update, rename or delete it.",
            "Gradients (photos)" to "Limit the correction to part of a photo. Line: full strength at ● fading to none at ○. Circles: drag from the centre outwards; one keeps the effect inside the circle, the other outside. You can start outside the photo. Only one gradient per photo: drawing a new one replaces it.",
            "Saving" to "Phone saves to DCIM/DiveFix, where your gallery shows it. Card saves to a DiveFix folder on the camera's card. Copies get _fix in the name, and correcting a file again replaces its earlier copy. Capture date and location are kept.",
            "Camera card" to "Set the camera to USB mode, connect it and add files with +. Don't unplug while saving. Originals and corrected copies on the same card are not a backup: copy them somewhere else too.",
            "Dives, sorting and filters" to "Sorted by time, files are grouped into dives: a new dive starts after a break of 30 minutes, or 10 minutes once a dive has lasted 100 minutes. Times come from the file name (DJI), the photo's data or the file date. Tap Select on a dive to select all its files. The filter button shows only raw (not yet saved), saved or individually edited files. Correct saves what is shown, or just the selection.",
            "Kept only while the app is open" to "The list of files and all individual settings (✱, gradients) are temporary. Presets and the save location are remembered.",
            "Videos" to "Playback shows the correction live. Exporting takes a while: keep the screen on and the phone charging. HDR clips are saved as normal (SDR) video.",
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), 0) }
        for ((title, body) in sections) {
            box.addView(TextView(this).apply {
                text = title; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(MaterialColors.getColor(box, com.google.android.material.R.attr.colorPrimary)); setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
            })
            box.addView(TextView(this).apply {
                text = body; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(box, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        MaterialAlertDialogBuilder(this).setTitle("Help").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Close", null).show()
    }

    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()))
            addView(TextView(context).apply { text = "DiveFix"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge); setPadding(0, (12 * dp).toInt(), 0, 0) })
            addView(TextView(context).apply {
                text = "Version $version"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            addView(TextView(context).apply {
                text = "Underwater colour correction for photos and videos.\nRuns fully offline: nothing leaves your phone.\n\nBuilt with AndroidX Media3 and Material Components."
                gravity = Gravity.CENTER; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); setPadding(0, (16 * dp).toInt(), 0, 0)
            })
        }
        MaterialAlertDialogBuilder(this).setView(box).setPositiveButton("Close", null).show()
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
        files = listOf(); edits.clear(); selected.clear(); meta.clear(); geos.clear()
        addFiles(list)
    }

    // Adds files to the set (grey until selected), skipping ones that are already in it.
    private fun addFiles(list: List<Uri>, select: Boolean = false) {
        if (running) { say("Wait for the current batch to finish first"); return }
        val known = files.toHashSet()
        val added = list.filter { known.add(it) }
        added.forEach { videoOf[it] = isVideo(it) }
        val wasEmpty = files.isEmpty()
        files = files + added
        if (select) selected.addAll(added)
        if (single) { shown = shown + added; rebuild(keepOrder = true); scanMeta(added); return }  // stay on the file being viewed
        rebuild()
        updateMode()
        if (wasEmpty) grid.scrollToPosition(0) else if (added.isNotEmpty()) items.indexOf(added.last()).takeIf { it >= 0 }?.let { grid.smoothScrollToPosition(it) }
        scanMeta(added)
        if (added.size < list.size) say("${list.size - added.size} already in the set, skipped")
    }

    private fun updateCount() {
        val target = exportTargets().size
        go.text = when { single -> "Correct this"; target == 0 -> "Correct"; else -> "Correct $target" }
        go.isEnabled = target > 0 && !running
        emptyBox.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        noMatch.visibility = if (files.isNotEmpty() && shown.isEmpty() && !single) View.VISIBLE else View.GONE
        noMatch.text = "No ${listOf("", "raw", "saved", "individually edited")[filter]} files. Change the filter to see the others."
        addMore.visibility = if (files.isNotEmpty() && !single && !running) View.VISIBLE else View.GONE
        gridCompare.visibility = if (files.isNotEmpty() && !single) View.VISIBLE else View.GONE
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
            val uri = shown.getOrNull(cur)
            val video = uri != null && videoOf[uri] == true
            menu.add(0, 11, 0, if (video) "Trim and rotate" else "Crop and rotate").apply {  // accent when this file is cropped, trimmed or turned
                setIcon(if (video) R.drawable.ic_trim else R.drawable.ic_crop); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                icon?.mutate()?.setTint(MaterialColors.getColor(toolbar, if (geos[uri]?.isSet == true || geoMode) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            (selectBtn.parent as? ViewGroup)?.removeView(selectBtn)
            menu.add(0, 12, 1, "Select").apply { actionView = selectBtn; setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
            updateSelectBtn()
            menu.add(0, 3, 2, "Remove from set")
        } else {
            toolbar.navigationIcon = null
            toolbar.title = "DiveFix"
            toolbar.subtitle = if (files.isEmpty()) null else "${selected.size} of ${files.size} selected · " + counts() + when {
                files.any { !meta.containsKey(it) } -> " · reading dates…"
                filter != 0 -> " · ${shown.size} ${listOf("", "raw", "saved", "individual")[filter]} shown"
                else -> ""
            }
            if (files.isNotEmpty()) {
                menu.add(0, 9, 0, "Sort and filter").apply {  // the icon lights up while a filter is on
                    setIcon(R.drawable.ic_filter); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    icon?.mutate()?.setTint(MaterialColors.getColor(toolbar, if (filter != 0) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                if (shown.any { it !in selected }) menu.add(0, 5, 1, "Select all")
                if (shown.any { it in selected }) menu.add(0, 10, 1, "Unselect all")
                if (files.any { it !in selected }) menu.add(0, 6, 2, "Remove unselected from set")
                menu.add(0, 4, 2, "Clear set")
            }
        }
        menu.add(0, 2, 3, "Camera card folder…")
        menu.add(0, 7, 4, "Help")
        menu.add(0, 8, 5, "About")
        for (k in 0 until menu.size()) menu.getItem(k).isEnabled = !running || menu.getItem(k).itemId in listOf(2, 7, 8)
    }

    private fun showMode() {
        singleBox.visibility = if (single) View.VISIBLE else View.GONE
        backToGrid.isEnabled = single
        updateCount()
    }

    // Points the sliders at what they now edit: the shown file's settings in the single view, the all-files settings in the grid.
    private fun selectTarget() {
        val uri = shown.getOrNull(cur)
        v = when {
            single && uri != null -> edits[uri]?.v ?: g.copyOf()
            editSelected && selected.isNotEmpty() -> settingsFor(selected.first()).copyOf()  // the sliders start from the first selected file
            else -> g
        }
        for (i in 0 until n) setSlider(i)
        syncChips()
        updateMode()
    }

    // The first change in the single view makes the shown file individual; later changes in the grid leave it alone.
    private fun adopt() {
        if (!single || shown.isEmpty()) return
        val uri = shown[cur]
        if (!edits.containsKey(uri)) { edits[uri] = Edit(v); updateMode() }
    }

    // Back closes a tool first, then the single view.
    private fun back() {
        when {
            geoMode -> setGeoMode(false)
            drawMode -> setDrawMode(false)
            single -> exitSingle()
        }
    }

    private fun toggle(uri: Uri) {
        if (running) return
        if (!selected.remove(uri)) selected.add(uri)
        selectionChanged()
    }

    private fun selectionChanged() {
        if (selected.isEmpty()) editSelected = false
        if (!single) selectTarget()
        updateCount()
        updateMode()
        updateSelectBtn()
        for (k in 0 until grid.childCount) grid.getChildAt(k).let { c -> (c.tag as? Uri)?.let { bindCell(c as FrameLayout, it) }; (c.tag as? Dive)?.let { bindHeader(c as LinearLayout, it) } }
    }

    private fun updateSelectBtn() {
        val on = shown.getOrNull(cur)?.let { it in selected } == true
        val color = { attr: Int -> MaterialColors.getColor(toolbar, attr) }
        selectBtn.text = if (on) "Selected" else "Select"
        selectBtn.icon = if (on) getDrawable(R.drawable.ic_check) else null
        selectBtn.backgroundTintList = ColorStateList.valueOf(color(if (on) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorSurfaceContainerHighest))
        val fg = color(if (on) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface)
        selectBtn.setTextColor(fg); selectBtn.iconTint = ColorStateList.valueOf(fg)
        selectBtn.contentDescription = if (on) "Selected for export. Tap to unselect." else "Not selected. Tap to select for export."
    }

    private fun removeUnselected() {
        if (running) return
        val gone = files.filter { it !in selected }
        files = files.filter { it in selected }
        gone.forEach { edits.remove(it); geos.remove(it) }
        cur = 0
        rebuild()
        selectionChanged()
        say("${gone.size} removed from the set")
    }

    // The files Correct will save: in the single view the one shown, in the grid the selected ones it shows.
    private fun exportTargets() = if (single) listOfNotNull(shown.getOrNull(cur)) else shown.filter { it in selected }

    // In selection mode a slider or preset change sets those values on every selected file, making each individual.
    private fun spread(only: Int?) {
        if (single || !editSelected || selected.isEmpty()) return
        for (u in selected) {
            val e = edits.getOrPut(u) { Edit(g.copyOf()) }
            if (only != null) e.v[only] = v[only] else v.copyInto(e.v)
        }
        updateMode()
    }

    private fun resetIndividual() {
        if (!single && editSelected) {
            val n = selected.count { edits.remove(it) != null }
            selectTarget(); render()
            say("$n file${if (n == 1) "" else "s"} use the settings for all files again")
            return
        }
        val uri = shown.getOrNull(cur) ?: return
        edits.remove(uri)
        overlay.line = null
        selectTarget()
        updateDrawBar()
        render()
        say("This ${if (videoOf[uri] == true) "video" else "photo"} uses the settings for all files again")
    }

    private fun updateMode() {
        val uri = shown.getOrNull(cur)
        val one = single && uri != null
        val e = if (one) edits[uri] else null
        val group = !single && editSelected && selected.isNotEmpty()
        val bg: Int; val fg: Int; val sub: Int
        if (group) {
            bg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorPrimaryContainer)
            fg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorOnPrimaryContainer); sub = fg
            modeIcon.setImageResource(R.drawable.ic_tune)
            modeTitle.text = "Only the ${selected.size} selected"
            modeSub.text = "Each change is set on all selected files and makes them individual (✱)"
        } else if (one) {
            bg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorPrimaryContainer)
            fg = MaterialColors.getColor(modeBar, com.google.android.material.R.attr.colorOnPrimaryContainer); sub = fg
            modeIcon.setImageResource(R.drawable.ic_tune)
            modeTitle.text = "Only this ${if (videoOf[uri] == true) "video" else "photo"}"
            modeSub.text = when {
                e == null -> "Follows the settings for all files. Any change makes it individual."
                e.line != null -> "Individual settings with a ${if (e.line!!.getOrElse(4) { 0f } == 0f) "linear" else "circle"} gradient"
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
        modeReset.visibility = if (e != null || (group && selected.any { edits.containsKey(it) })) View.VISIBLE else View.GONE
        modeSwitch.visibility = if (!single && selected.isNotEmpty()) View.VISIBLE else View.GONE
        modeSwitch.text = if (group) "Edit all" else "Edit selected"
        modeSwitch.setTextColor(fg)
    }

    private fun enterSingle() {
        single = true
        showMode()
        loadPreview()
    }

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        overlay.drawing = on
        overlay.kind = drawKind
        overlay.line = edits[shown.getOrNull(cur)]?.line
        drawBar.visibility = if (on) View.VISIBLE else View.GONE
        histo.visibility = if (on || player?.playWhenReady == true) View.GONE else View.VISIBLE  // the hint sits at the top while drawing
        findViewById<View>(R.id.prev).visibility = if (on) View.GONE else View.VISIBLE  // keep the edges free for drawing
        findViewById<View>(R.id.next).visibility = findViewById<View>(R.id.prev).visibility
        compareBtn.visibility = if (on) View.GONE else View.VISIBLE  // compare shrinks to its icon to make room for ✓ ✕
        compareIcon.visibility = if (on) View.VISIBLE else View.GONE
        updateDrawBar()
    }

    // Each tool is dark, or accent-filled when its gradient is applied to this photo. Pressing one opens drawing mode:
    // the tools join one pill with ✓ (done, keep) and ✕ (remove the gradient); the tool being drawn with is filled.
    private fun updateDrawBar() {
        val applied = edits[shown.getOrNull(cur)]?.line?.getOrElse(4) { 0f }?.toInt() ?: -1
        drawHint.text = when (drawKind) {
            0 -> "Drag a line: ● full effect → ○ none"
            1 -> "Drag from the centre: full inside, none at the circle"
            else -> "Drag from the centre: none inside, full at the circle"
        }
        val color = { attr: Int -> MaterialColors.getColor(gradientGroup, attr) }
        val primary = color(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary)
        val dark = color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val onDark = color(com.google.android.material.R.attr.colorOnSurface)
        TransitionManager.beginDelayedTransition(gradientGroup, AutoTransition().setDuration(150))
        gradientSave.visibility = if (drawMode) View.VISIBLE else View.GONE
        gradientDiscard.visibility = gradientSave.visibility
        gradientGroup.background = if (drawMode) GradientDrawable().apply { setColor(dark); cornerRadius = 28 * dp } else null
        for ((k, b) in gradBtns.withIndex()) {
            val filled = if (drawMode) k == drawKind else k == applied
            b.backgroundTintList = ColorStateList.valueOf(if (filled) primary else if (drawMode) Color.TRANSPARENT else dark)
            b.iconTint = ColorStateList.valueOf(if (filled) onPrimary else if (k == applied) primary else onDark)  // applied but not drawn with: accent icon
        }
        (gradientSave as MaterialButton).iconTint = ColorStateList.valueOf(onDark)
        (gradientDiscard as MaterialButton).iconTint = ColorStateList.valueOf(onDark)
    }

    // Keeps the overlay's idea of where the photo sits in sync with the image view (fit-center).
    private fun syncOverlay() {
        val b = srcBmp ?: return
        val w = image.width.toFloat(); val h = image.height.toFloat()
        if (w == 0f || h == 0f) return
        val c = shownCrop() ?: floatArrayOf(0f, 0f, 1f, 1f)
        val dw = b.width * (c[2] - c[0]); val dh = b.height * (c[3] - c[1])
        val sc = minOf(w / dw, h / dh)
        val left = (w - dw * sc) / 2 - c[0] * b.width * sc; val top = (h - dh * sc) / 2 - c[1] * b.height * sc  // where the whole picture would sit
        overlay.imgRect.set(left, top, left + b.width * sc, top + b.height * sc); overlay.invalidate()
        cropOverlay.imgRect.set(overlay.imgRect); cropOverlay.invalidate()
    }

    private fun exitSingle() {
        releasePlayer()
        fileSeq++; frameSeq++
        frameExec.execute { mmr?.release(); mmr = null }
        if (geoMode) setGeoMode(false)
        single = false
        setDrawMode(false)
        showMode()
        selectTarget()
        val back = shown.getOrNull(cur)
        rebuild()  // edits may have changed what the filter shows
        back?.let { items.indexOf(it).takeIf { p -> p >= 0 }?.let { p -> grid.scrollToPosition(p) } }
    }

    private fun removeCurrent() {
        if (running || shown.isEmpty()) return
        val gone = shown[cur]
        edits.remove(gone); geos.remove(gone); selected.remove(gone)
        files = files - gone
        shown = shown - gone; rebuild(keepOrder = true)
        cur = cur.coerceAtMost(shown.size - 1).coerceAtLeast(0)
        if (shown.isEmpty()) exitSingle() else { updateCount(); loadPreview() }
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

    private fun userChanged(i: Int) {
        adopt()
        spread(i)
        syncChips()  // checks a preset chip only if the settings still match it
        render()
    }

    private fun applyPreset(name: String) {
        val o = presets.optJSONObject(name) ?: return
        adopt()
        for (i in 0 until n) { v[i] = o.optDouble(ids[i], base[i].toDouble()).toFloat(); setSlider(i) }
        spread(null)
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
        val kind = line.getOrElse(4) { 0f }
        if (kind != 0f) {  // circle around the start point, radius to the end point
            val inv = 1f / sqrt(l2); val yy = y + .5f - ay
            for (x in 0 until w) {
                val xx = x + .5f - ax; val r = sqrt(xx * xx + yy * yy) * inv
                dst[off + x] = shade(src[off + x], m, pop, if (kind == 1f) 1f - r else r)
            }
            return
        }
        val t0 = ((.5f - ax) * dx + (y + .5f - ay) * dy) / l2; val dt = dx / l2
        for (x in 0 until w) dst[off + x] = shade(src[off + x], m, pop, 1f - (t0 + x * dt))
    }

    private fun correct(px: IntArray, w: Int, h: Int, s: FloatArray, line: FloatArray?): IntArray {
        val m = matrix(s); val pop = s[6] / 100f; val l = usableLine(line, w, h)
        val out = IntArray(px.size)
        for (y in 0 until h) correctRow(px, out, y * w, w, y, h, m, pop, l)
        return out
    }

    // One more clockwise quarter turn of a point, gradient (x0, y0, x1, y1, kind) or crop (l, t, r, b), all in 0..1.
    private fun turnLine(l: FloatArray) = floatArrayOf(1f - l[1], l[0], 1f - l[3], l[2], l.getOrElse(4) { 0f })
    private fun turnCrop(c: FloatArray) = floatArrayOf(1f - c[3], c[0], 1f - c[1], c[2])
    private fun <T> times(n: Int, x: T, f: (T) -> T): T { var r = x; repeat(n) { r = f(r) }; return r }

    // Pixels turned clockwise by 0, 90, 180 or 270 degrees.
    private fun turn(px: IntArray, w: Int, h: Int, rot: Int): Triple<IntArray, Int, Int> {
        if (rot == 0) return Triple(px, w, h)
        val out = IntArray(px.size)
        when (rot) {
            90 -> for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = px[y * w + x]
            180 -> for (k in px.indices) out[px.size - 1 - k] = px[k]
            else -> for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = px[y * w + x]
        }
        return if (rot == 180) Triple(out, w, h) else Triple(out, h, w)
    }

    private fun cropBox(c: FloatArray, w: Int, h: Int): IntArray {
        val x0 = (c[0] * w).toInt().coerceIn(0, w - 1); val y0 = (c[1] * h).toInt().coerceIn(0, h - 1)
        return intArrayOf(x0, y0, (c[2] * w).toInt().coerceIn(x0 + 1, w), (c[3] * h).toInt().coerceIn(y0 + 1, h))
    }

    private fun cropPx(px: IntArray, w: Int, h: Int, c: FloatArray?): Triple<IntArray, Int, Int> {
        if (c == null) return Triple(px, w, h)
        val (x0, y0, x1, y1) = cropBox(c, w, h)
        val cw = x1 - x0; val out = IntArray(cw * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(px, y * w + x0, out, (y - y0) * cw, cw)
        return Triple(out, cw, y1 - y0)
    }

    // A thumbnail as exported: turned, corrected (gradient in turned coordinates) and cropped.
    private fun thumbPixels(t: Thumb, s: FloatArray?, line: FloatArray?, g: Geo?): Triple<IntArray, Int, Int> {
        val (px, w, h) = turn(t.px, t.w, t.h, g?.rot ?: 0)
        return cropPx(if (s == null) px else correct(px, w, h, s, line), w, h, g?.crop)
    }

    private fun settingsFor(uri: Uri) = edits[uri]?.v ?: g
    private fun keyFor(uri: Uri) = (settingsFor(uri).contentHashCode() * 31 + (edits[uri]?.line?.contentHashCode() ?: 0)) * 31 +
        (geos[uri]?.let { it.rot * 7 + (it.crop?.contentHashCode() ?: 0) } ?: 0)

    // Live playback: the colour LUT (none while holding for the original) and the file's rotation.
    private fun videoEffects(): List<Effect> {
        val rot = geos[shown.getOrNull(cur)]?.rot ?: 0
        return (if (holding) listOf() else listOf<Effect>(lut(matrix(), v[6] / 100f))) +
            (if (rot != 0) listOf<Effect>(ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - rot).toFloat()).build()) else listOf())
    }

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
        val s = v.copyOf(); val line = edits[shown.getOrNull(cur)]?.line?.copyOf()
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
        val whole = if (holding) srcBmp else outBmp ?: srcBmp
        val c = shownCrop()
        val b = if (whole != null && c != null) cropBox(c, whole.width, whole.height).let { (x0, y0, x1, y1) -> Bitmap.createBitmap(whole, x0, y0, x1 - x0, y1 - y0) } else whole
        val h = if (c != null && b != null) hist(IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) })
            else if (holding) srcHist else outHist ?: srcHist
        image.setImageBitmap(b); fullImg.setImageBitmap(b)
        histo.data = h; fullHisto.data = h
        syncOverlay()
    }

    private fun hold(on: Boolean) {
        if (holding == on) return
        holding = on
        originalTag.visibility = if (on) View.VISIBLE else View.GONE
        if (overlay.drawing) drawBar.visibility = if (on) View.INVISIBLE else View.VISIBLE
        fullOriginal.visibility = originalTag.visibility
        display()
        if (!single) for (k in 0 until grid.childCount) (grid.getChildAt(k) as? FrameLayout)?.let { c -> (c.tag as? Uri)?.let { bindCell(c, it) } }
        if (player != null) { ui.removeCallbacks(applyFx); applyFx.run() }
    }

    // ---------- grid thumbnails ----------

    private fun timeLabel(uri: Uri) = if (showTimes) meta[uri]?.time?.takeIf { it >= 0 }?.let { clock.format(it) } else null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun bindHeader(h: LinearLayout, d: Dive) {
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val videos = d.all.count { meta[it]?.video == true }; val photos = d.all.size - videos
        val parts = mutableListOf<String>()
        if (d.n > 0) parts += "${day.format(d.start)} · ${clock.format(d.start)}–${clock.format(d.end)}"
        parts += "$photos photo${if (photos == 1) "" else "s"}" + if (videos > 0) " · $videos video${if (videos == 1) "" else "s"}" else ""
        if (d.shownHere.size < d.all.size) parts += "${d.shownHere.size} shown"
        parts += "${d.shownHere.count { it in selected }} selected"
        GridViews.bindHeader(h, if (d.n > 0) "Dive ${d.n}" else "Unknown time", parts.joinToString(" · "),
            if (d.shownHere.isNotEmpty() && selected.containsAll(d.shownHere)) "Unselect" else "Select")
    }

    // Select (or deselect) every shown file of one dive, e.g. to edit or save just that dive.
    private fun selectDive(d: Dive) {
        if (running) return
        if (selected.containsAll(d.shownHere)) selected.removeAll(d.shownHere.toSet()) else selected.addAll(d.shownHere)
        selectionChanged()
    }

    private fun cellFor(uri: Uri) = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.tag == uri } as? FrameLayout

    private fun bindCell(cell: FrameLayout, uri: Uri) {
        val img = cell.getChildAt(0) as ImageView
        val where = cell.getChildAt(1) as LinearLayout
        cell.getChildAt(3).visibility = if (edits.containsKey(uri)) View.VISIBLE else View.GONE
        cell.getChildAt(4).visibility = View.GONE
        img.colorFilter = if (uri in selected) null else grey  // included but not selected: grey
        img.alpha = if (uri in selected) 1f else .55f
        val t = thumbs.get(uri)
        if (t == null) {
            img.setImageBitmap(null); where.visibility = View.GONE; GridViews.bindBottom(cell, null, timeLabel(uri))
            if (loading.add(uri)) {
                val s = settingsFor(uri).copyOf(); val line = edits[uri]?.line?.copyOf(); val gg = geos[uri]?.copy(); val key = keyFor(uri)
                thumbLoader.execute {
                    val th = loadThumb(uri, s, line, gg, key)
                    runOnUiThread { loading.remove(uri); thumbs.put(uri, th); cellFor(uri)?.let { bindCell(it, uri) } }
                }
            }
            return
        }
        img.setImageBitmap(if (holding && t.w > 0) thumbPixels(t, null, null, geos[uri]).let { (px, w, h) -> Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) } else t.out)
        val card = cardFiles.containsKey(t.outName)
        where.visibility = if (t.phone || card) View.VISIBLE else View.GONE
        where.getChildAt(0).visibility = if (t.phone) View.VISIBLE else View.GONE
        where.getChildAt(1).visibility = if (card) View.VISIBLE else View.GONE
        GridViews.bindBottom(cell, if (t.dur >= 0) "%d:%02d".format(t.dur / 60000, t.dur / 1000 % 60) else null, timeLabel(uri))
        if (t.key != keyFor(uri)) renderThumbs(listOf(uri), renderSeq)
    }

    private fun loadThumb(uri: Uri, s: FloatArray, line: FloatArray?, gg: Geo?, key: Int): Thumb {
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
        val t = Thumb(px, bmp.width, bmp.height, dur, outName, phone, null, key)
        t.out = thumbPixels(t, s, line, gg).let { (o, w, h) -> Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888) }
        return t
    }

    // Re-renders thumbnails whose settings changed; each file uses its own settings, so grid edits skip individual files.
    private fun renderThumbs(list: List<Uri>, seq: Int) {
        val jobs = list.map { uri -> Triple(uri, settingsFor(uri).copyOf(), edits[uri]?.line?.copyOf()) to (geos[uri]?.copy() to keyFor(uri)) }
        thumbRenderer.execute {
            for ((job, gk) in jobs) {
                val (uri, s, line) = job; val (gg, key) = gk
                if (seq != renderSeq) return@execute
                val t = thumbs.get(uri) ?: continue
                if (t.key == key || t.w == 0) continue
                val bmp = thumbPixels(t, s, line, gg).let { (o, w, h) -> Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888) }
                runOnUiThread { if (seq == renderSeq) { t.out = bmp; t.key = key; if (!holding) (cellFor(uri)?.getChildAt(0) as? ImageView)?.setImageBitmap(bmp) } }
            }
        }
    }

    // ---------- single view: photo or video frame ----------

    private fun loadPreview() {
        if (shown.isEmpty()) return
        releasePlayer()
        val uri = shown[cur]
        val video = isVideo(uri)
        val fs = ++fileSeq
        val seq = ++frameSeq
        val rot = geos[uri]?.rot ?: 0
        geoMode = false; cropOverlay.active = false; geoGroup.visibility = View.GONE; trimRow.visibility = View.GONE
        fileTitle = nameOf(uri); fileSub = "${cur + 1} of ${shown.size}"
        updateToolbar()
        refreshFileInfo()
        prevBtn.visibility = if (shown.size > 1) View.VISIBLE else View.GONE
        nextBtn.visibility = prevBtn.visibility
        videoRow.visibility = if (video) View.VISIBLE else View.GONE
        frameTools.visibility = videoRow.visibility
        compareBtn.visibility = View.VISIBLE
        gradientGroup.visibility = if (video) View.GONE else View.VISIBLE  // gradients are for photos
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
                val frames = r?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull() ?: 0f
                videoFps = if (frames > 0 && d > 0) frames * 1000f / d else 30f
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
                deliver(seq, bmp, rot)
            }
        }
    }

    private fun loadFrame(exact: Boolean) {
        val seq = ++frameSeq
        val pos = videoPos
        val rot = geos[shown.getOrNull(cur)]?.rot ?: 0
        frameExec.execute {
            if (seq != frameSeq) return@execute
            val bmp = runCatching {
                val r = mmr!!
                (r.getScaledFrameAtTime(pos * 1000, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
                    ?: r.getScaledFrameAtTime(pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440))!!.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return@execute
            deliver(seq, bmp, rot)
        }
    }

    // Runs on frameExec: extracts pixels + histogram, then hands the new source frame to the UI.
    private fun deliver(seq: Int, raw: Bitmap?, rot: Int) {
        val bmp = if (raw == null || rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val px = bmp?.let { IntArray(it.width * it.height).also { a -> it.getPixels(a, 0, it.width, 0, 0, it.width, it.height) } }
        val h = px?.let { hist(it) }
        runOnUiThread {
            if (seq != frameSeq) return@runOnUiThread
            if (bmp == null) { say("Can't open ${fileTitle}"); return@runOnUiThread }
            baseBmp = raw; srcBmp = bmp; srcPx = px; srcHist = h; outBmp = null; outHist = null
            syncOverlay()
            if (full.isShowing) orientFull()
            render()
        }
    }

    private fun times() = "%d:%02d.%02d / %d:%02d".format(videoPos / 60000, videoPos / 1000 % 60, videoPos % 1000 / 10, videoDur / 60000, videoDur / 1000 % 60)

    private fun trimStart() = geos[shown.getOrNull(cur)]?.trimStart?.takeIf { it >= 0 } ?: 0L
    private fun trimEnd() = geos[shown.getOrNull(cur)]?.trimEnd?.takeIf { it >= 0 } ?: videoDur

    // The crop shown in the preview (none while editing it).
    private fun shownCrop() = if (geoMode) null else geos[shown.getOrNull(cur)]?.crop

    // ---------- crop, trim and rotate ----------

    private fun setGeoMode(on: Boolean) {
        val uri = shown.getOrNull(cur) ?: return
        val video = videoOf[uri] == true
        if (on) { setDrawMode(false); if (player?.playWhenReady == true) pause() }
        geoMode = on
        cropOverlay.active = on && !video
        cropOverlay.crop = geos[uri]?.crop?.copyOf() ?: floatArrayOf(0f, 0f, 1f, 1f)
        geoGroup.visibility = if (on) View.VISIBLE else View.GONE
        gradientGroup.visibility = if (on || video) View.GONE else View.VISIBLE
        compareBtn.visibility = if (on) View.GONE else View.VISIBLE
        frameTools.visibility = if (video && !on) View.VISIBLE else View.GONE
        videoRow.visibility = if (video && !on) View.VISIBLE else View.GONE
        trimRow.visibility = if (video && on) View.VISIBLE else View.GONE
        drawBar.visibility = if (on) View.VISIBLE else View.GONE
        drawHint.text = if (video) "Drag the ends to trim · ↻ turns 90°" else "Drag a corner or edge to crop · ↻ turns 90°"
        histo.visibility = if (on) View.GONE else View.VISIBLE
        prevBtn.visibility = if (on || shown.size < 2) View.GONE else View.VISIBLE
        nextBtn.visibility = prevBtn.visibility
        val color = { attr: Int -> MaterialColors.getColor(geoGroup, attr) }
        geoGroup.background = GradientDrawable().apply { setColor(color(com.google.android.material.R.attr.colorSurfaceContainerHighest)); cornerRadius = 28 * dp }
        if (video && on) {
            trimSlider.valueTo = maxOf(1f, videoDur.toFloat())
            trimSlider.values = listOf(trimStart().toFloat(), trimEnd().toFloat().coerceAtLeast(trimStart() + 1f))
            updateTrimText()
        }
        display()
        updateToolbar()
    }

    private fun updateTrimText() {
        val v = trimSlider.values; val a = v[0].toLong(); val b = v[1].toLong()
        val f = { t: Long -> "%d:%02d".format(t / 60000, t / 1000 % 60) }
        trimTime.text = "${f(a)}–${f(b)} (${f(b - a)})"
    }

    // ✓: keep the crop or trim as set (a full frame or full length counts as none).
    private fun commitGeo() {
        val uri = shown.getOrNull(cur) ?: return
        val gg = geos.getOrPut(uri) { Geo() }
        if (videoOf[uri] == true) {
            val v = trimSlider.values
            gg.trimStart = v[0].toLong().takeIf { it > 50 } ?: -1
            gg.trimEnd = v[1].toLong().takeIf { it < videoDur - 50 } ?: -1
            if (videoPos < trimStart() || videoPos > trimEnd()) { videoPos = trimStart(); loadFrame(true) }
        } else {
            val c = cropOverlay.crop
            gg.crop = if (c[0] < .005f && c[1] < .005f && c[2] > .995f && c[3] > .995f) null else c.copyOf()
        }
        if (!gg.isSet) geos.remove(uri)
        setGeoMode(false)
    }

    // ✕: undo crop, trim and rotation for this file.
    private fun resetGeo() {
        val uri = shown.getOrNull(cur) ?: return
        while ((geos[uri]?.rot ?: 0) != 0) turnCurrent()
        geos.remove(uri)
        setGeoMode(false)
    }

    // ↻: turns the file 90° clockwise; its crop and gradient turn with it.
    private fun turnCurrent() {
        val uri = shown.getOrNull(cur) ?: return
        val gg = geos.getOrPut(uri) { Geo() }
        gg.rot = (gg.rot + 90) % 360
        gg.crop = gg.crop?.let(::turnCrop)
        if (geoMode && videoOf[uri] != true) cropOverlay.crop = turnCrop(cropOverlay.crop)
        edits[uri]?.let { e -> e.line = e.line?.let(::turnLine) }
        val raw = baseBmp ?: return
        val seq = ++frameSeq; val rot = gg.rot
        frameExec.execute { deliver(seq, raw, rot) }
    }

    // ---------- video frames ----------

    private fun stepFrame(d: Int) {
        if (player?.playWhenReady == true) pause()
        val idx = (videoPos * videoFps / 1000f).roundToLong() + d
        videoPos = (idx * 1000f / videoFps).roundToLong().coerceIn(0L, videoDur)
        timeline.value = videoPos.toFloat().coerceIn(timeline.valueFrom, timeline.valueTo); timeTv.text = times()
        loadFrame(true)
    }

    // Saves the shown frame as a new, uncorrected photo (turned like the video, timestamp = video start + position),
    // adds it to the set as selected, and offers to open it.
    private fun saveFrame() {
        if (player?.playWhenReady == true) pause()
        val uri = shown.getOrNull(cur) ?: return
        val pos = videoPos; val rot = geos[uri]?.rot ?: 0
        val base = (meta[uri]?.name ?: nameOf(uri)).substringBeforeLast('.')
        val start = meta[uri]?.time ?: -1L
        frameExec.execute {
            val result = runCatching {
                val r = mmr!!
                var bmp = (r.getFrameAtTime(pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: r.getFrameAtTime(pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))!!
                if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                val tmp = File(cacheDir, "frame.jpg")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                val at = if (start >= 0) start + pos else -1L
                if (at >= 0) ExifInterface(tmp.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifClock.format(at)); setAttribute(ExifInterface.TAG_DATETIME, exifClock.format(at))
                    setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(at % 1000)); saveAttributes()
                }
                val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_frame_%d-%02d-%03d.jpg".format(pos / 60000, pos / 1000 % 60, pos % 1000))
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DiveFix/Frames")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (at >= 0) put(MediaStore.MediaColumns.DATE_TAKEN, at - TimeZone.getDefault().getOffset(at))
                })!!
                contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                tmp.delete()
                out
            }
            runOnUiThread {
                result.onSuccess { u ->
                    videoOf[u] = false
                    addFiles(listOf(u), select = true)
                    say("Frame saved to DCIM/DiveFix/Frames and selected", "Open") { shown.indexOf(u).takeIf { it >= 0 }?.let { cur = it; loadPreview() } }
                }.onFailure { say("Couldn't save the frame (${it.message})") }
            }
        }
    }

    // ---------- video playback (live correction through the same LUT as the export) ----------

    private fun play() {
        if (running) { say("Playback is paused while files are being corrected"); return }
        if (videoPos < trimStart() || videoPos >= trimEnd() - 200) videoPos = trimStart()
        // Always start a fresh player at the current position: resuming a paused one with live colour effects can stall.
        if (player != null) releasePlayer()
        run {
            val p = ExoPlayer.Builder(this).build()
            player = p
            p.setVideoSurfaceView(surface)
            p.setVideoEffects(videoEffects())
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
            p.setMediaItem(MediaItem.fromUri(shown[cur]), videoPos)
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

    private var zoom = 1f

    // Scales the full-screen picture around a focus point (in the box's coordinates) and moves it by (panX, panY).
    private fun zoomBy(factor: Float, focusX: Float, focusY: Float, panX: Float, panY: Float) {
        val z = (zoom * factor).coerceIn(1f, 8f)
        val k = z / zoom
        var tx = focusX - (focusX - fullImg.translationX) * k + panX
        var ty = focusY - (focusY - fullImg.translationY) * k + panY
        val w = rotBox.width.toFloat(); val h = rotBox.height.toFloat()
        tx = tx.coerceIn(w - w * z, 0f); ty = ty.coerceIn(h - h * z, 0f)  // keep the picture on screen
        zoom = z
        fullImg.pivotX = 0f; fullImg.pivotY = 0f
        fullImg.scaleX = z; fullImg.scaleY = z; fullImg.translationX = tx; fullImg.translationY = ty
    }

    private fun resetZoom() {
        zoom = 1f
        fullImg.scaleX = 1f; fullImg.scaleY = 1f; fullImg.translationX = 0f; fullImg.translationY = 0f
    }

    // Turn the image 90° when its orientation doesn't match the screen's, so it fills the screen without rotating the app.
    private fun orientFull() {
        val w = fullRoot.width; val h = fullRoot.height; val b = (fullImg.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: srcBmp ?: return
        if (w == 0 || h == 0) return
        val rotate = (b.width > b.height) != (w > h) && b.width != b.height
        rotBox.layoutParams = FrameLayout.LayoutParams(if (rotate) h else w, if (rotate) w else h, Gravity.CENTER)
        rotBox.rotation = if (rotate) 90f else 0f
        resetZoom()
    }

    // ---------- files & destinations ----------

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun isVideo(uri: Uri) = contentResolver.getType(uri)?.startsWith("video") == true

    private fun outName(uri: Uri, video: Boolean) = nameOf(uri).substringBeforeLast('.') + "_fix" + if (video) ".mp4" else ".jpg"

    // Reads names, capture times, video lengths and saved state in the background, then regroups the grid.
    private fun scanMeta(list: List<Uri>) = metaExec.execute {
        for (u in list) {
            if (meta.containsKey(u)) continue
            val name = nameOf(u); val video = isVideo(u)
            val dur = if (!video) -1L else runCatching {
                MediaMetadataRetriever().run { setDataSource(this@MainActivity, u); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); release(); d }
            }.getOrDefault(0L)
            val phone = runCatching { existing(name.substringBeforeLast('.') + "_fix" + if (video) ".mp4" else ".jpg", video) != null }.getOrDefault(false)
            meta[u] = Meta(name, video, captureTime(u, name, video), dur, phone)
        }
        runOnUiThread { if (!single) rebuild() }
    }

    // Capture time as camera wall-clock millis (formatted in UTC, so photos and videos line up). DJI and most phones put it in the
    // file name; otherwise the photo's EXIF, the video's metadata or the file date is used.
    private fun captureTime(u: Uri, name: String, video: Boolean): Long {
        if (!video) runCatching {
            val exif = contentResolver.openInputStream(u)!!.use { ExifInterface(it) }
            val sub = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
            return exifClock.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)!!)!!.time + sub
        }
        Regex("(20\\d{6})[_-]?(\\d{6})").find(name)?.let { m -> runCatching { return wall.parse(m.groupValues[1] + m.groupValues[2])!!.time } }
        val local = { t: Long -> t + TimeZone.getDefault().getOffset(t) }
        if (!video) runCatching {
            val exif = contentResolver.openInputStream(u)!!.use { ExifInterface(it) }.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)!!
            return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(exif)!!.time
        }
        if (video) runCatching {
            val d = MediaMetadataRetriever().run { setDataSource(this@MainActivity, u); val x = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE); release(); x!! }
            val t = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(d)!!.time
            if (t > 0) return local(t)
        }
        return runCatching {
            contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)!!.use { c -> c.moveToFirst(); c.getLong(0) }.takeIf { it > 0 }!!.let(local)
        }.getOrDefault(-1L)
    }

    // Recomputes dives, the filtered and sorted list, and the grid rows.
    private fun rebuild(keepOrder: Boolean = false) {
        val time = { u: Uri -> meta[u]?.time ?: -1L }
        val dives = mutableListOf<Dive>(); val diveOf = HashMap<Uri, Dive>()
        var group = mutableListOf<Uri>(); var start = 0L; var end = 0L
        val close = { if (group.isNotEmpty()) dives += Dive(dives.size + 1, start, end, group) }
        for (u in files.filter { time(it) >= 0 }.sortedBy(time)) {
            val t = time(u)
            if (group.isEmpty() || t - end >= 30 * 60_000L || (t - start >= 100 * 60_000L && t - end >= 10 * 60_000L)) { close(); group = mutableListOf(); start = t; end = t }
            group += u; end = maxOf(end, t + maxOf(0L, meta[u]?.dur ?: 0L))
        }
        close()
        dives.forEach { d -> d.all.forEach { diveOf[it] = d } }
        val unknown = files.filter { time(it) < 0 }.let { if (it.isEmpty()) null else Dive(0, 0, 0, it) }
        unknown?.all?.forEach { diveOf[it] = unknown }

        val saved = { u: Uri -> meta[u]?.phone == true || meta[u]?.let { cardFiles.containsKey(outName(u, it.video)) } == true }
        val pass = { u: Uri -> when (filter) { 1 -> !saved(u); 2 -> saved(u); 3 -> edits.containsKey(u); else -> true } }
        if (!keepOrder) shown = when (sort) {
            2 -> files.filter(pass).sortedBy { meta[it]?.name?.lowercase() ?: "" }
            else -> files.filter(pass).sortedWith(compareBy<Uri>({ time(it) < 0 }, { if (sort == 1) -time(it) else time(it) }))
        }
        selected.retainAll(files.toSet())
        val grouped = groupDives && sort != 2 && files.all { meta.containsKey(it) }  // group once every date is known
        items = if (!grouped) shown else buildList {
            val order = if (sort == 1) dives.reversed() else dives
            for (d in order + listOfNotNull(unknown)) {
                d.shownHere = shown.filter { diveOf[it] === d }
                if (d.shownHere.isNotEmpty()) { add(d); addAll(d.shownHere) }
            }
        }
        if (grouped) shown = items.filterIsInstance<Uri>()
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun showSortSheet() {
        val sheet = BottomSheetDialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt()) }
        val heading = { t: String -> TextView(this).apply {
            text = t; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall); setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        } }
        val chipRow = { labels: List<String>, checked: Int, pick: (Int) -> Unit -> ChipGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            labels.forEachIndexed { k, l ->
                val c = layoutInflater.inflate(R.layout.chip_preset, this, false) as Chip
                c.text = l; c.id = View.generateViewId(); addView(c); if (k == checked) c.isChecked = true
                c.setOnCheckedChangeListener { _, on -> if (on) pick(k) }
            }
        } }
        lateinit var groupSwitch: MaterialSwitch
        box.addView(heading("Show"))
        box.addView(chipRow(listOf("All", "Raw", "Saved", "Individual ✱"), filter) { filter = it; rebuild(); selectionChanged() })
        box.addView(TextView(this).apply {
            text = "Raw: no corrected copy yet · Saved: corrected copy on phone or card"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        box.addView(heading("Sort"))
        box.addView(chipRow(listOf("Oldest first", "Newest first", "Name"), sort) {
            sort = it; prefs.edit().putInt("sort", it).apply(); groupSwitch.isEnabled = it != 2; rebuild(); grid.scrollToPosition(0)
        })
        groupSwitch = MaterialSwitch(this).apply {
            text = "Group by dive"; isChecked = groupDives; isEnabled = sort != 2
            setOnCheckedChangeListener { _, on -> groupDives = on; prefs.edit().putBoolean("groupDives", on).apply(); rebuild() }
        }
        box.addView(groupSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
        box.addView(MaterialSwitch(this).apply {
            text = "Show capture time on thumbnails"; isChecked = showTimes
            setOnCheckedChangeListener { _, on -> showTimes = on; prefs.edit().putBoolean("showTimes", on).apply(); adapter.notifyDataSetChanged() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheet.setContentView(box)
        sheet.show()
    }

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
        if (shown.isEmpty() || !single) return
        val uri = shown[cur]
        val fs = fileSeq
        thread {
            val video = isVideo(uri)
            val name = outName(uri, video)
            val phone = runCatching { existing(name, video) != null }.getOrDefault(false)
            val card = cardFiles.containsKey(name)
            val where = when { phone && card -> " · fixed on phone and card"; phone -> " · fixed on phone"; card -> " · fixed on card"; else -> "" }
            runOnUiThread { if (fs == fileSeq) { fileSub = "${cur + 1} of ${shown.size}$where"; toolbar.subtitle = fileSub } }
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
                if (!single) rebuild(); refreshFileInfo()
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
        batch = exportTargets()
        val plan = batch.map { Triple(settingsFor(it).copyOf(), edits[it]?.line?.copyOf(), geos[it]?.copy()) }  // each file with its own settings
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
            meta.forEach { (u, m) -> m.phone = runCatching { existing(outName(u, m.video), m.video) != null }.getOrDefault(m.phone) }
            runOnUiThread { if (single) rebuild(keepOrder = true) else rebuild() }
        }
    }

    private fun process(i: Int, plan: List<Triple<FloatArray, FloatArray?, Geo?>>) {
        if (!running) return
        if (cancelled || i >= batch.size) { finishBatch(); return }
        val m = matrix(plan[i].first); val pop = plan[i].first[6] / 100f
        val uri = batch[i]
        val name = nameOf(uri)
        val base = name.substringBeforeLast('.') + "_fix"
        runText.text = "${i + 1} of ${batch.size} · $name"
        progress.setProgressCompat(i * 1000 / batch.size, true)

        if (!isVideo(uri)) {
            thread {
                val tmp = File(cacheDir, "export.jpg")
                val result = runCatching {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.isMutableRequired = true
                    }
                    // The pixels stay upright as decoded; gradient and crop (stored for the turned picture) are turned back,
                    // and the turn is written as EXIF orientation, so no second full-size copy is needed.
                    val gg = plan[i].third; val rot = gg?.rot ?: 0; val back = (4 - rot / 90) % 4
                    val w = bmp.width; val h = bmp.height
                    val row = IntArray(w)
                    val line = usableLine(plan[i].second?.let { times(back, it, ::turnLine) }, w, h)
                    val box = gg?.crop?.let { cropBox(times(back, it, ::turnCrop), w, h) }
                    for (y in (box?.get(1) ?: 0) until (box?.get(3) ?: h)) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1)
                        correctRow(row, row, 0, w, y, h, m, pop, line)
                        bmp.setPixels(row, 0, w, 0, y, w, 1)
                    }
                    val out = box?.let { (x0, y0, x1, y1) -> Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0) } ?: bmp
                    tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    out.recycle(); bmp.recycle()
                    runCatching { // capture date, GPS and camera info, plus the turn
                        val src = runCatching { contentResolver.openInputStream(uri)!!.use { ExifInterface(it) } }.getOrNull()
                        ExifInterface(tmp.absolutePath).apply {
                            src?.let { sx -> exifTags.forEach { t -> sx.getAttribute(t)?.let { setAttribute(t, it) } } }
                            setAttribute(ExifInterface.TAG_ORIENTATION, when (rot) {
                                90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180
                                270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL
                            }.toString())
                            saveAttributes()
                        }
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
        val taken0 = runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)!!)!!.time
        }.getOrNull()
        mr.release()
        val gg = plan[i].third
        val start = gg?.trimStart?.takeIf { it >= 0 }; val end = gg?.trimEnd?.takeIf { it >= 0 }
        val taken = taken0?.let { it + (start ?: 0L) }  // a trimmed clip starts later
        // Bake fx() into a 33x33x33 LUT; the GPU interpolates it trilinearly per pixel. Rotation and trim come after.
        val lut = Array(33) { r -> Array(33) { g -> IntArray(33) { b -> fx(r / 32f, g / 32f, b / 32f, m, pop) } } }
        val media = MediaItem.Builder().setUri(uri).apply {
            if (start != null || end != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().apply {
                start?.let { setStartPositionMs(it) }; end?.let { setEndPositionMs(it) }
            }.build())
        }.build()
        val item = EditedMediaItem.Builder(media)
            .setEffects(Effects(listOf(), listOf<Effect>(SingleColorLut.createFromCube(lut)) +
                (if ((gg?.rot ?: 0) != 0) listOf<Effect>(ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - gg!!.rot).toFloat()).build()) else listOf())))
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
                    progress.setProgressCompat((i * 1000 + holder.progress * 10) / batch.size, true)
                    runText.text = "${i + 1} of ${batch.size} · $name · ${holder.progress}%"
                }
                ui.postDelayed(this, 500)
            }
        })
    }
}
