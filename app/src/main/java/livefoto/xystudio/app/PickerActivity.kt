package livefoto.xystudio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import livefoto.xystudio.app.databinding.ActivityPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Pemilih video buatan sendiri + fallback Photo Picker sistem
 * (tidak butuh izin luas, jalan di Android 14+ saat akses sebagian).
 */
class PickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPickerBinding
    private var albums: List<ProjectStore.Album> = emptyList()
    private var currentBucket: String? = null
    private val adapter = VideoAdapter { uri -> pick(uri) }

    @SuppressLint("InlinedApi")
    private val askPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.READ_MEDIA_VIDEO] == true ||
            (Build.VERSION.SDK_INT >= 34 &&
                grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true) ||
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (granted) loadAlbums() else {
            showEmpty("Izin akses video ditolak")
            openSystemPicker()
        }
    }

    private val pickVisual = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pick(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)

        b.btnBack.setOnClickListener { finish() }
        b.albumBar.setOnClickListener { showAlbumMenu() }
        b.btnSystemPick.setOnClickListener { openSystemPicker() }
        b.btnEmptyPick.setOnClickListener { openSystemPicker() }

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter

        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val hasAccess = permissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (hasAccess) loadAlbums() else askPerm.launch(permissions)
    }

    private fun openSystemPicker() {
        pickVisual.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    private fun loadAlbums() {
        lifecycleScope.launch {
            albums = withContext(Dispatchers.IO) {
                ProjectStore.videoAlbums(this@PickerActivity)
            }
            b.albumName.text = "Semua Video"
            currentBucket = null
            loadVideos()
        }
    }

    private fun showAlbumMenu() {
        if (albums.isEmpty()) return
        val menu = PopupMenu(this, b.albumBar)
        menu.menu.add(0, -1, 0, "Semua Video")
        albums.forEachIndexed { i, a ->
            menu.menu.add(0, i, i + 1, "${a.name}  (${a.count})")
        }
        menu.setOnMenuItemClickListener { mi ->
            if (mi.itemId == -1) {
                currentBucket = null
                b.albumName.text = "Semua Video"
            } else {
                val a = albums[mi.itemId]
                currentBucket = a.id
                b.albumName.text = a.name
            }
            loadVideos()
            true
        }
        menu.show()
    }

    private fun loadVideos() {
        b.emptyBox.visibility = View.GONE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                ProjectStore.videos(this@PickerActivity, currentBucket)
            }
            adapter.submit(list)
            b.count.text = "${list.size} video"
            if (list.isEmpty()) showEmpty("Tidak ada video di album ini")
        }
    }

    private fun showEmpty(msg: String) {
        b.empty.text = msg
        b.emptyBox.visibility = View.VISIBLE
    }

    private fun pick(uri: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java).withReadGrant(uri)
        )
        finish()
    }

    private inner class VideoAdapter(
        val onPick: (Uri) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VH>() {

        private var items: List<ProjectStore.Video> = emptyList()
        // Maksimal ±20 MiB; sebelumnya map tanpa batas bisa menahan 500 bitmap.
        private val thumbs = object : LruCache<Long, Bitmap>(20 * 1024) {
            override fun sizeOf(key: Long, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
        private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

        fun submit(list: List<ProjectStore.Video>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos].uri == list[newPos].uri
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos] == list[newPos]
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val dur: TextView = v.findViewById(R.id.dur)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_video, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val video = items[pos]
            h.dur.text = fmt(video.durationMs)
            val key = video.uri.toString()
            h.img.tag = key
            h.img.setImageDrawable(null)
            h.itemView.setOnClickListener { onPick(video.uri) }

            val id = android.content.ContentUris.parseId(video.uri)
            val cached = thumbs.get(id)
            if (cached != null && !cached.isRecycled) {
                h.img.setImageBitmap(cached)
                return
            }
            if (!inFlight.add(id)) return

            val uri = video.uri
            lifecycleScope.launch {
                val bmp = try {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= 29) {
                                contentResolver.loadThumbnail(
                                    uri, android.util.Size(256, 256), null
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Video.Thumbnails.getThumbnail(
                                    contentResolver, id,
                                    MediaStore.Video.Thumbnails.MINI_KIND, null
                                )
                            }
                        }.getOrNull()
                    }
                } finally {
                    inFlight.remove(id)
                }
                if (bmp != null) {
                    thumbs.put(id, bmp)
                    // Bandingkan URI, bukan posisi: album dapat berubah saat decode berjalan.
                    if (h.img.tag == key) h.img.setImageBitmap(bmp)
                }
            }
        }

        private fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
