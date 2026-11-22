package com.hkr.nativeplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
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

    private lateinit var playerBox: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var backToAlbumsBtn: Button
    private lateinit var headerTitleText: TextView
    private lateinit var gridRecyclerView: RecyclerView
    private var mediaController: MediaController? = null

    private val albumsMap = linkedMapOf<String, MutableList<VideoItem>>()
    private var inAlbumGrid: Boolean = false
    private var currentAlbum: String = "All Videos"
    private var currentIndex: Int = -1

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        playerBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
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
        playerBox.addView(videoView)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        backToAlbumsBtn = Button(this).apply {
            text = "‹ Albums"
            textSize = 13f
            setAllCaps(false)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2E"))
            visibility = View.GONE
            setOnClickListener {
                showAlbumsGrid()
            }
        }

        headerTitleText = TextView(this).apply {
            text = "Albums"
            textSize = 18f
            setTextColor(Color.WHITE)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(10)
            }
        }

        topBar.addView(backToAlbumsBtn)
        topBar.addView(headerTitleText)

        gridRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToPadding = false
        }

        root.addView(playerBox)
        root.addView(topBar)
        root.addView(gridRecyclerView)
        setContentView(root)

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

        checkPermissionAndLoad()
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
        val item = list[index]

        playerBox.visibility = View.VISIBLE
        headerTitleText.text = item.title
        videoView.setVideoURI(item.uri)
        videoView.setOnPreparedListener { mp ->
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
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            }

            val cView = TextView(parent.context).apply {
                id = 103
                textSize = 12f
                setTextColor(Color.parseColor("#9E9EA4"))
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
                setBackgroundColor(Color.parseColor("#242426"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val activeBorder = View(parent.context).apply {
                id = 203
                background = GradientDrawable().apply {
                    setStroke(dp(3), Color.parseColor("#FF8800"))
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val dur = TextView(parent.context).apply {
                id = 202
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#88000000"))
                setPadding(dp(4), dp(1), dp(4), dp(1))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }

            frame.addView(img)
            frame.addView(activeBorder)
            frame.addView(dur)
            return VH(frame)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.durationText.text = formatDuration(item.durationMs)
            holder.overlay.visibility = if (position == playingIndex) View.VISIBLE else View.GONE
            loadThumbnail(item, holder.thumb)
            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size
    }
}
