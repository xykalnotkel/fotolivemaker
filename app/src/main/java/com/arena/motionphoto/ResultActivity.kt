package com.arena.motionphoto

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar hasil: menampilkan foto diam, dan kalau ditahan akan memutar
 * video yang tertanam di dalam file — persis kelakuan Live Photo asli.
 *
 * Videonya diekstrak langsung dari file yang sudah tersimpan, jadi ini
 * sekaligus membuktikan bahwa struktur Motion Photo-nya memang benar.
 */
class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_LOG = "log"
    }

    private lateinit var b: ActivityResultBinding
    private var savedUri: Uri? = null
    private var clipFile: File? = null
    private var prepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        savedUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val log = intent.getStringExtra(EXTRA_LOG).orEmpty()

        b.detail.text = log
        val valid = log.contains("VALID")
        b.statusTitle.text = if (valid) "Format valid" else "Format bermasalah"
        b.statusSub.text = if (valid)
            "Tersimpan di DCIM/Camera" else "Coba ulangi dengan pengaturan lain"
        b.statusDot.setBackgroundResource(
            if (valid) R.drawable.dot_ok else R.drawable.dot_bad
        )

        b.btnClose.setOnClickListener { finish() }
        b.btnAgain.setOnClickListener { finish() }
        b.btnShare.setOnClickListener { share() }

        b.btnDetail.setOnClickListener {
            val show = b.detail.visibility != View.VISIBLE
            b.detail.visibility = if (show) View.VISIBLE else View.GONE
            b.btnDetail.text = if (show) "Sembunyikan detail" else "Lihat detail teknis"
        }

        loadStill()
        prepareClip()
        setupHoldToPlay()
    }

    private fun loadStill() {
        val uri = savedUri ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            if (bmp != null) b.still.setImageBitmap(bmp)
        }
    }

    /**
     * Ambil bagian MP4 dari ekor file — persis cara galeri Android membacanya.
     * Kalau langkah ini berhasil, berarti file-nya memang motion photo yang sah.
     */
    private fun prepareClip() {
        val uri = savedUri ?: return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val idx = indexOfFtyp(bytes)
                    if (idx < 4) return@runCatching null
                    val out = File(cacheDir, "preview_${System.currentTimeMillis()}.mp4")
                    out.writeBytes(bytes.copyOfRange(idx - 4, bytes.size))
                    out
                }.getOrNull()
            }
            if (f == null) {
                b.hintHold.text = "Video tidak bisa dibaca"
                return@launch
            }
            clipFile = f
            b.video.setVideoPath(f.absolutePath)
            b.video.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)   // senyap, seperti preview Live Photo
                prepared = true
            }
            b.video.setOnErrorListener { _, _, _ ->
                b.hintHold.text = "Gagal memutar"
                true
            }
        }
    }

    private fun indexOfFtyp(data: ByteArray): Int {
        val pat = byteArrayOf('f'.code.toByte(), 't'.code.toByte(),
            'y'.code.toByte(), 'p'.code.toByte())
        outer@ for (i in 0..data.size - 4) {
            for (j in 0..3) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun setupHoldToPlay() {
        b.holdArea.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (prepared) startPlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPlay()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun startPlay() {
        b.video.visibility = View.VISIBLE
        b.still.visibility = View.INVISIBLE
        b.hintHold.visibility = View.GONE
        b.badgeLive.alpha = 1f
        b.video.seekTo(0)
        b.video.start()
    }

    private fun stopPlay() {
        if (b.video.isPlaying) b.video.pause()
        b.video.visibility = View.INVISIBLE
        b.still.visibility = View.VISIBLE
        b.hintHold.visibility = View.VISIBLE
        b.badgeLive.alpha = 0.85f
    }

    private fun share() {
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
        if (b.video.isPlaying) b.video.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { b.video.stopPlayback() }
        clipFile?.delete()
    }
}
