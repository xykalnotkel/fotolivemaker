package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var durationMs = 0L
    private var busy = false

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        onVideoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPick.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        b.btnConvert.setOnClickListener { doConvert() }

        b.sliderStart.addOnChangeListener { _, _, _ -> updateLabels() }
        b.sliderKey.addOnChangeListener { _, _, _ -> updateLabels() }
        b.sliderDur.addOnChangeListener { _, _, _ ->
            clampStart()
            updateLabels()
        }

        setEnabledState(false)
        // handle share/kirim-ke dari galeri
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null && intent.type?.startsWith("video") != false) {
            onVideoPicked(uri)
        }
    }

    private fun onVideoPicked(uri: Uri) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { Converter.videoDurationMs(this@MainActivity, uri) }
            if (d <= 0) {
                toast("Tidak bisa membaca video ini")
                return@launch
            }
            videoUri = uri
            durationMs = d

            val maxDur = minOf(3f, d / 1000f)
            b.sliderDur.valueFrom = 0.5f
            b.sliderDur.valueTo = maxOf(1f, minOf(6f, d / 1000f))
            b.sliderDur.value = maxOf(0.5f, maxDur)

            clampStart()
            // default: ambil bagian tengah video
            b.sliderStart.value = ((d / 1000f - b.sliderDur.value) / 2f)
                .coerceIn(b.sliderStart.valueFrom, b.sliderStart.valueTo)

            setEnabledState(true)
            updateLabels()
            refreshPreview()
        }
    }

    private fun clampStart() {
        val total = durationMs / 1000f
        val dur = b.sliderDur.value
        val maxStart = maxOf(0f, total - dur)
        b.sliderStart.valueFrom = 0f
        b.sliderStart.valueTo = maxOf(0.01f, maxStart)
        if (b.sliderStart.value > b.sliderStart.valueTo) {
            b.sliderStart.value = b.sliderStart.valueTo
        }
        b.sliderKey.valueFrom = 0f
        b.sliderKey.valueTo = dur
        if (b.sliderKey.value > dur) b.sliderKey.value = dur / 2f
    }

    private fun updateLabels() {
        val s = b.sliderStart.value
        val d = b.sliderDur.value
        val k = b.sliderKey.value
        b.tvInfo.text = "Potong: %.2fs → %.2fs  (%.2fs)\nFrame kunci: +%.2fs".format(s, s + d, d, k)
        refreshPreview()
    }

    private var previewJob: kotlinx.coroutines.Job? = null
    private fun refreshPreview() {
        val uri = videoUri ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(180) // debounce saat slider digeser
            val at = ((b.sliderStart.value + b.sliderKey.value) * 1000).toLong()
            val opts = currentOptions() ?: return@launch
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@MainActivity, uri, at, opts.copy(targetHeight = 480))
            }
            if (bmp != null) b.preview.setImageBitmap(bmp)
        }
    }

    private fun currentOptions(): Converter.Options? {
        return Converter.Options(
            startMs = (b.sliderStart.value * 1000).toLong(),
            durationMs = (b.sliderDur.value * 1000).toLong(),
            keyframeOffsetMs = (b.sliderKey.value * 1000).toLong(),
            square = b.switchSquare.isChecked,
            targetHeight = if (b.switch720.isChecked) 720 else 1080
        )
    }

    private fun doConvert() {
        val uri = videoUri ?: return
        if (busy) return
        val opts = currentOptions() ?: return

        busy = true
        setEnabledState(false)
        b.progress.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val (saved, log) = Converter.convert(this@MainActivity, uri, opts) { msg ->
                    b.tvStatus.text = msg
                }
                b.tvStatus.text = "✅ Tersimpan ke DCIM/Camera\n\n$log"
                toast("Berhasil! Cek galeri.")
            } catch (e: Exception) {
                b.tvStatus.text = "❌ Gagal: ${e.message}"
                toast("Gagal: ${e.message}")
            } finally {
                busy = false
                setEnabledState(true)
                b.progress.visibility = android.view.View.GONE
            }
        }
    }

    private fun setEnabledState(hasVideo: Boolean) {
        b.btnConvert.isEnabled = hasVideo && !busy
        b.sliderStart.isEnabled = hasVideo && !busy
        b.sliderDur.isEnabled = hasVideo && !busy
        b.sliderKey.isEnabled = hasVideo && !busy
        b.switchSquare.isEnabled = hasVideo && !busy
        b.switch720.isEnabled = hasVideo && !busy
        b.btnPick.isEnabled = !busy
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
