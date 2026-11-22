package com.hkr.nativeplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

data class VideoItem(
    val title: String,
    val uri: Uri,
    val albumName: String,
    val durationMs: Long
)

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var skipIndicatorText: TextView
    private lateinit var backToAlbumsBtn: Button
    private lateinit var audioTrackBtn: Button
    private lateinit var themeToggleBtn: Button
    private lateinit var headerTitleText: TextView
    private lateinit var gridRecyclerView: RecyclerView

    private var mediaController: MediaController? = null
    private var mediaPlayerRef: MediaPlayer? = null

    private val albumsMap = linkedMapOf<String, MutableList<VideoItem>>()
    private var inAlbumGrid: Boolean = false
    private var currentAlbum: String = "All Videos"
    private var currentIndex: Int = -1
    private var isDarkMode: Boolean = true
    private var currentAudioTrackIndex: Int = 0

    private val thumbCache = LruCache<String, Bitmap>(120)
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadLocalVideos()
        } else {
            headerTitleText.text = "Permission दें"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

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

        skipIndicatorText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val halfWidth = playerBox.width / 2f
                if (e.x >= halfWidth) {
                    seekBy(10_000)
                } else {
                    seekBy(-10_000)
                }
                return true
            }
        })

        playerBox.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        playerBox.addView(videoView)
        playerBox.addView(skipIndicatorText)

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        backToAlbumsBtn = Button(this).apply {
            text = "‹ Albums"
            textSize = 12f
            setAllCaps(false)
            visibility = View.GONE
            setOnClickListener { showAlbumsGrid() }
        }

        headerTitleText = TextView(this).apply {
            text = "Albums"
            textSize = 17f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(6)
            }
        }

        audioTrackBtn = Button(this).apply {
            text = "Audio 1"
            textSize = 11f
            setAllCaps(false)
            visibility = View.GONE
            setOnClickListener { cycleAudioTrack() }
        }

        themeToggleBtn = Button(this).apply {
            text = "Light"
            textSize = 11f
            setAllCaps(false)
            setOnClickListener {
                isDarkMode = !isDarkMode
                applyTheme()
            }
        }

        topBar.addView(backToAlbumsBtn)
        topBar.addView(headerTitleText)
        topBar.addView(audioTrackBtn)
        topBar.addView(themeToggleBtn)

        gridRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToPadding = false
        }

        rootLayout.addView(playerBox)
        rootLayout.addView(topBar)
        rootLayout.addView(gridRecyclerView)
        setContentView(rootLayout)

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        controller.setPrevNextListeners(
            { playPreviousEpisode() },
            { playNextEpisode() }
        )
        videoView.setMediaController(controller)
        mediaController = controller

        videoView.setOnCompletionListener {
            playNextEpisode()
        }

        applyTheme()
        checkPermissionAndLoad()
    }

    private fun applyTheme() {
        val bgMain = if (isDarkMode) Color.parseColor("#111111") else Color.parseColor("#FFFFFF")
        val bgBar = if (isDarkMode) Color.parseColor("#1C1C1E") else Color.parseColor("#F2F2F7")
        val textMain = if (isDarkMode) Color.WHITE else Color.parseColor("#111111")
        val btnBg = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")

        rootLayout.setBackgroundColor(bgMain)
        topBar.setBackgroundColor(bgBar)
        headerTitleText.setTextColor(textMain)

        for (btn in listOf(backToAlbumsBtn, audioTrackBtn, themeToggleBtn)) {
            btn.setBackgroundColor(btnBg)
            btn.setTextColor(textMain)
        }
        themeToggleBtn.text = if (isDarkMode) "Light" else "Dark"

        if (inAlbumGrid) {
            openAlbum(currentAlbum)
        } else if (albumsMap.isNotEmpty()) {
            showAlbumsGrid()
        }
    }

    private fun seekBy(deltaMs: Int) {
        if (!videoView.canSeekForward() && deltaMs > 0) return
        val target = (videoView.currentPosition + deltaMs).coerceIn(0, videoView.duration.coerceAtLeast(0))
        videoView.seekTo(target)
        skipIndicatorText.text = if (deltaMs > 0) "+10s ⏭" else "⏮ -10s"
        skipIndicatorText.visibility = View.VISIBLE
        mainHandler.removeCallbacksAndMessages("skip_hide")
        mainHandler.postAtTime({ skipIndicatorText.visibility = View.GONE }, "skip_hide", android.os.SystemClock.uptimeMillis() + 700)
    }

    private fun cycleAudioTrack() {
        val mp = mediaPlayerRef ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        val tracks = mp.trackInfo ?: return
        val audioIndices = tracks.mapIndexedNotNull { idx, info ->
            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) idx else null
        }
        if (audioIndices.size <= 1) {
            skipIndicatorText.text = "Single Audio Track"
            skipIndicatorText.visibility = View.VISIBLE
            mainHandler.postDelayed({ skipIndicatorText.visibility = View.GONE }, 900)
            return
        }
        currentAudioTrackIndex = (currentAudioTrackIndex + 1) % audioIndices.size
        val selectedTrackIdx = audioIndices[currentAudioTrackIndex]
        mp.selectTrack(selectedTrackIdx)
        val lang = tracks[selectedTrackIdx].language?.uppercase().orEmpty()
        val label = if (lang.isNotBlank()) "Audio: $lang" else "Audio ${currentAudioTrackIndex + 1}/${audioIndices.size}"
        audioTrackBtn.text = label
        skipIndicatorText.text = label
        skipIndicatorText.visibility = View.VISIBLE
        mainHandler.postDelayed({ skipIndicatorText.visibility = View.GONE }, 1000)
    }

    override fun onPause() {
        super.onPause()
        mediaController?.hide()
    }

    override fun onStop() {
        super.onStop()
        mediaController?.hide()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private fun checkPermissionAndLoad() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadLocalVideos()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun loadLocalVideos() {
        albumsMap.clear()
        val allList = mutableListOf<VideoItem>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )

        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Video"
                val bucket = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() } ?: "Videos"
                val duration = cursor.getLong(durCol)
                val contentUri = ContentUris.withAppendedId(collection, id)

                val item = VideoItem(name, contentUri, bucket, duration)
                allList.add(item)
                albumsMap.getOrPut(bucket) { mutableListOf() }.add(item)
            }
        }

        if (allList.isEmpty()) {
            headerTitleText.text = "कोई वीडियो नहीं मिला"
            return
        }

        val ordered = linkedMapOf<String, MutableList<VideoItem>>()
        ordered["All Videos"] = allList
        for ((k, v) in albumsMap) {
            ordered[k] = v
        }
        albumsMap.clear()
        albumsMap.putAll(ordered)

        showAlbumsGrid()
    }

    private fun showAlbumsGrid() {
        inAlbumGrid = false
        backToAlbumsBtn.visibility = View.GONE
        headerTitleText.text = "Albums"

        val albumNames = albumsMap.keys.toList()
        gridRecyclerView.layoutManager = GridLayoutManager(this, 3)
        gridRecyclerView.adapter = AlbumsAdapter(albumNames) { albumName ->
            openAlbum(albumName)
        }
    }

    private fun openAlbum(albumName: String) {
        inAlbumGrid = true
        currentAlbum = albumName
        backToAlbumsBtn.visibility = View.VISIBLE
        headerTitleText.text = albumName

        val list = albumsMap[albumName] ?: emptyList()
        gridRecyclerView.layoutManager = GridLayoutManager(this, 4)
        gridRecyclerView.adapter = VideosGridAdapter(list, currentIndex) { idx ->
            playEpisode(idx)
        }
    }

    private fun playEpisode(index: Int) {
        val list = albumsMap[currentAlbum] ?: return
        if (index !in list.indices) return
        currentIndex = index
        currentAudioTrackIndex = 0
        val item = list[index]

        playerBox.visibility = View.VISIBLE
        audioTrackBtn.visibility = View.VISIBLE
        audioTrackBtn.text = "Audio"
        headerTitleText.text = item.title

        videoView.setVideoURI(item.uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayerRef = mp
            mp.start()
            mediaController?.show(4000)
        }
        videoView.start()

        if (inAlbumGrid) {
            gridRecyclerView.adapter = VideosGridAdapter(list, currentIndex) { idx ->
                playEpisode(idx)
            }
        }
    }

    private fun playPreviousEpisode() {
        if (currentIndex > 0) {
            playEpisode(currentIndex - 1)
        }
    }

    private fun playNextEpisode() {
        val list = albumsMap[currentAlbum] ?: return
        if (currentIndex + 1 < list.size) {
            playEpisode(currentIndex + 1)
        }
    }

    private fun loadThumbnail(item: VideoItem, imageView: ImageView) {
        val key = item.uri.toString()
        val cached = thumbCache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageBitmap(null)
        imageView.tag = key

        thumbExecutor.execute {
            var bmp: Bitmap? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bmp = contentResolver.loadThumbnail(
                        item.uri,
                        android.util.Size(dp(180), dp(180)),
                        null
                    )
                }
            } catch (_: Exception) {
            }

            if (bmp == null) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, item.uri)
                    bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            if (bmp != null) {
                thumbCache.put(key, bmp)
                val finalBmp = bmp
                mainHandler.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(finalBmp)
                    }
                }
            }
        }
    }

    inner class AlbumsAdapter(
        private val names: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AlbumsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(101)
            val title: TextView = view.findViewById(102)
            val count: TextView = view.findViewById(103)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val img = ImageView(parent.context).apply {
                id = 101
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#242426"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(115)
                )
            }

            val tView = TextView(parent.context).apply {
                id = 102
                textSize = 14f
                setTextColor(if (isDarkMode) Color.WHITE else Color.parseColor("#111111"))
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            }

            val cView = TextView(parent.context).apply {
                id = 103
                textSize = 12f
                setTextColor(if (isDarkMode) Color.parseColor("#9E9EA4") else Color.parseColor("#66666E"))
            }

            box.addView(img)
            box.addView(tView)
            box.addView(cView)
            return VH(box)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val albumName = names[position]
            val items = albumsMap[albumName].orEmpty()
            holder.title.text = albumName
            holder.count.text = "${items.size}"
            val firstVideo = items.firstOrNull()
            if (firstVideo != null) {
                loadThumbnail(firstVideo, holder.thumb)
            }
            holder.itemView.setOnClickListener { onClick(albumName) }
        }

        override fun getItemCount(): Int = names.size
    }

    inner class VideosGridAdapter(
        private val items: List<VideoItem>,
        private val playingIndex: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<VideosGridAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(201)
            val durationText: TextView = view.findViewById(202)
            val overlay: View = view.findViewById(203)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val frame = FrameLayout(parent.context).apply {
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(92)
                )
            }

            val img = ImageView(parent.context).apply {
                id = 201
                scaleType = ImageView.ScaleType.CENTER_CROP
         
