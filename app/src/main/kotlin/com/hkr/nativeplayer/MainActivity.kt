package com.hkr.nativeplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

data class MediaEntry(
    val title: String,
    val uri: Uri,
    val folderName: String,
    val filePath: String?,
    val durationMs: Long,
    val qualityTag: String,
    val isAudio: Boolean
)

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("hkr_player_prefs", Context.MODE_PRIVATE) }

    // Onboarding Views
    private lateinit var onboardingOverlay: LinearLayout
    private lateinit var onboardTitle: TextView
    private lateinit var onboardDesc: TextView
    private lateinit var onboardAutoScanRow: LinearLayout
    private lateinit var onboardAutoScanSwitch: Switch
    private lateinit var onboardPermActionBtn: Button
    private lateinit var onboardSkipBtn: Button
    private lateinit var onboardNextBtn: Button
    private var onboardStep: Int = 0

    // Main App Views
    private lateinit var rootLayout: LinearLayout
    private lateinit var topHeader: LinearLayout
    private lateinit var topHeaderTitle: TextView
    private lateinit var topThemeBtn: Button
    private lateinit var contentContainer: FrameLayout
    private lateinit var mainRecycler: RecyclerView
    private lateinit var moreHubScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var fabPlayAll: ImageButton
    private lateinit var bottomNavBar: LinearLayout

    // Full-screen / VLC-style Player Overlay
    private lateinit var playerOverlay: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var playerTopBar: LinearLayout
    private lateinit var playerTitleText: TextView
    private lateinit var rotateScreenBtn: ImageButton
    private lateinit var centerHudText: TextView
    private lateinit var centerHudSubText: TextView
    private lateinit var centerHudBox: LinearLayout
    private lateinit var playerBottomControls: LinearLayout
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playerSeekBar: SeekBar
    private lateinit var playPauseBtn: ImageButton
    private lateinit var repeatToggleBtn: ImageButton
    private lateinit var aspectRatioBtn: ImageButton
    private lateinit var trackInfoBtn: ImageButton
    private lateinit var moreOptionsBtn: ImageButton

    private var mediaPlayerRef: MediaPlayer? = null
    private val allVideos = mutableListOf<MediaEntry>()
    private val allAudios = mutableListOf<MediaEntry>()
    private val folderMap = linkedMapOf<String, MutableList<MediaEntry>>()
    private val historyList = mutableListOf<MediaEntry>()

    private var activeTab: String = "Video"
    private var currentQueue: List<MediaEntry> = emptyList()
    private var currentPlayingIndex: Int = -1
    private var currentAudioTrackIdx: Int = 0
    private var isDarkMode: Boolean = true
    private var autoScanEnabled: Boolean = true
    private var saveHistoryEnabled: Boolean = true
    private var repeatEnabled: Boolean = false
    private var aspectModeIndex: Int = 0
    private var playbackSpeed: Float = 1.0f
    private var isPlayerVisible: Boolean = false
    private var isLandscapeLocked: Boolean = false

    private val aspectModes = listOf("Fit", "16:9", "4:3", "Fill")

    private val thumbCache = LruCache<String, Bitmap>(140)
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val hideHudRunnable = Runnable {
        if (::centerHudBox.isInitialized) {
            centerHudBox.visibility = View.GONE
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isPlayerVisible && ::videoView.isInitialized && videoView.duration > 0) {
                val pos = videoView.currentPosition.coerceAtLeast(0)
                val dur = videoView.duration.coerceAtLeast(1)
                playerSeekBar.max = dur
                playerSeekBar.progress = pos
                currentTimeText.text = formatDuration(pos.toLong())
                totalTimeText.text = formatDuration(dur.toLong())
                if (::playPauseBtn.isInitialized) {
                    playPauseBtn.setImageDrawable(
                        makeVlcIconDrawable(if (videoView.isPlaying) "pause" else "play", Color.WHITE)
                    )
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (autoScanEnabled) {
            scanDeviceMedia()
            renderCurrentTab()
        }
        refreshOnboardingUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isDarkMode = prefs.getBoolean("dark_mode", true)
        autoScanEnabled = prefs.getBoolean("auto_scan", true)
        saveHistoryEnabled = prefs.getBoolean("save_history", true)
        val onboardingDone = prefs.getBoolean("onboarding_done", false)

        val outerContainer = FrameLayout(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        topHeaderTitle = TextView(this).apply {
            text = "HKR Player"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        topThemeBtn = Button(this).apply {
            text = if (isDarkMode) "Light" else "Dark"
            textSize = 11f
            setAllCaps(false)
            setOnClickListener {
                isDarkMode = !isDarkMode
                prefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                applyTheme()
                renderCurrentTab()
            }
        }

        topHeader.addView(topHeaderTitle)
        topHeader.addView(topThemeBtn)

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        mainRecycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(6), dp(6), dp(6), dp(76))
            clipToPadding = false
        }

        moreHubScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        settingsScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        fabPlayAll = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF8800"))
            }
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(18), dp(18), dp(18), dp(18))
            }
            setOnClickListener {
                if (allVideos.isNotEmpty()) {
                    playFromList(allVideos, 0)
                } else {
                    Toast.makeText(this@MainActivity, "Koi video nahi mila", Toast.LENGTH_SHORT).show()
                }
            }
        }

        contentContainer.addView(mainRecycler)
        contentContainer.addView(moreHubScroll)
        contentContainer.addView(settingsScroll)
        contentContainer.addView(fabPlayAll)

        bottomNavBar = buildBottomNavBar()

        rootLayout.addView(topHeader)
        rootLayout.addView(contentContainer)
        rootLayout.addView(bottomNavBar)

        playerOverlay = buildPlayerOverlay().apply {
            visibility = View.GONE
        }

        onboardingOverlay = buildOnboardingView().apply {
            visibility = if (onboardingDone) View.GONE else View.VISIBLE
        }

        outerContainer.addView(rootLayout)
        outerContainer.addView(playerOverlay)
        outerContainer.addView(onboardingOverlay)
        setContentView(outerContainer)

        applyTheme()
        updateSystemUiForPlayer()

        if (onboardingDone) {
            if (hasMediaPermission()) {
                if (autoScanEnabled) scanDeviceMedia()
            } else {
                requestNeededPermissions()
            }
            selectTab("Video")
        } else {
            onboardStep = 0
            refreshOnboardingUi()
        }

        mainHandler.post(progressRunnable)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isPlayerVisible) {
            applyAspectRatio()
        }
    }

    private fun updateSystemUiForPlayer() {
        WindowCompat.setDecorFitsSystemWindows(window, !isPlayerVisible)
        val controller = WindowInsetsControllerCompat(window, playerOverlay)
        if (isPlayerVisible) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleLandscapeFullScreen() {
        isLandscapeLocked = !isLandscapeLocked
        requestedOrientation = if (isLandscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        showHud(
            if (isLandscapeLocked) "Full Screen" else "Portrait",
            if (isLandscapeLocked) "Landscape mode" else "Vertical mode"
        )
    }

    private fun styleSeekBarVlc(seekBar: SeekBar) {
        val orange = Color.parseColor("#FF8800")
        val trackBg = Color.parseColor("#555558")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.progressTintList = ColorStateList.valueOf(orange)
            seekBar.thumbTintList = ColorStateList.valueOf(orange)
            seekBar.progressBackgroundTintList = ColorStateList.valueOf(trackBg)
            seekBar.secondaryProgressTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val thumbSize = dp(16)
        seekBar.thumb = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = orange
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val r = bounds.width().coerceAtMost(bounds.height()) / 2f
                canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(cf: ColorFilter?) {
                paint.colorFilter = cf
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = thumbSize
            override fun getIntrinsicHeight(): Int = thumbSize
        }
    }

    private fun makeVlcIconDrawable(kind: String, tintColor: Int): Drawable {
        val size = dp(48)
        return object : Drawable() {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = dpF(2.2f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                when (kind) {
                    "track" -> {
                        val w = dp(24).toFloat()
                        val h = dp(17).toFloat()
                        val l = cx - w / 2f
                        val t = cy - h / 2f
                        canvas.drawRoundRect(l, t, l + w, t + h, dpF(3f), dpF(3f), strokePaint)
                        canvas.drawLine(l + dp(4), t + dp(9), l + dp(11), t + dp(9), strokePaint)
                        canvas.drawLine(l + dp(14), t + dp(9), l + w - dp(4), t + dp(9), strokePaint)
                        canvas.drawLine(l + dp(4), t + dp(13), l + dp(15), t + dp(13), strokePaint)
                        canvas.drawLine(l + dp(18), t + dp(13), l + w - dp(4), t + dp(13), strokePaint)
                    }
                    "repeat" -> {
                        val r = dp(9).toFloat()
                        canvas.drawLine(cx - r, cy - dp(4), cx + r, cy - dp(4), strokePaint)
                        canvas.drawLine(cx + r - dp(4), cy - dp(8), cx + r, cy - dp(4), strokePaint)
                        canvas.drawLine(cx + r - dp(4), cy, cx + r, cy - dp(4), strokePaint)

                        canvas.drawLine(cx + r, cy + dp(4), cx - r, cy + dp(4), strokePaint)
                        canvas.drawLine(cx - r + dp(4), cy, cx - r, cy + dp(4), strokePaint)
                        canvas.drawLine(cx - r + dp(4), cy + dp(8), cx - r, cy + dp(4), strokePaint)
                    }
                    "pause" -> {
                        val rad = dp(23).toFloat()
                        canvas.drawCircle(cx, cy, rad, strokePaint)
                        val barW = dpF(3.5f)
                        val barH = dp(15).toFloat()
                        canvas.drawRect(
                            cx - dpF(6.5f),
                            cy - barH / 2f,
                            cx - dpF(6.5f) + barW,
                            cy + barH / 2f,
                            fillPaint
                        )
                        canvas.drawRect(
                            cx + dpF(3f),
                            cy - barH / 2f,
                            cx + dpF(3f) + barW,
                            cy + barH / 2f,
                            fillPaint
                        )
                    }
                    "play" -> {
                        val rad = dp(23).toFloat()
                        canvas.drawCircle(cx, cy, rad, strokePaint)
                        val path = Path().apply {
                            moveTo(cx - dpF(5f), cy - dpF(8f))
                            lineTo(cx + dpF(9f), cy)
                            lineTo(cx - dpF(5f), cy + dpF(8f))
                            close()
                        }
                        canvas.drawPath(path, fillPaint)
                    }
                    "ratio" -> {
                        val w = dp(24).toFloat()
                        val h = dp(17).toFloat()
                        val l = cx - w / 2f
                        val t = cy - h / 2f
                        val r = l + w
                        val b = t + h
                        val c = dpF(5f)
                        canvas.drawLine(l, t + c, l, t, strokePaint)
                        canvas.drawLine(l, t, l + c, t, strokePaint)
                        canvas.drawLine(r - c, t, r, t, strokePaint)
                        canvas.drawLine(r, t, r, t + c, strokePaint)
                        canvas.drawLine(l, b - c, l, b, strokePaint)
                        canvas.drawLine(l, b, l + c, b, strokePaint)
                        canvas.drawLine(r - c, b, r, b, strokePaint)
                        canvas.drawLine(r, b, r, b - c, strokePaint)
                    }
                    "more" -> {
                        val dotR = dpF(2.2f)
                        val gap = dpF(7f)
                        canvas.drawCircle(cx - gap, cy, dotR, fillPaint)
                        canvas.drawCircle(cx, cy, dotR, fillPaint)
                        canvas.drawCircle(cx + gap, cy, dotR, fillPaint)
                    }
                }
            }

            override fun setAlpha(alpha: Int) {
                strokePaint.alpha = alpha
                fillPaint.alpha = alpha
            }

            override fun setColorFilter(cf: ColorFilter?) {
                strokePaint.colorFilter = cf
                fillPaint.colorFilter = cf
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = size
            override fun getIntrinsicHeight(): Int = size
        }
    }

    private fun buildPlayerOverlay(): FrameLayout {
        val box = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }

        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x >= box.width / 2f) {
                    seekBy(10_000)
                } else {
                    seekBy(-10_000)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showPlayerMoreDialog()
            }
        })

        box.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        playerTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { closePlayerOverlay() }
        }

        playerTitleText = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

        rotateScreenBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_always_landscape_portrait)
            setColorFilter(Color.parseColor("#FF8800"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener { toggleLandscapeFullScreen() }
        }

        playerTopBar.addView(backBtn)
        playerTopBar.addView(playerTitleText)
        playerTopBar.addView(rotateScreenBtn)

        centerHudBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC141416"))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        centerHudText = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        centerHudSubText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D4D4D8"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        centerHudBox.addView(centerHudText)
        centerHudBox.addView(centerHudSubText)

        playerBottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B8000000"))
            setPadding(dp(16), dp(8), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }

        currentTimeText = TextView(this).apply {
            text = "0:00"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        val timeSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        totalTimeText = TextView(this).apply {
            text = "0:00"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        timeRow.addView(currentTimeText)
        timeRow.addView(timeSpacer)
        timeRow.addView(totalTimeText)

        playerSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            styleSeekBarVlc(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentTimeText.text = formatDuration(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.let { videoView.seekTo(it.progress) }
                }
            })
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        trackInfoBtn = ImageButton(this).apply {
            setImageDrawable(makeVlcIconDrawable("track", Color.WHITE))
            contentDescription = "Audio Track"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { cycleAudioTrack() }
        }

        repeatToggleBtn = ImageButton(this).apply {
            setImageDrawable(makeVlcIconDrawable("repeat", Color.WHITE))
            contentDescription = "Repeat Mode"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                repeatEnabled = !repeatEnabled
                repeatToggleBtn.setImageDrawable(
                    makeVlcIconDrawable("repeat", if (repeatEnabled) Color.parseColor("#FF8800") else Color.WHITE)
                )
                showHud(if (repeatEnabled) "Repeat ON" else "Repeat OFF", "")
            }
        }

        val spacerLeft = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        playPauseBtn = ImageButton(this).apply {
            setImageDrawable(makeVlcIconDrawable("pause", Color.WHITE))
            contentDescription = "Play or Pause"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    setImageDrawable(makeVlcIconDrawable("play", Color.WHITE))
                } else {
                    videoView.start()
                    setImageDrawable(makeVlcIconDrawable("pause", Color.WHITE))
                }
            }
        }

        val spacerRight = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        aspectRatioBtn = ImageButton(this).apply {
            setImageDrawable(makeVlcIconDrawable("ratio", Color.WHITE))
            contentDescription = "Aspect Ratio"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { cycleAspectRatio() }
        }

        moreOptionsBtn = ImageButton(this).apply {
            setImageDrawable(makeVlcIconDrawable("more", Color.WHITE))
            contentDescription = "More Options"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showPlayerMoreDialog() }
        }

        actionsRow.addView(trackInfoBtn)
        actionsRow.addView(repeatToggleBtn)
        actionsRow.addView(spacerLeft)
        actionsRow.addView(playPauseBtn)
        actionsRow.addView(spacerRight)
        actionsRow.addView(aspectRatioBtn)
        actionsRow.addView(moreOptionsBtn)

        playerBottomControls.addView(timeRow)
        playerBottomControls.addView(playerSeekBar)
        playerBottomControls.addView(actionsRow)

        videoView.setOnCompletionListener {
            if (repeatEnabled && currentPlayingIndex in currentQueue.indices) {
                playFromList(currentQueue, currentPlayingIndex)
            } else if (currentPlayingIndex + 1 < currentQueue.size) {
                playFromList(currentQueue, currentPlayingIndex + 1)
            } else {
                closePlayerOverlay()
            }
        }

        box.addView(videoView)
        box.addView(playerTopBar)
        box.addView(centerHudBox)
        box.addView(playerBottomControls)
        return box
    }

    private fun applyAspectRatio() {
        val mode = aspectModes[aspectModeIndex]
        val dm = resources.displayMetrics
        val parentW = playerOverlay.width.takeIf { it > 0 } ?: dm.widthPixels
        val parentH = playerOverlay.height.takeIf { it > 0 } ?: dm.heightPixels

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )

        when (mode) {
            "16:9" -> {
                params.width = parentW
                params.height = (parentW * 9f / 16f).toInt().coerceAtMost(parentH)
            }
            "4:3" -> {
                params.width = (parentH * 4f / 3f).toInt().coerceAtMost(parentW)
                params.height = (params.width * 3f / 4f).toInt()
            }
            "Fill" -> {
                params.width = parentW
                params.height = parentH
            }
            else -> {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }

        videoView.layoutParams = params
        aspectRatioBtn.setImageDrawable(
            makeVlcIconDrawable("ratio", if (mode == "Fit") Color.WHITE else Color.parseColor("#FF8800"))
        )
    }

    private fun cycleAspectRatio() {
        aspectModeIndex = (aspectModeIndex + 1) % aspectModes.size
        applyAspectRatio()
        showHud(aspectModes[aspectModeIndex], "Long tap for more options")
    }

    private fun showPlayerMoreDialog() {
        val options = arrayOf(
            if (isLandscapeLocked) "Switch to Portrait" else "Switch to Landscape Full Screen",
            "Playback Speed (${playbackSpeed}x)",
            "Audio Track Switch",
            "Previous Episode",
            "Next Episode",
            "Close Player"
        )
        AlertDialog.Builder(this)
            .setTitle("Player Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleLandscapeFullScreen()
                    1 -> cyclePlaybackSpeed()
                    2 -> cycleAudioTrack()
                    3 -> if (currentPlayingIndex > 0) playFromList(currentQueue, currentPlayingIndex - 1)
                    4 -> if (currentPlayingIndex + 1 < currentQueue.size) playFromList(currentQueue, currentPlayingIndex + 1)
                    5 -> closePlayerOverlay()
                }
            }
            .show()
    }

    private fun cyclePlaybackSpeed() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val nextIdx = (speeds.indexOfFirst { it == playbackSpeed } + 1) % speeds.size
        playbackSpeed = speeds[nextIdx]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayerRef?.playbackParams = mediaPlayerRef?.playbackParams?.setSpeed(playbackSpeed)
                    ?: return
            } catch (_: Exception) {
            }
        }
        showHud("${playbackSpeed}x", "Playback speed")
    }

    private fun cycleAudioTrack() {
        val mp = mediaPlayerRef ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        val tracks = mp.trackInfo ?: return
        val audioIndices = tracks.mapIndexedNotNull { idx, info ->
            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) idx else null
        }
        if (audioIndices.size <= 1) {
            showHud("Audio 1/1", "Single audio track in file")
            return
        }
        currentAudioTrackIdx = (currentAudioTrackIdx + 1) % audioIndices.size
        val selected = audioIndices[currentAudioTrackIdx]
        mp.selectTrack(selected)
        val lang = tracks[selected].language?.uppercase().orEmpty()
        val label = if (lang.isNotBlank()) "Audio: $lang" else "Audio ${currentAudioTrackIdx + 1}/${audioIndices.size}"
        showHud(label, "Switched audio track")
    }

    private fun seekBy(deltaMs: Int) {
        val target = (videoView.currentPosition + deltaMs).coerceIn(0, videoView.duration.coerceAtLeast(0))
        videoView.seekTo(target)
        showHud(if (deltaMs > 0) "+10s" else "-10s", if (deltaMs > 0) "Forward" else "Backward")
    }

    private fun showHud(main: String, sub: String) {
        centerHudText.text = main
        if (sub.isBlank()) {
            centerHudSubText.visibility = View.GONE
        } else {
            centerHudSubText.text = sub
            centerHudSubText.visibility = View.VISIBLE
        }
        centerHudBox.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 1100)
    }

    private fun playFromList(list: List<MediaEntry>, index: Int) {
        if (index !in list.indices) return
        currentQueue = list
        currentPlayingIndex = index
        currentAudioTrackIdx = 0
        val item = list[index]

        if (saveHistoryEnabled) {
            historyList.removeAll { it.uri == item.uri }
            historyList.add(0, item)
        }

        isPlayerVisible = true
        updateSystemUiForPlayer()
        playerOverlay.visibility = View.VISIBLE
        playerTitleText.text = item.title
        playPauseBtn.setImageDrawable(makeVlcIconDrawable("pause", Color.WHITE))
        applyAspectRatio()

        videoView.setVideoURI(item.uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                try {
                    mp.playbackParams = mp.playbackParams.setSpeed(playbackSpeed)
                } catch (_: Exception) {
                }
            }
            mp.start()
        }
        videoView.start()
    }

    private fun closePlayerOverlay() {
        isPlayerVisible = false
        isLandscapeLocked = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updateSystemUiForPlayer()
        videoView.stopPlayback()
        playerOverlay.visibility = View.GONE
        renderCurrentTab()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPlayerVisible) {
            closePlayerOverlay()
        } else if (activeTab == "More" && settingsScroll.visibility == View.VISIBLE) {
            settingsScroll.visibility = View.GONE
            moreHubScroll.visibility = View.VISIBLE
            topHeaderTitle.text = "HKR Player"
        } else {
            super.onBackPressed()
        }
    }

    private fun primaryTextColor(): Int =
        if (isDarkMode) Color.WHITE else Color.parseColor("#111111")

    private fun secondaryTextColor(): Int =
        if (isDarkMode) Color.parseColor("#A1A1AA") else Color.parseColor("#52525B")

    private fun cardBgColor(): Int =
        if (isDarkMode) Color.parseColor("#1E1E22") else Color.parseColor("#F3F4F6")

    private fun buildOnboardingView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1722"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        val badge = TextView(this).apply {
            text = "HKR"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF8800"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }

        onboardTitle = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        onboardDesc = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }

        onboardAutoScanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val scanLabel = TextView(this).apply {
            text = "Let HKR Player scan my device for media"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        onboardAutoScanSwitch = Switch(this).apply {
            isChecked = autoScanEnabled
            setOnCheckedChangeListener { _, checked ->
                autoScanEnabled = checked
                prefs.edit().putBoolean("auto_scan", checked).apply()
            }
        }

        onboardAutoScanRow.addView(scanLabel)
        onboardAutoScanRow.addView(onboardAutoScanSwitch)

        onboardPermActionBtn = Button(this).apply {
            text = "Grant Media & Files Access"
            textSize = 13f
            setAllCaps(false)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF8800"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
            setOnClickListener {
                requestNeededPermissions()
                openAllFilesAccessSettings()
            }
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
            }
        }

        onboardSkipBtn = Button(this).apply {
            text = "SKIP"
            textSize = 13f
            setTextColor(Color.parseColor("#FF8800"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finishOnboarding() }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        onboardNextBtn = Button(this).apply {
            text = "NEXT"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF8800"))
            setOnClickListener {
                if (onboardStep < 2) {
                    onboardStep++
                    refreshOnboardingUi()
                } else {
                    finishOnboarding()
                }
            }
        }

        bottomRow.addView(onboardSkipBtn)
        bottomRow.addView(spacer)
        bottomRow.addView(onboardNextBtn)

        box.addView(badge)
        box.addView(onboardTitle)
        box.addView(onboardDesc)
        box.addView(onboardAutoScanRow)
        box.addView(onboardPermActionBtn)
        box.addView(bottomRow)
        return box
    }

    private fun refreshOnboardingUi() {
        when (onboardStep) {
            0 -> {
                onboardTitle.text = "Welcome to HKR Player!"
                onboardDesc.text = "Local videos, folders, landscape full screen, double-tap ±10s skip aur dark/light theme ke saath media player."
                onboardAutoScanRow.visibility = View.GONE
                onboardPermActionBtn.visibility = View.GONE
                onboardNextBtn.text = "NEXT"
            }
            1 -> {
                onboardTitle.text = "Auto-discover your media"
                onboardDesc.text = "HKR Player aapke device ke videos, folders aur audio files ko scan karke arrange kar sakta hai."
                onboardAutoScanRow.visibility = View.VISIBLE
                onboardPermActionBtn.visibility = View.GONE
                onboardNextBtn.text = "NEXT"
            }
            else -> {
                onboardTitle.text = "Permission"
                onboardDesc.text = "Device ke videos aur folders access karne ke liye media permission aur zaroorat pade to All files access enable karein."
                onboardAutoScanRow.visibility = View.GONE
                onboardPermActionBtn.visibility = View.VISIBLE
                onboardNextBtn.text = "DONE"
            }
        }
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        onboardingOverlay.visibility = View.GONE
        if (autoScanEnabled && hasMediaPermission()) {
            scanDeviceMedia()
        }
        selectTab("Video")
    }

    private fun buildBottomNavBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
            elevation = dp(6).toFloat()
        }

        val tabs = listOf("Video", "Audio", "Browse", "Playlists", "More")
        for (tab in tabs) {
            val btn = Button(this).apply {
                text = tab
                textSize = 11f
                setAllCaps(false)
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
                setOnClickListener { selectTab(tab) }
            }
            bar.addView(btn)
        }
        return bar
    }

    private fun buildMoreHubContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        val topButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }

        val settingsBtn = Button(this).apply {
            text = "SETTINGS"
            textSize = 13f
            setTextColor(Color.parseColor("#FF8800"))
            setBackgroundColor(cardBgColor())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                moreHubScroll.visibility = View.GONE
                settingsScroll.visibility = View.VISIBLE
                topHeaderTitle.text = "Settings"
                reloadSettingsContent()
            }
        }

        val aboutBtn = Button(this).apply {
            text = "ABOUT"
            textSize = 13f
            setTextColor(Color.parseColor("#FF8800"))
            setBackgroundColor(cardBgColor())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("HKR Player")
                    .setMessage("Native Android Media Player\nVideos: ${allVideos.size} • Audio: ${allAudios.size}\nFolders: ${folderMap.size}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        topButtonsRow.addView(settingsBtn)
        topButtonsRow.addView(aboutBtn)
        container.addView(topButtonsRow)

        val streamTitle = TextView(this).apply {
            text = "Streams"
            textSize = 16f
            setTextColor(Color.parseColor("#FF8800"))
            setPadding(0, dp(4), 0, dp(10))
        }
        container.addView(streamTitle)

        val newStreamCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cardBgColor())
            setPadding(dp(16), dp(24), dp(16), dp(24))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(145), dp(130)).apply {
                bottomMargin = dp(20)
            }
            setOnClickListener { showStreamUrlDialog() }
        }

        val plusText = TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(Color.parseColor("#FF8800"))
        }
        val streamLabel = TextView(this).apply {
            text = "New stream"
            textSize = 13f
            setTextColor(primaryTextColor())
        }
        newStreamCard.addView(plusText)
        newStreamCard.addView(streamLabel)
        container.addView(newStreamCard)

        val historyTitle = TextView(this).apply {
            text = "History (${historyList.size})"
            textSize = 16f
            setTextColor(Color.parseColor("#FF8800"))
            setPadding(0, dp(4), 0, dp(10))
        }
        container.addView(historyTitle)

        if (historyList.isEmpty()) {
            val emptyHistory = TextView(this).apply {
                text = "Abhi tak koi media play nahi hua."
                textSize = 13f
                setTextColor(secondaryTextColor())
            }
            container.addView(emptyHistory)
        } else {
            for ((idx, item) in historyList.take(8).withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(cardBgColor())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }
                    setOnClickListener { playFromList(historyList, idx) }
                }
                val t = TextView(this).apply {
                    text = item.title
                    textSize = 14f
                    maxLines = 1
                    setTextColor(primaryTextColor())
                }
                val s = TextView(this).apply {
                    text = "${item.folderName} • ${formatDuration(item.durationMs)}"
                    textSize = 11f
                    setTextColor(secondaryTextColor())
                }
                row.addView(t)
                row.addView(s)
                container.addView(row)
            }
        }

        return container
    }

    private fun showStreamUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setTextColor(primaryTextColor())
            setHintTextColor(secondaryTextColor())
        }
        AlertDialog.Builder(this)
            .setTitle("New Stream URL")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    val entry = MediaEntry(
                        title = url.substringAfterLast('/').ifBlank { "Network Stream" },
                        uri = Uri.parse(url),
                        folderName = "Stream",
                        filePath = null,
                        durationMs = 0L,
                        qualityTag = "URL",
                        isAudio = false
                    )
                    playFromList(listOf(entry), 0)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildSettingsView(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        fun sectionTitle(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#FF8800"))
            setPadding(0, dp(14), 0, dp(6))
        }

        fun settingRow(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { onClick() }

                val t = TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(primaryTextColor())
                }
                val s = TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(secondaryTextColor())
                }
                addView(t)
                addView(s)
            }

        container.addView(sectionTitle("Media library"))
        container.addView(settingRow(
            "Media library folders",
            "${folderMap.size} folders detected • Tap to rescan"
        ) {
            if (hasMediaPermission()) {
                scanDeviceMedia()
                Toast.makeText(this, "Media rescanned (${allVideos.size} videos)", Toast.LENGTH_SHORT).show()
            } else {
                requestNeededPermissions()
            }
        })

        val autoScanCheck = CheckBox(this).apply {
            text = "Auto rescan on startup"
            isChecked = autoScanEnabled
            setTextColor(primaryTextColor())
            setOnCheckedChangeListener { _, checked ->
                autoScanEnabled = checked
                prefs.edit().putBoolean("auto_scan", checked).apply()
            }
        }
        container.addView(autoScanCheck)

        container.addView(sectionTitle("Interface & Video"))
        val themeLabel = if (isDarkMode) "Theme: Dark Mode" else "Theme: Light Mode"
        container.addView(settingRow(
            themeLabel,
            "Tap karke Dark aur Light mode badlein"
        ) {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("dark_mode", isDarkMode).apply()
            applyTheme()
            reloadSettingsContent()
        })

        container.addView(settingRow(
            "Video screen orientation",
            if (isLandscapeLocked) "Landscape full screen" else "Automatic / Portrait"
        ) {
            toggleLandscapeFullScreen()
        })

        container.addView(settingRow(
            "All files access",
            "Device ke sabhi folders/files access karne ki setting kholein"
        ) {
            openAllFilesAccessSettings()
        })

        container.addView(sectionTitle("History"))
        val historyCheck = CheckBox(this).apply {
            text = "Playback history"
            isChecked = saveHistoryEnabled
            setTextColor(primaryTextColor())
            setOnCheckedChangeListener { _, checked ->
                saveHistoryEnabled = checked
                prefs.edit().putBoolean("save_history", checked).apply()
            }
        }
        container.addView(historyCheck)

        container.addView(settingRow(
            "Clear playback history",
            "Saved history list saaf karein"
        ) {
            historyList.clear()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        })

        scroll.addView(container)
        return scroll
    }

    private fun reloadSettingsContent() {
        val fresh = buildSettingsView()
        val inner = fresh.getChildAt(0)
        fresh.removeAllViews()
        settingsScroll.removeAllViews()
        if (inner != null) {
            settingsScroll.addView(inner)
        }
    }

    private fun reloadMoreHubContent() {
        val inner = buildMoreHubContent()
        moreHubScroll.removeAllViews()
        moreHubScroll.addView(inner)
    }

    private fun applyTheme() {
        val bg = if (isDarkMode) Color.parseColor("#121212") else Color.parseColor("#FFFFFF")
        val barBg = if (isDarkMode) Color.parseColor("#1E1E20") else Color.parseColor("#F5F5F7")
        val fg = primaryTextColor()
        val btnBg = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E4E4E7")

        rootLayout.setBackgroundColor(bg)
        topHeader.setBackgroundColor(barBg)
        topHeaderTitle.setTextColor(fg)
        bottomNavBar.setBackgroundColor(barBg)

        topThemeBtn.setBackgroundColor(btnBg)
        topThemeBtn.setTextColor(fg)
        topThemeBtn.text = if (isDarkMode) "Light" else "Dark"

        for (i in 0 until bottomNavBar.childCount) {
            val child = bottomNavBar.getChildAt(i) as? Button ?: continue
            val isSelected = child.text.toString() == activeTab
            child.setTextColor(if (isSelected) Color.parseColor("#FF8800") else fg)
        }
    }

    private fun selectTab(tab: String) {
        activeTab = tab
        applyTheme()
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        if (activeTab == "More") {
            mainRecycler.visibility = View.GONE
            fabPlayAll.visibility = View.GONE
            settingsScroll.visibility = View.GONE
            moreHubScroll.visibility = View.VISIBLE
            topHeaderTitle.text = "HKR Player"
            reloadMoreHubContent()
            return
        }

        moreHubScroll.visibility = View.GONE
        settingsScroll.visibility = View.GONE
        mainRecycler.visibility = View.VISIBLE
        fabPlayAll.visibility = if (activeTab == "Video") View.VISIBLE else View.GONE

        when (activeTab) {
            "Video" -> {
                topHeaderTitle.text = "HKR Player"
                mainRecycler.layoutManager = GridLayoutManager(this, 2)
                mainRecycler.adapter = MediaGridAdapter(allVideos) { idx ->
                    playFromList(allVideos, idx)
                }
            }
            "Audio" -> {
                topHeaderTitle.text = "Audio (${allAudios.size})"
                mainRecycler.layoutManager = LinearLayoutManager(this)
                mainRecycler.adapter = MediaListAdapter(allAudios) { idx ->
                    playFromList(allAudios, idx)
                }
            }
            "Browse" -> {
                topHeaderTitle.text = "Browse (${folderMap.size})"
                val folders = folderMap.keys.toList()
                mainRecycler.layoutManager = GridLayoutManager(this, 2)
                mainRecycler.adapter = FoldersAdapter(folders) { folderName ->
                    val list = folderMap[folderName].orEmpty()
                    topHeaderTitle.text = "$folderName (${list.size})"
                    mainRecycler.layoutManager = GridLayoutManager(this, 2)
                    mainRecycler.adapter = MediaGridAdapter(list) { idx ->
                        playFromList(list, idx)
                    }
                }
            }
            "Playlists" -> {
                topHeaderTitle.text = "Playlists & History (${historyList.size})"
                mainRecycler.layoutManager = LinearLayoutManager(this)
                mainRecycler.adapter = MediaListAdapter(historyList) { idx ->
                    playFromList(historyList, idx)
                }
            }
        }
    }

    private fun hasMediaPermission(): Boolean {
        val videoPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, videoPerm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun scanDeviceMedia() {
        allVideos.clear()
        allAudios.clear()
        folderMap.clear()

        val videoCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoProj = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        @Suppress("DEPRECATION")
        videoProj.add(MediaStore.Video.Media.DATA)

        contentResolver.query(
            videoCol,
            videoProj.toTypedArray(),
            null,
            null,
            "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "Video"
                val path = if (dataCol >= 0) c.getString(dataCol) else null
                val folder = path?.let { File(it).parentFile?.name }?.takeIf { it.isNotBlank() }
                    ?: c.getString(bucketCol)?.takeIf { it.isNotBlank() }
                    ?: "Videos"
                val dur = c.getLong(durCol)
                val h = c.getInt(hCol)
                val quality = when {
                    h >= 4320 -> "8K"
                    h >= 2160 -> "4K"
                    h >= 1080 -> "1080p"
                    h >= 720 -> "720p"
                    h > 0 -> "${h}p"
                    else -> ""
                }
                val uri = ContentUris.withAppendedId(videoCol, id)
                val entry = MediaEntry(name, uri, folder, path, dur, quality, false)
                allVideos.add(entry)
                folderMap.getOrPut(folder) { mutableListOf() }.add(entry)
            }
        }

        val audioCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val audioProj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION
        )

        contentResolver.query(
            audioCol,
            audioProj,
            null,
            null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "Audio"
                val dur = c.getLong(durCol)
                val uri = ContentUris.withAppendedId(audioCol, id)
                allAudios.add(MediaEntry(name, uri, "Audio", null, dur, "", true))
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    private fun loadThumbnail(item: MediaEntry, imageView: ImageView) {
        if (item.isAudio) {
            imageView.setImageBitmap(null)
            imageView.setBackgroundColor(Color.parseColor("#27272A"))
            return
        }
        val key = item.uri.toString()
        thumbCache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageBitmap(null)
        imageView.tag = key
        thumbExecutor.execute {
            var bmp: Bitmap? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bmp = contentResolver.loadThumbnail(item.uri, android.util.Size(dp(260), dp(160)), null)
                }
            } catch (_: Exception) {
            }
            if (bmp == null) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this@MainActivity, item.uri)
                    bmp = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
                } catch (_: Exception) {
                } finally {
                    try {
                        r.release()
                    } catch (_: Exception) {
                    }
                }
            }
            if (bmp != null) {
                thumbCache.put(key, bmp)
                mainHandler.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    inner class MediaGridAdapter(
        private val items: List<MediaEntry>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MediaGridAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(11)
            val quality: TextView = v.findViewById(12)
            val menuBtn: ImageView = v.findViewById(13)
            val title: TextView = v.findViewById(14)
            val dur: TextView = v.findViewById(15)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(12))
            }

            val frame = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(106)
                )
            }

            val img = ImageView(parent.context).apply {
                id = 11
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#242426"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val qTag = TextView(parent.context).apply {
                id = 12
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#B3000000"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            }

            val dots = ImageView(parent.context).apply {
                id = 13
                setImageResource(android.R.drawable.ic_menu_more)
                setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(4), dp(6), dp(4), dp(4))
                }
            }

            frame.addView(img)
            frame.addView(qTag)
            frame.addView(dots)

            val tView = TextView(parent.context).apply {
                id = 14
                textSize = 13f
                maxLines = 1
                setTextColor(primaryTextColor())
                setPadding(0, dp(6), 0, dp(2))
            }

            val dView = TextView(parent.context).apply {
                id = 15
                textSize = 11f
                setTextColor(secondaryTextColor())
            }

            card.addView(frame)
            card.addView(tView)
            card.addView(dView)
            return VH(card)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title.substringBeforeLast('.')
            holder.title.setTextColor(primaryTextColor())
            holder.dur.text = formatDuration(item.durationMs)
            holder.dur.setTextColor(secondaryTextColor())
            holder.quality.text = item.qualityTag
            holder.quality.visibility = if (item.qualityTag.isNotBlank()) View.VISIBLE else View.GONE
            loadThumbnail(item, holder.thumb)

            holder.menuBtn.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(item.title)
                    .setItems(arrayOf("Play Video", "Folder: ${item.folderName}")) { _, which ->
                        if (which == 0) onClick(position)
                    }
                    .show()
            }
            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class FoldersAdapter(
        private val folders: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<FoldersAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(21)
            val name: TextView = v.findViewById(22)
            val count: TextView = v.findViewById(23)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(12))
            }
            val img = ImageView(parent.context).apply {
                id = 21
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#242426"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(106))
            }
            val nView = TextView(parent.context).apply {
                id = 22
                textSize = 14f
                maxLines = 1
                setTextColor(primaryTextColor())
                setPadding(0, dp(6), 0, 0)
            }
            val cView = TextView(parent.context).apply {
                id = 23
                textSize = 12f
                setTextColor(secondaryTextColor())
            }
            box.addView(img)
            box.addView(nView)
            box.addView(cView)
            return VH(box)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val fName = folders[position]
            val list = folderMap[fName].orEmpty()
            holder.name.text = fName
            holder.name.setTextColor(primaryTextColor())
            holder.count.text = "${list.size} videos"
            holder.count.setTextColor(secondaryTextColor())
            list.firstOrNull()?.let { loadThumbnail(it, holder.thumb) }
            holder.itemView.setOnClickListener { onClick(fName) }
        }

        override fun getItemCount(): Int = folders.size
    }

    inner class MediaListAdapter(
        private val items: List<MediaEntry>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MediaListAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(31)
            val sub: TextView = v.findViewById(32)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
            }
            val t = TextView(parent.context).apply {
                id = 31
                textSize = 14f
                maxLines = 1
                setTextColor(primaryTextColor())
            }
            val s = TextView(parent.context).apply {
                id = 32
                textSize = 11f
                setTextColor(secondaryTextColor())
            }
            row.addView(t)
            row.addView(s)
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.title.setTextColor(primaryTextColor())
            holder.sub.text = "${item.folderName} • ${formatDuration(item.durationMs)}"
            holder.sub.setTextColor(secondaryTextColor())
            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size
    }
}
