package com.hkr.nativeplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var playerBox: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var skipHudText: TextView
    private lateinit var audioSwitchBtn: Button
    private lateinit var contentContainer: FrameLayout
    private lateinit var mainRecycler: RecyclerView
    private lateinit var settingsScroll: ScrollView
    private lateinit var bottomNavBar: LinearLayout

    private var mediaController: MediaController? = null
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

    private val thumbCache = LruCache<String, Bitmap>(140)
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideSkipRunnable = Runnable {
        if (::skipHudText.isInitialized) {
            skipHudText.visibility = View.GONE
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (autoScanEnabled) {
            scanDeviceMedia()
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        topHeaderTitle = TextView(this).apply {
            text = "HKR Player"
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        audioSwitchBtn = Button(this).apply {
            text = "Audio"
            textSize = 11f
            setAllCaps(false)
            visibility = View.GONE
            setOnClickListener { cycleAudioTrack() }
        }

        topThemeBtn = Button(this).apply {
            text = if (isDarkMode) "Light" else "Dark"
            textSize = 11f
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                isDarkMode = !isDarkMode
                prefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                applyTheme()
                renderCurrentTab()
            }
        }

        topHeader.addView(topHeaderTitle)
        topHeader.addView(audioSwitchBtn)
        topHeader.addView(topThemeBtn)

        playerBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(215)
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        skipHudText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C2000000"))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x >= playerBox.width / 2f) {
                    seekBy(10_000)
                } else {
                    seekBy(-10_000)
                }
                return true
            }
        })
        playerBox.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }

        playerBox.addView(videoView)
        playerBox.addView(skipHudText)

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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
        }

        settingsScroll = buildSettingsView().apply {
            visibility = View.GONE
        }

        contentContainer.addView(mainRecycler)
        contentContainer.addView(settingsScroll)

        bottomNavBar = buildBottomNavBar()

        rootLayout.addView(topHeader)
        rootLayout.addView(playerBox)
        rootLayout.addView(contentContainer)
        rootLayout.addView(bottomNavBar)

        onboardingOverlay = buildOnboardingView().apply {
            visibility = if (onboardingDone) View.GONE else View.VISIBLE
        }

        outerContainer.addView(rootLayout)
        outerContainer.addView(onboardingOverlay)
        setContentView(outerContainer)

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        controller.setPrevNextListeners(
            { playPrevious() },
            { playNext() }
        )
        videoView.setMediaController(controller)
        mediaController = controller

        videoView.setOnCompletionListener {
            playNext()
        }

        applyTheme()

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
    }

    private fun primaryTextColor(): Int =
        if (isDarkMode) Color.WHITE else Color.parseColor("#111111")

    private fun secondaryTextColor(): Int =
        if (isDarkMode) Color.parseColor("#A1A1AA") else Color.parseColor("#52525B")

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
                onboardDesc.text = "Local videos, series folders, multi-audio tracks, double-tap ±10s skip aur dark/light theme ke saath fast media player."
                onboardAutoScanRow.visibility = View.GONE
                onboardPermActionBtn.visibility = View.GONE
                onboardNextBtn.text = "NEXT"
            }
            1 -> {
                onboardTitle.text = "Auto-discover your media"
                onboardDesc.text = "HKR Player aapke device ke videos, anime folders aur audio files ko scan karke arrange kar sakta hai."
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
                dp(56)
            )
            setBackgroundColor(Color.parseColor("#1C1C1E"))
        }

        val tabs = listOf("Video", "Audio", "Browse", "Playlists", "More")
        for (tab in tabs) {
            val btn = Button(this).apply {
                text = tab
                textSize = 11f
                setAllCaps(false)
                setTextColor(Color.WHITE)
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

    private fun buildSettingsView(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        fun sectionTitle(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#FF8800"))
            setPadding(0, dp(12), 0, dp(6))
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

        container.addView(sectionTitle("Interface & Media"))
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

        val autoScanCheck = CheckBox(this).apply {
            text = "Auto rescan media o
