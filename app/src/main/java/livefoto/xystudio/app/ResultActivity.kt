package livefoto.xystudio.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import livefoto.xystudio.app.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
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
    private var prepared = false
    private var playedOk = false

    /** Mode preview: STATIC = foto diam, LIVE = tahan untuk putar, LOOP = putar terus. */
    private enum class Mode { STATIC, LIVE, LOOP }
    private var mode = Mode.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        savedUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)

        b.btnClose.setOnClickListener { finish() }
        b.btnAgain.setOnClickListener { finish() }
        b.btnShare.setOnClickListener { share() }
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
        prepareClip()
        setupHoldToPlay()
        setMode(Mode.LIVE)
        runVerification()
    }

    private fun runVerification() {
        val uri = savedUri ?: return
        b.badgeText.text = "CEK…"
        lifecycleScope.launch {
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
                false -> "○  Galeri HP ini tidak menandainya — wajar, TikTok tetap bisa"
                null -> "–  Penanda sistem tak tersedia di Android ini"
            }

            when (rep.level) {
                MotionPhotoVerifier.Level.CONFIRMED -> {
                    b.statusDot.setBackgroundResource(R.drawable.dot_ok)
                    b.illusResult.setImageResource(R.drawable.illus_done)
                    b.statusSub.text = "Android mengenali berkas ini sebagai motion photo"
                    b.badgeText.text = "LIVE"
                    b.badgeIcon.alpha = 1f
                    b.badgeLive.alpha = 1f
                    b.btnRecheck.visibility = View.GONE
                }
                MotionPhotoVerifier.Level.LIKELY -> {
                    b.statusDot.setBackgroundResource(R.drawable.dot_accent)
                    b.statusSub.text =
                        "Struktur benar. Banyak galeri Android tidak bisa " +
                        "memutarnya, tapi TikTok tetap membacanya — coba upload."
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
        }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✕"

    private fun setMode(m: Mode) {
        mode = m
        // tandai yang aktif dengan tebal + hitam, sisanya redup
        val on = androidx.core.content.ContextCompat.getColor(this, R.color.ink)
        val off = androidx.core.content.ContextCompat.getColor(this, R.color.text_dim)
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
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) b.still.setImageBitmap(bmp)
        }
    }

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
        }
    }

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

    /**
     * Bagikan berkas Motion Photo apa adanya.
     *
     * Diberi peringatan lebih dulu, karena banyak aplikasi (termasuk TikTok)
     * memproses ulang gambar yang diterima lewat share sehingga bagian
     * videonya terbuang dan hasilnya jadi foto diam.
     */
    private fun share() {
        AlertDialog.Builder(this)
            .setTitle("Bagikan Live Photo")
            .setMessage(
                "Banyak aplikasi memproses ulang gambar yang dikirim lewat " +
                    "tombol bagikan, sehingga bagian videonya hilang dan yang " +
                    "sampai cuma foto diam.\n\n" +
                    "Supaya tetap jadi Live Photo, pilih berkasnya langsung " +
                    "dari galeri di dalam aplikasi tujuan."
            )
            .setPositiveButton("Tetap bagikan") { _, _ -> doShare() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doShare() {
        val uri = savedUri ?: return
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Bagikan Live Photo"
                )
            )
        }.onFailure {
            Toast.makeText(this, "Tidak bisa membagikan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
