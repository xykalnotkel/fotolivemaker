package livefoto.xystudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import livefoto.xystudio.app.databinding.ActivityPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val askPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
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
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.albumBar.setOnClickListener { showAlbumMenu() }
        b.btnSystemPick.setOnClickListener { openSystemPicker() }
        b.btnEmptyPick.setOnClickListener { openSystemPicker() }

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter

        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED
        ) loadAlbums() else askPerm.launch(perm)
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
        private val thumbs = ConcurrentHashMap<Long, Bitmap>()

        fun submit(list: List<ProjectStore.Video>) {
            items = list
            notifyDataSetChanged()
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
            h.img.setImageDrawable(null)
            h.itemView.setOnClickListener { onPick(video.uri) }

            val id = android.content.ContentUris.parseId(video.uri)
            val cached = thumbs[id]
            if (cached != null) {
                h.img.setImageBitmap(cached)
                return
            }
            val uri = video.uri
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 29) {
                            contentResolver.loadThumbnail(
                                uri, android.util.Size(320, 320), null
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
                if (bmp != null) {
                    thumbs[id] = bmp
                    if (h.bindingAdapterPosition == pos) h.img.setImageBitmap(bmp)
                }
            }
        }

        private fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
