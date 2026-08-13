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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var durationMs = 0L
    private var busy = false
    private var ready = false          // penjaga: jangan proses event slider saat setup
    private var previewJob: Job? = null

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

        b.sliderStart.addOnChangeListener { _, _, fromUser -> if (fromUser) onSliderMoved() }
        b.sliderKey.addOnChangeListener { _, _, fromUser -> if (fromUser) onSliderMoved() }
        b.sliderDur.addOnChangeListener { _, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            reclampAfterDuration()
            onSliderMoved()
        }

        setBusy(false)

        val uri = intent?.data
        if (uri == null) {
            toast("Tidak ada video")
            finish()
            return
        }
        loadVideo(uri)
    }

    // ---------------------------------------------------------------
    // Penyebab crash sebelumnya: Material Slider melempar exception
    // kalau value/valueTo tidak konsisten. Semua nilai sekarang
    // dibulatkan ke 0.1 dan diurutkan penulisannya (range dulu, baru value).
    // ---------------------------------------------------------------
    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

    /** Tulis range + value ke slider dengan urutan yang aman. */
    private fun setSlider(
        s: com.google.android.material.slider.Slider,
        from: Float,
        to: Float,
        value: Float
    ) {
        val f = round1(from)
        // valueTo harus selalu lebih besar dari valueFrom, minimal 0.1 jaraknya
        val t = round1(max(to, f + 0.1f))
        val v = round1(value).coerceIn(f, t)
        // urutan penting: kalau value lama di luar range baru, set dulu ke batas
        if (s.value < f || s.value > t) s.value = f
        s.valueFrom = f
        s.valueTo = t
        s.value = v
    }

    private fun loadVideo(uri: Uri) {
        ready = false
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
            durationMs = d
            val totalSec = round1(d / 1000f)

            // durasi klip: maksimal 3 dtk, tapi tidak boleh melebihi panjang video
            val maxDur = min(6f, totalSec)
            val defDur = min(3f, totalSec)
            setSlider(b.sliderDur, 0.5f, maxDur, defDur)

            val dur = b.sliderDur.value
            val maxStart = max(0f, totalSec - dur)
            val defStart = maxStart / 2f
            setSlider(b.sliderStart, 0f, maxStart, defStart)
            setSlider(b.sliderKey, 0f, dur, dur / 2f)

            ready = true
            updateLabels()
            refreshPreview()
        }
    }

    private fun reclampAfterDuration() {
        if (!ready) return
        val totalSec = round1(durationMs / 1000f)
        val dur = b.sliderDur.value
        setSlider(b.sliderStart, 0f, max(0f, totalSec - dur), b.sliderStart.value)
        setSlider(b.sliderKey, 0f, dur, min(b.sliderKey.value, dur))
    }

    private fun onSliderMoved() {
        if (!ready) return
        updateLabels()
        refreshPreview()
    }

    private fun updateLabels() {
        b.valStart.text = "%.1fs".format(b.sliderStart.value)
        b.valDur.text = "%.1fs".format(b.sliderDur.value)
        b.valKey.text = "+%.1fs".format(b.sliderKey.value)
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(160)   // debounce biar tidak berat saat slider digeser
            val at = ((b.sliderStart.value + b.sliderKey.value) * 1000).toLong()
            val opts = currentOptions().copy(targetHeight = 480)
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@MainActivity, uri, at, opts)
            }
            if (bmp != null) b.preview.setImageBitmap(bmp)
        }
    }

    private fun currentOptions() = Converter.Options(
        startMs = (b.sliderStart.value * 1000).toLong(),
        durationMs = (b.sliderDur.value * 1000).toLong(),
        keyframeOffsetMs = (b.sliderKey.value * 1000).toLong(),
        square = b.switchSquare.isChecked,
        targetHeight = if (b.switch720.isChecked) 720 else 1080
    )

    private fun doConvert() {
        val uri = videoUri ?: return
        if (busy) return
        setBusy(true)

        lifecycleScope.launch {
            try {
                val (saved, log) = Converter.convert(
                    this@MainActivity, uri, currentOptions()
                ) { msg -> b.tvStatus.text = msg }

                startActivity(
                    Intent(this@MainActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_URI, saved.toString())
                        .putExtra(ResultActivity.EXTRA_LOG, log)
                )
                b.tvStatus.text = ""
            } catch (e: Exception) {
                b.tvStatus.text = "Gagal: ${e.message}"
                toast(e.message ?: "Konversi gagal")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(v: Boolean) {
        busy = v
        b.progress.visibility = if (v) android.view.View.VISIBLE else android.view.View.GONE
        b.btnConvert.isEnabled = !v
        b.btnConvert.text = if (v) "Memproses…" else "Buat Live Photo"
        b.btnChange.isEnabled = !v
        b.sliderStart.isEnabled = !v
        b.sliderDur.isEnabled = !v
        b.sliderKey.isEnabled = !v
        b.switchSquare.isEnabled = !v
        b.switch720.isEnabled = !v
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
