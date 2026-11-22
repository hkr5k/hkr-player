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
            text = "Auto rescan media on startup"
            isChecked = autoScanEnabled
            setTextColor(primaryTextColor())
            setOnCheckedChangeListener { _, checked ->
                autoScanEnabled = checked
                prefs.edit().putBoolean("auto_scan", checked).apply()
            }
        }
        container.addView(autoScanCheck)

        val historyCheck = CheckBox(this).apply {
            text = "Playback history (Playlists tab mein save karein)"
            isChecked = saveHistoryEnabled
            setTextColor(primaryTextColor())
            setOnCheckedChangeListener { _, checked ->
                saveHistoryEnabled = checked
                prefs.edit().putBoolean("save_history", checked).apply()
            }
        }
        container.addView(historyCheck)

        container.addView(sectionTitle("Video, Audio & Subtitles"))
        container.addView(settingRow(
            "All files access",
            "Device ke sabhi folders/files scan karne ki setting kholein"
        ) {
            openAllFilesAccessSettings()
        })

        container.addView(settingRow(
            "Audio Track Switcher",
            "Video chalne par top bar me 'Audio' button se tracks badlein"
        ) {
            cycleAudioTrack()
        })

        container.addView(settingRow(
            "Double-Tap Seek (±10s)",
            "Player ke right side double-tap = +10s, left side = -10s"
        ) {
            Toast.makeText(this, "Player ke left/right side double-tap karein", Toast.LENGTH_SHORT).show()
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

    private fun applyTheme() {
        val bg = if (isDarkMode) Color.parseColor("#111111") else Color.parseColor("#FFFFFF")
        val barBg = if (isDarkMode) Color.parseColor("#1C1C1E") else Color.parseColor("#F4F4F5")
        val fg = primaryTextColor()
        val btnBg = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E4E4E7")

        rootLayout.setBackgroundColor(bg)
        topHeader.setBackgroundColor(barBg)
        topHeaderTitle.setTextColor(fg)
        bottomNavBar.setBackgroundColor(barBg)

        for (btn in listOf(topThemeBtn, audioSwitchBtn)) {
            btn.setBackgroundColor(btnBg)
            btn.setTextColor(fg)
        }
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
            settingsScroll.visibility = View.VISIBLE
            reloadSettingsContent()
            return
        }

        settingsScroll.visibility = View.GONE
        mainRecycler.visibility = View.VISIBLE

        when (activeTab) {
            "Video" -> {
                topHeaderTitle.text = "Videos (${allVideos.size})"
                mainRecycler.layoutManager = GridLayoutManager(this, 2)
                mainRecycler.adapter = MediaGridAdapter(allVideos, currentPlayingIndex) { idx ->
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
                topHeaderTitle.text = "Browse Folders (${folderMap.size})"
                val folders = folderMap.keys.toList()
                mainRecycler.layoutManager = GridLayoutManager(this, 2)
                mainRecycler.adapter = FoldersAdapter(folders) { folderName ->
                    val list = folderMap[folderName].orEmpty()
                    topHeaderTitle.text = "$folderName (${list.size})"
                    mainRecycler.layoutManager = GridLayoutManager(this, 2)
                    mainRecycler.adapter = MediaGridAdapter(list, -1) { idx ->
                        playFromList(list, idx)
                    }
                }
            }
            "Playlists" -> {
                topHeaderTitle.text = "History & Queue (${historyList.size})"
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

        playerBox.visibility = View.VISIBLE
        audioSwitchBtn.visibility = if (item.isAudio) View.GONE else View.VISIBLE
        audioSwitchBtn.text = "Audio"
        topHeaderTitle.text = item.title

        videoView.setVideoURI(item.uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            mp.start()
            mediaController?.show(4000)
        }
        videoView.start()
    }

    private fun playPrevious() {
        if (currentPlayingIndex > 0) {
            playFromList(currentQueue, currentPlayingIndex - 1)
        }
    }

    private fun playNext() {
        if (currentPlayingIndex + 1 < currentQueue.size) {
            playFromList(currentQueue, currentPlayingIndex + 1)
        }
    }

    private fun seekBy(deltaMs: Int) {
        val target = (videoView.currentPosition + deltaMs).coerceIn(0, videoView.duration.coerceAtLeast(0))
        videoView.seekTo(target)
        skipHudText.text = if (deltaMs > 0) "+10s ⏭" else "⏮ -10s"
        skipHudText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideSkipRunnable)
        mainHandler.postDelayed(hideSkipRunnable, 700)
    }

    private fun cycleAudioTrack() {
        val mp = mediaPlayerRef ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        val tracks = mp.trackInfo ?: return
        val audioIndices = tracks.mapIndexedNotNull { idx, info ->
            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) idx else null
        }
        if (audioIndices.size <= 1) {
            skipHudText.text = "Single Audio Track"
            skipHudText.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideSkipRunnable)
            mainHandler.postDelayed(hideSkipRunnable, 900)
            return
        }
        currentAudioTrackIdx = (currentAudioTrackIdx + 1) % audioIndices.size
        val selected = audioIndices[currentAudioTrackIdx]
        mp.selectTrack(selected)
        val lang = tracks[selected].language?.uppercase().orEmpty()
        val label = if (lang.isNotBlank()) "Audio: $lang" else "Audio ${currentAudioTrackIdx + 1}/${audioIndices.size}"
        audioSwitchBtn.text = label
        skipHudText.text = label
        skipHudText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideSkipRunnable)
        mainHandler.postDelayed(hideSkipRunnable, 1000)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
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
                    bmp = contentResolver.loadThumbnail(item.uri, android.util.Size(dp(240), dp(160)), null)
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

    override fun onPause() {
        super.onPause()
        mediaController?.hide()
    }

    inner class MediaGridAdapter(
        private val items: List<MediaEntry>,
        private val activeIdx: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MediaGridAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(11)
            val quality: TextView = v.findViewById(12)
            val title: TextView = v.findViewById(13)
            val dur: TextView = v.findViewById(14)
            val border: View = v.findViewById(15)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(10))
            }

            val frame = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(105)
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
                setBackgroundColor(Color.parseColor("#AA000000"))
                setPadding(dp(5), dp(2), dp(5), dp(2))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
            }

            val activeRing = View(parent.context).apply {
                id = 15
                background = GradientDrawable().apply {
                    setStroke(dp(3), Color.parseColor("#FF8800"))
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            frame.addView(img)
            frame.addView(activeRing)
            frame.addView(qTag)

            val tView = TextView(parent.context).apply {
                id = 13
                textSize = 13f
                maxLines = 1
                setTextColor(primaryTextColor())
                setPadding(0, dp(5), 0, dp(1))
            }

            val dView = TextView(parent.context).apply {
                id = 14
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
            holder.border.visibility = if (position == activeIdx) View.VISIBLE else View.GONE
            loadThumbnail(item, holder.thumb)
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(105))
            }
            val nView = TextView(parent.context).apply {
                id = 22
                textSize = 14f
                maxLines = 1
                setTextColor(primaryTextColor())
                setPadding(0, dp(5), 0, 0)
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
                setPadding(dp(12), dp(10), dp(12), dp(10))
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
