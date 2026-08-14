package livefoto.xystudio.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import livefoto.xystudio.app.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private val adapter = ProjectAdapter()
    private var updateCheckRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)

        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        b.cardAdd.setOnClickListener {
            startActivity(Intent(this, PickerActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnHapusSemua.setOnClickListener { confirmDeleteAll() }
        b.btnUpdateClick.setOnClickListener { openUpdate() }
        b.btnDismissUpdate.setOnClickListener {
            UpdateCheck.cached(this)?.takeIf { it.newer }?.let {
                UpdateCheck.dismiss(this, it.tag)
            }
            b.updateBanner.visibility = View.GONE
        }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.isNestedScrollingEnabled = false

        handleIncoming(intent)
        paintBanner(UpdateCheck.cached(this))
    }

    override fun onResume() {
        super.onResume()
        refresh()
        paintBanner(UpdateCheck.cached(this))
        checkForUpdatesSilently()
    }

    /** Banner benar-benar hilang bila tidak ada versi yang lebih baru. */
    private fun paintBanner(info: UpdateCheck.Info?) {
        val show = info?.newer == true && !UpdateCheck.isDismissed(this, info.tag)
        b.updateBanner.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            b.tvUpdateLabel.text = "Update tersedia"
            b.tvUpdateVer.text = info.tag.removePrefix("v")
        }
    }

    /** Cek foreground, maksimal sekali per 24 jam; tidak ada background worker. */
    private fun checkForUpdatesSilently() {
        if (updateCheckRunning || !UpdateCheck.online(this) || !UpdateCheck.shouldAutoCheck(this)) {
            return
        }
        updateCheckRunning = true
        UpdateCheck.markCheckAttempt(this)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateCheck.fetch(this@HomeActivity) }
            updateCheckRunning = false
            paintBanner(info ?: UpdateCheck.cached(this@HomeActivity))
        }
    }

    private fun openUpdate() {
        if (!UpdateCheck.online(this)) {
            AlertDialog.Builder(this)
                .setTitle("Nyalakan data")
                .setMessage(
                    "Update sudah terdeteksi, tetapi jaringan dibutuhkan untuk " +
                        "memeriksa ulang dan mengunduh APK.\n\nNyalakan data/Wi-Fi, lalu ketuk lagi."
                )
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }
        val wait = AlertDialog.Builder(this)
            .setMessage("Mengecek rilis GitHub…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateCheck.fetch(this@HomeActivity) }
            wait.dismiss()
            paintBanner(info)
            if (info == null) {
                Toast.makeText(this@HomeActivity, "Gagal cek rilis. Coba lagi.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!info.newer) {
                Toast.makeText(this@HomeActivity, "Sudah versi terbaru", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Update ${info.tag}")
                .setMessage("Ada rilis lebih baru. Unduh di app, atau buka halaman resmi.")
                .setPositiveButton("Unduh di app") { _, _ -> startDownload(info) }
                .setNeutralButton("Buka web") { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, info.pageUrl.toUri()))
                    }
                }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    private fun startDownload(info: UpdateCheck.Info) {
        val url = info.apkUrl
        if (url.isNullOrBlank()) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, info.pageUrl.toUri()))
            }
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Mengunduh ${info.tag}")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val dest = UpdateCheck.apkFile(this@HomeActivity)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateCheck.downloadApk(url, dest) { p ->
                        runOnMain { dlg.setMessage("$p%") }
                    }
                }.isSuccess
            }
            dlg.dismiss()
            if (!ok || !dest.exists()) {
                Toast.makeText(this@HomeActivity, "Unduhan gagal. Buka web saja.", Toast.LENGTH_LONG).show()
                return@launch
            }
            installApk(dest)
        }
    }

    private fun installApk(file: java.io.File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Izin pasang aplikasi")
                .setMessage("Android minta izin memasang APK dari sumber ini. Aktifkan, lalu ketuk update lagi.")
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    startActivity(
                        Intent(
                            SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:$packageName".toUri()
                        )
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Terima video lewat "Bagikan ke" dari galeri. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("handled", false)) return
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            intent.putExtra("handled", true)
            startActivity(Intent(this, MainActivity::class.java).withReadGrant(uri))
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { ProjectStore.list(this@HomeActivity) }
            adapter.submit(items)
            b.tvCount.text = if (items.isEmpty()) "" else "${items.size}"
            val bytes = items.sumOf { it.sizeBytes }
            b.heroStat.text = if (items.isEmpty()) "SIAP DIPAKAI"
                else "${items.size} HASIL  ·  ${Stats.human(bytes)}"
            b.emptyBox.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            b.btnHapusSemua.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Hapus semua hasil?")
            .setMessage("Berkas Live Photo di DCIM/Camera yang dibuat app ini akan dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) {
                        ProjectStore.deleteAll(this@HomeActivity)
                    }
                    Toast.makeText(
                        this@HomeActivity,
                        if (n > 0) "$n berkas dihapus" else "Tidak ada yang terhapus",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            }
            .show()
    }

    // ---------------- adapter riwayat ----------------

    private inner class ProjectAdapter : RecyclerView.Adapter<ProjectAdapter.VH>() {

        private var items: List<ProjectStore.Item> = emptyList()
        private val fmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("id-ID"))
        private val cache = HashMap<String, Bitmap>()

        fun submit(list: List<ProjectStore.Item>) {
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
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val more: ImageView = v.findViewById(R.id.more)
            val del: ImageView = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_project, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            // jangan pakai nama "it": lambda runCatching/let punya "it" sendiri
            val item = items[pos]
            h.name.text = item.name
            h.meta.text = "${fmt.format(Date(item.dateMs))}   ·   " +
                "${item.width}x${item.height}   ·   ${item.sizeBytes / 1024} KB"
            val key = item.uri.toString()
            h.thumb.tag = key
            h.thumb.setImageDrawable(null)

            h.itemView.setOnClickListener { open(item) }
            h.more.setOnClickListener { v -> menuFor(v, item) }
            h.del.setOnClickListener { confirmDelete(item) }

            val cached = cache[key]
            if (cached != null) {
                h.thumb.setImageBitmap(cached)
                return
            }

            val uri = item.uri
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 29) {
                            contentResolver.loadThumbnail(
                                uri, android.util.Size(200, 200), null
                            )
                        } else {
                            contentResolver.openInputStream(uri)?.use { s ->
                                val o = android.graphics.BitmapFactory.Options()
                                    .apply { inSampleSize = 8 }
                                android.graphics.BitmapFactory.decodeStream(s, null, o)
                            }
                        }
                    }.getOrNull()
                }
                if (bmp != null) {
                    cache[key] = bmp
                    if (h.thumb.tag == key) h.thumb.setImageBitmap(bmp)
                }
            }
        }

        private fun open(item: ProjectStore.Item) {
            startActivity(
                Intent(this@HomeActivity, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_URI, item.uri.toString())
            )
        }

        private fun menuFor(anchor: View, item: ProjectStore.Item) {
            PopupMenu(this@HomeActivity, anchor).apply {
                menu.add(0, 1, 0, "Buka")
                menu.add(0, 2, 1, "Bagikan")
                menu.add(0, 3, 2, "Hapus")
                setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> open(item)
                        2 -> share(item.uri)
                        3 -> confirmDelete(item)
                    }
                    true
                }
                show()
            }
        }

        private fun share(uri: Uri) {
            runCatching {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Bagikan"
                    )
                )
            }
        }

        private fun confirmDelete(item: ProjectStore.Item) {
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Hapus berkas ini?")
                .setMessage(item.name)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ProjectStore.delete(this@HomeActivity, item.uri)
                        }
                        Toast.makeText(
                            this@HomeActivity,
                            if (ok) "Dihapus" else "Gagal menghapus",
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
                .show()
        }
    }
}
