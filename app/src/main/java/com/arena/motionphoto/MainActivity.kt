package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar editor.
 *
 * Trim manual sudah dihapus: potongan 3 detik dihitung otomatis dari
 * durasi video (Converter.plan). Video lebih pendek dari 3 detik dipakai utuh.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var plan: Converter.Plan? = null
    private var busy = false
    private var previewJob: Job? = null
    private val logLines = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnChange.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        b.btnConvert.setOnClickListener { doConvert() }
        b.switchSquare.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        setBusy(false)

        val uri = intent?.data
        if (uri == null) {
            toast("Tidak ada video")
            finish()
            return
        }
        loadVideo(uri)
    }

    private fun loadVideo(uri: Uri) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                Converter.videoDurationMs(this@MainActivity, uri)
            }
            if (d <= 0) {
                toast("Video ini tidak bisa dibaca")
                if (videoUri == null) finish()
                return@launch
            }
            videoUri = uri
            val p = Converter.plan(d)
            plan = p

            b.tvPlan.text = buildString {
                append("Durasi video   : %.1f dtk\n".format(d / 1000f))
                append("Diambil        : %.1f – %.1f dtk\n".format(
                    p.startMs / 1000f, (p.startMs + p.durationMs) / 1000f))
                append("Panjang klip   : %.1f dtk\n".format(p.durationMs / 1000f))
                append("Frame kunci    : +%.1f dtk".format(p.keyframeOffsetMs / 1000f))
            }
            refreshPreview()
        }
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val at = p.startMs + p.keyframeOffsetMs
            val opts = currentOptions().copy(targetHeight = 480)
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@MainActivity, uri, at, opts)
            }
            if (bmp != null) b.preview.setImageBitmap(bmp)
        }
    }

    private fun currentOptions() = Converter.Options(
        square = b.switchSquare.isChecked,
        targetHeight = if (b.switch720.isChecked) 720 else 1080
    )

    private fun log(msg: String) {
        logLines.append(clock.format(Date())).append("  ").append(msg).append('\n')
        b.tvLog.text = logLines.toString().trimEnd()
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun doConvert() {
        val uri = videoUri ?: return
        if (busy) return
        setBusy(true)
        logLines.setLength(0)
        b.logCard.visibility = View.VISIBLE
        log("Mulai proses")

        lifecycleScope.launch {
            try {
                val res = Converter.convert(
                    this@MainActivity, uri, currentOptions(),
                    log = { msg -> log(msg) },
                    progress = { pct ->
                        b.progress.isIndeterminate = false
                        b.progress.progress = pct
                        b.tvPercent.text = "$pct%"
                    }
                )
                log("SELESAI")
                startActivity(
                    Intent(this@MainActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_URI, res.uri.toString())
                        .putExtra(ResultActivity.EXTRA_LOG, res.verifyLog)
                )
            } catch (e: Exception) {
                log("GAGAL: ${e.message}")
                toast(e.message ?: "Konversi gagal")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(v: Boolean) {
        busy = v
        b.progressBox.visibility = if (v) View.VISIBLE else View.GONE
        if (v) {
            b.progress.isIndeterminate = true
            b.tvPercent.text = ""
        }
        b.btnConvert.isEnabled = !v
        b.btnConvert.text = if (v) "Memproses…" else "Buat Live Photo"
        b.btnChange.isEnabled = !v
        b.switchSquare.isEnabled = !v
        b.switch720.isEnabled = !v
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
