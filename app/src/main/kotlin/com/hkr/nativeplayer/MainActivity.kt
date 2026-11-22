package com.hkr.nativeplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class VideoItem(
    val title: String,
    val uri: Uri,
    val folderName: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var nowPlayingText: TextView
    private lateinit var albumsContainer: LinearLayout
    private lateinit var episodesRecyclerView: RecyclerView

    private val albumsMap = linkedMapOf<String, MutableList<VideoItem>>()
    private var currentAlbum: String = "All Videos"
    private var currentIndex: Int = -1
    private var adapter: EpisodeAdapter? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadLocalVideos()
        } else {
            nowPlayingText.text = "Storage/Video permission ज़रूरी है"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        albumsContainer = findViewById(R.id.albumsContainer)
        episodesRecyclerView = findViewById(R.id.episodesRecyclerView)

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        videoView.setOnCompletionListener {
            val list = albumsMap[currentAlbum] ?: return@setOnCompletionListener
            if (currentIndex + 1 < list.size) {
                playEpisode(currentIndex + 1)
            }
        }

        episodesRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = EpisodeAdapter(emptyList(), -1) { idx ->
            playEpisode(idx)
        }
        episodesRecyclerView.adapter = adapter

        checkPermissionAndLoad()
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
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Video"
                val bucket = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() } ?: "Other"
                val contentUri = ContentUris.withAppendedId(collection, id)

                val item = VideoItem(name, contentUri, bucket)
                allList.add(item)
                albumsMap.getOrPut(bucket) { mutableListOf() }.add(item)
            }
        }

        if (allList.isEmpty()) {
            nowPlayingText.text = "फ़ोन में कोई वीडियो नहीं मिला"
            return
        }

        val finalMap = linkedMapOf<String, MutableList<VideoItem>>()
        finalMap["All Videos"] = allList
        for ((k, v) in albumsMap) {
            finalMap[k] = v
        }
        albumsMap.clear()
        albumsMap.putAll(finalMap)

        currentAlbum = albumsMap.keys.firstOrNull { it != "All Videos" } ?: "All Videos"
        currentIndex = -1
        renderAlbumButtons()
        renderEpisodesList()
        nowPlayingText.text = "Album चुनें या Episode पर tap करें"
    }

    private fun renderAlbumButtons() {
        albumsContainer.removeAllViews()
        for (albumName in albumsMap.keys) {
            val count = albumsMap[albumName]?.size ?: 0
            val btn = Button(this).apply {
                text = "$albumName ($count)"
                textSize = 12f
                setAllCaps(false)
                val isSelected = albumName == currentAlbum
                setBackgroundColor(if (isSelected) Color.parseColor("#FF8800") else Color.parseColor("#2A2A2A"))
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 12
                }
                layoutParams = lp
                setOnClickListener {
                    currentAlbum = albumName
                    currentIndex = -1
                    renderAlbumButtons()
                    renderEpisodesList()
                }
            }
            albumsContainer.addView(btn)
        }
    }

    private fun renderEpisodesList() {
        val list = albumsMap[currentAlbum] ?: emptyList()
        adapter?.updateData(list, currentIndex)
    }

    private fun playEpisode(index: Int) {
        val list = albumsMap[currentAlbum] ?: return
        if (index !in list.indices) return
        currentIndex = index
        val item = list[index]
        nowPlayingText.text = "[${item.folderName}] ${item.title}"
        videoView.setVideoURI(item.uri)
        videoView.start()
        renderEpisodesList()
    }

    class EpisodeAdapter(
        private var items: List<VideoItem>,
        private var playingIndex: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        fun updateData(newItems: List<VideoItem>, newPlayingIndex: Int) {
            items = newItems
            playingIndex = newPlayingIndex
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isPlaying = position == playingIndex
            holder.titleText.text = "${position + 1}. ${item.title}"
            holder.titleText.setTextColor(if (isPlaying) Color.parseColor("#FF8800") else Color.WHITE)
            holder.titleText.setBackgroundColor(
                if (isPlaying) Color.parseColor("#2E2216") else Color.parseColor("#1B1B1B")
            )
            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size
    }
}
