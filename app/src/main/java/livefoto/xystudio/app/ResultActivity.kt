package livefoto.xystudio.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import livefoto.xystudio.app.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar hasil.
 *
 * Preview memakai ExoPlayer, bukan VideoView. VideoView sering gagal
 * menyiapkan berkas ketika view-nya masih invisible, sehingga tahan-layar
 * tidak memutar apa pun.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
    }

    private lateinit var b: ActivityResultBinding
    private var savedUri: Uri? = null
    private var clipFile: File? = null
    private var player: ExoPlayer? = null
    private var stillBitmap: Bitmap? = null
    private var verifyJob: Job? = null
    private var prepared = false
    private var playedOk = false

    /** Mode preview: STATIC = foto diam, LIVE = tahan untuk putar, LOOP = putar terus. */
    private enum class Mode { STATIC, LIVE, LOOP }
    private var mode = Mode.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)

        savedUri = intent.getStringExtra(EXTRA_URI)?.toUri()

        b.btnClose.setOnClickListener { finish() }
        b.btnAgain.setOnClickListener { finish() }
        b.btnShare.setOnClickListener { showShareChoice() }
        b.btnTikTok.setOnClickListener {
            // TikTok Android 2026 sudah bisa baca JPG Motion Photo sebagai Live
            shareJpgToPackage(
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "TikTok"
            )
        }
        b.btnTikTok.setOnLongClickListener {
            shareVideoToPackage(
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "TikTok"
            )
            true
        }
        b.btnInstagram.setOnClickListener { shareToInstagramStory() }
        b.btnWhatsApp.setOnClickListener {
            // WhatsApp 2025+ support Motion Photo JPG native dengan icon play
            shareJpgToPackage(
                listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp"
            )
        }
        b.btnWhatsApp.setOnLongClickListener {
            shareVideoToPackage(
                listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp"
            )
            true
        }
        b.btnGallery.setOnClickListener { openInGallery() }
        b.btnRecheck.setOnClickListener { runVerification() }

        b.modeStatic.setOnClickListener { setMode(Mode.STATIC) }
        b.modeLive.setOnClickListener { setMode(Mode.LIVE) }
        b.modeLoop.setOnClickListener { setMode(Mode.LOOP) }

        b.btnDetail.setOnClickListener {
            val show = b.detail.visibility != View.VISIBLE
            b.detail.visibility = if (show) View.VISIBLE else View.GONE
            b.btnDetail.text = if (show) "Sembunyikan detail" else "Lihat detail teknis"
        }

        loadStill()
        // Persiapan klip dan verifikasi dijalankan berurutan supaya dua salinan
        // penuh Motion Photo tidak berada di heap pada waktu yang sama.
        prepareClip()
        setupHoldToPlay()
        setMode(Mode.LIVE)
    }

    private fun runVerification() {
        val uri = savedUri ?: return
        verifyJob?.cancel()
        b.badgeText.text = "CEK…"
        b.btnRecheck.isEnabled = false
        verifyJob = lifecycleScope.launch {
            try {
                val rep = withContext(Dispatchers.IO) {
                    MotionPhotoVerifier.verify(this@ResultActivity, uri)
                }
                b.statusTitle.text = rep.headline
                b.detail.text = rep.detail
                b.chkStruct.text = mark(rep.lengthOk && rep.xmpOk) + "  Struktur file Motion Photo"
                b.chkVideo.text = mark(rep.videoPlayable) + "  Video bisa diputar" +
                    if (rep.videoPlayable) "  ·  ${rep.videoDurationMs} ms  ·  ${rep.videoSize}" else ""
                b.chkSystem.text = when (rep.systemFlag) {
                    true -> mark(true) + "  Ditandai sistem Android"
                    false -> "○  Galeri HP ini belum menandainya"
                    null -> "–  Penanda sistem tak tersedia di Android ini"
                }

                when (rep.level) {
                    MotionPhotoVerifier.Level.CONFIRMED -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_ok)
                        b.illusResult.setImageResource(R.drawable.illus_export_success)
                        b.statusSub.text = "Android mengenali berkas ini sebagai motion photo"
                        b.badgeText.text = "LIVE"
                        b.badgeIcon.alpha = 1f
                        b.badgeLive.alpha = 1f
                        b.btnRecheck.visibility = View.GONE
                    }
                    MotionPhotoVerifier.Level.LIKELY -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_accent)
                        b.statusSub.text =
                            "Struktur dan video lolos. Coba buka di galeri atau picker tujuan."
                        b.badgeText.text = "LIVE"
                        b.badgeIcon.alpha = 1f
                        b.badgeLive.alpha = 1f
                        b.btnRecheck.visibility = View.VISIBLE
                    }
                    MotionPhotoVerifier.Level.FAILED -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_bad)
                        b.statusSub.text = "Coba ulangi dengan pengaturan lain"
                        b.badgeText.text = "GAGAL"
                        b.badgeIcon.alpha = 0.45f
                        b.badgeLive.alpha = 0.55f
                        b.btnRecheck.visibility = View.VISIBLE
                    }
                }
            } finally {
                b.btnRecheck.isEnabled = true
            }
        }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✕"

    private fun setMode(m: Mode) {
        mode = m
        // tandai yang aktif dengan tebal + hitam, sisanya redup
        val on = Settings.color(this, R.attr.appInk)
        val off = Settings.color(this, R.attr.appTextDim)
        b.modeStatic.setTextColor(if (m == Mode.STATIC) on else off)
        b.modeLive.setTextColor(if (m == Mode.LIVE) on else off)
        b.modeLoop.setTextColor(if (m == Mode.LOOP) on else off)

        when (m) {
            Mode.STATIC -> {
                stopPlay()
                b.hintHold.visibility = View.GONE
            }
            Mode.LIVE -> {
                stopPlay()
                b.hintHold.visibility = View.VISIBLE
                b.hintHold.text = "Tahan untuk memutar"
            }
            Mode.LOOP -> {
                // Putar berulang terus. Berkasnya TIDAK diubah sama sekali —
                // ini hanya cara menonton, jadi statusnya tetap Live Photo.
                b.hintHold.visibility = View.VISIBLE
                b.hintHold.text = "Diputar berulang · berkas tetap Live Photo"
                if (prepared) startPlay() else
                    Toast.makeText(this, "Video belum siap…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadStill() {
        val uri = savedUri ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeSampledStill(uri, 2048) }
            if (bmp != null) {
                stillBitmap?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
                stillBitmap = bmp
                b.still.setImageBitmap(bmp)
            }
        }
    }

    private fun decodeSampledStill(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /** Ambil MP4 dari ekor berkas — cara yang sama dipakai galeri Android. */
    private fun prepareClip() {
        val uri = savedUri ?: return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val mp4 = MotionPhotoWriter.extractMp4(bytes) ?: return@runCatching null
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "LivePhoto_${System.currentTimeMillis()}.mp4")
                    out.writeBytes(mp4)
                    out
                }.getOrNull()
            }
            if (f == null) {
                b.hintHold.text = "Video tidak bisa dibaca"
                runVerification()
                return@launch
            }
            clipFile = f

            val p = ExoPlayer.Builder(this@ResultActivity).build()
            player = p
            b.playerView.player = p
            b.playerView.useController = false
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        prepared = true
                        if (mode == Mode.LOOP) startPlay()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    b.hintHold.text = "Gagal memutar: ${error.errorCodeName}"
                }
            })
            p.prepare()
            runVerification()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldToPlay() {
        b.holdArea.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (mode != Mode.LIVE) return@setOnTouchListener false
                    if (prepared) startPlay() else
                        Toast.makeText(this, "Video belum siap…", Toast.LENGTH_SHORT).show()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode != Mode.LIVE) return@setOnTouchListener false
                    stopPlay()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun startPlay() {
        val p = player ?: return
        b.playerView.visibility = View.VISIBLE
        b.still.visibility = View.INVISIBLE
        b.hintHold.visibility = View.GONE
        p.seekTo(0)
        p.playWhenReady = true
        playedOk = true
    }

    private fun stopPlay() {
        player?.playWhenReady = false
        b.playerView.visibility = View.INVISIBLE
        b.still.visibility = View.VISIBLE
        if (mode == Mode.LIVE) {
            b.hintHold.visibility = View.VISIBLE
            if (playedOk) b.hintHold.text = "Berhasil diputar · tahan lagi"
        }
    }

    // ---------------- buka & bagikan ----------------

    /**
     * Buka berkas di aplikasi galeri.
     *
     * Ini cara menguji yang benar: Motion Photo hanya utuh kalau dibaca
     * langsung dari galeri. Begitu dikirim lewat Intent share, aplikasi
     * penerima umumnya memproses ulang gambarnya dan ekor MP4-nya hilang.
     */
    private fun openInGallery() {
        val uri = savedUri ?: return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(this, "Tidak ada aplikasi galeri", Toast.LENGTH_SHORT).show()
        }
    }

    /** MP4 ekstraksi untuk platform yang masih butuh video murni. */
    private fun shareVideoUri(): Uri? {
        val file = clipFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Video share masih disiapkan", Toast.LENGTH_SHORT).show()
            return null
        }
        return FileProvider.getUriForFile(this, "$packageName.files", file)
    }

    /** JPG Motion Photo asli - format yang sekarang dibaca Live di Android 2026. */
    private fun shareJpgUri(): Uri? {
        val uri = savedUri
        if (uri == null) {
            Toast.makeText(this, "File Motion Photo belum siap", Toast.LENGTH_SHORT).show()
            return null
        }
        return uri
    }

    private fun installedPackage(candidates: List<String>): String? =
        candidates.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

    private fun baseVideoShare(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newRawUri("Foto Live video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun baseJpgShare(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newRawUri("Foto Live JPG", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun shareVideoToPackage(candidates: List<String>, label: String) {
        val uri = shareVideoUri() ?: return
        val target = installedPackage(candidates)
        if (target == null) {
            Toast.makeText(this, "$label belum terpasang", Toast.LENGTH_SHORT).show()
            shareVideoGeneric()
            return
        }
        grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            startActivity(baseVideoShare(uri).setPackage(target))
        }.onFailure {
            Toast.makeText(this, "Tidak bisa membuka $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareJpgToPackage(candidates: List<String>, label: String) {
        val uri = shareJpgUri() ?: return
        val target = installedPackage(candidates)
        if (target == null) {
            Toast.makeText(this, "$label belum terpasang", Toast.LENGTH_SHORT).show()
            shareJpgGeneric()
            return
        }
        grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            startActivity(baseJpgShare(uri).setPackage(target))
        }.onFailure {
            // Fallback ke video bila app versi lama tidak handle Motion Photo JPG
            val vUri = shareVideoUri()
            if (vUri != null) {
                runCatching {
                    grantUriPermission(target, vUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(baseVideoShare(vUri).setPackage(target))
                }
            } else {
                Toast.makeText(this, "Tidak bisa membuka $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareToInstagramStory() {
        val uri = shareVideoUri() ?: return
        val target = installedPackage(listOf("com.instagram.android"))
        if (target == null) {
            Toast.makeText(this, "Instagram belum terpasang", Toast.LENGTH_SHORT).show()
            shareVideoGeneric()
            return
        }
        grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val story = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(uri, "video/mp4")
            setPackage(target)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            if (story.resolveActivity(packageManager) != null) startActivity(story)
            else startActivity(baseVideoShare(uri).setPackage(target))
        }.onFailure {
            Toast.makeText(this, "Tidak bisa membuka Instagram Story", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideoGeneric() {
        val uri = shareVideoUri() ?: return
        runCatching {
            startActivity(Intent.createChooser(baseVideoShare(uri), "Bagikan video MP4"))
        }.onFailure {
            Toast.makeText(this, "Tidak ada aplikasi untuk membagikan video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareJpgGeneric() {
        val uri = shareJpgUri() ?: return
        runCatching {
            startActivity(Intent.createChooser(baseJpgShare(uri), "Bagikan Foto Live JPG"))
        }.onFailure {
            Toast.makeText(this, "Tidak ada aplikasi untuk membagikan Foto Live", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShareChoice() {
        CustomDialogs.showChoiceDialog(
            context = this,
            title = "Bagikan Foto Live",
            subtitle = "Pilih format. JPG Motion Photo sekarang kebaca Live di WhatsApp & TikTok Android 2026. MP4 tetap untuk IG Story & editor.",
            choices = listOf(
                CustomDialogs.ChoiceItem(
                    "Foto Live JPG (Motion)",
                    "Label Live/Motion muncul di WA & TikTok 2026, file asli tetap JPG"
                ),
                CustomDialogs.ChoiceItem(
                    "Video MP4 murni",
                    "Gerak tetap terlihat di semua aplikasi, cocok untuk IG Story & Status"
                )
            ),
            selectedIndex = 0
        ) { idx ->
            if (idx == 0) shareJpgGeneric() else shareVideoGeneric()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        b.playerView.player = null
        stillBitmap?.takeIf { !it.isRecycled }?.recycle()
        stillBitmap = null
        // File share dipertahankan di cache sampai sesi berikutnya agar aplikasi
        // tujuan sempat membaca URI walaupun activity ini dihancurkan.
        clipFile = null
        super.onDestroy()
    }
}
