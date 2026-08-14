package livefoto.xystudio.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import livefoto.xystudio.app.databinding.ActivityProcessBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar export: preview adaptif, progress halus, estimasi waktu tenang & manusiawi.
 */
class ProcessActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ASPECT_RATIO = "aspect_ratio"
        const val EXTRA_SQUARE = "square"
        const val EXTRA_RES = "res"
        const val EXTRA_ENHANCE = "enhance"
        const val EXTRA_STABILIZE = "stabilize"
        const val EXTRA_JPEG_QUALITY = "jpeg_quality"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_KEY_MS = "key_ms"
    }

    private lateinit var b: ActivityProcessBinding
    private var job: Job? = null
    private var progress = 0
    private var startedAt = 0L
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (finished) return
            updateEta()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityProcessBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)
        if (Settings.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        b.btnCancel.setOnClickListener { cancelExport() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelExport()
        })

        val uri = intent.data ?: run { finish(); return }
        val res = intent.getStringExtra(EXTRA_RES)
            ?.let { runCatching { Converter.Res.valueOf(it) }.getOrNull() }
            ?: Converter.Res.P1080

        val ratioName = intent.getStringExtra(EXTRA_ASPECT_RATIO)
        val aspectRatio = if (ratioName != null) {
            runCatching { Converter.AspectRatio.valueOf(ratioName) }.getOrDefault(Converter.AspectRatio.RATIO_9_16)
        } else if (intent.getBooleanExtra(EXTRA_SQUARE, false)) {
            Converter.AspectRatio.RATIO_1_1
        } else {
            Converter.AspectRatio.RATIO_9_16
        }

        val opts = Converter.Options(
            aspectRatio = aspectRatio,
            res = res,
            enhance = intent.getBooleanExtra(EXTRA_ENHANCE, false),
            stabilize = intent.getBooleanExtra(EXTRA_STABILIZE, false),
            jpegQuality = intent.getIntExtra(EXTRA_JPEG_QUALITY, 96)
        )
        val hint = if (intent.hasExtra(EXTRA_START_MS)) {
            Converter.Plan(
                totalMs = 0L,
                startMs = intent.getLongExtra(EXTRA_START_MS, 0L),
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, Converter.TARGET_CLIP_MS),
                keyframeOffsetMs = intent.getLongExtra(EXTRA_KEY_MS, Converter.TARGET_CLIP_MS / 2)
            )
        } else null

        loadPreview(uri, opts, hint)
        export(uri, opts, hint)
    }

    private fun loadPreview(uri: Uri, opts: Converter.Options, hint: Converter.Plan?) =
        lifecycleScope.launch {
            val at = (hint?.startMs ?: 0L) + (hint?.keyframeOffsetMs ?: 0L)
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@ProcessActivity, uri, at, opts, 720, 720)
            }
            if (bmp != null) fitPreview(bmp)
        }

    private fun fitPreview(bmp: Bitmap) {
        b.preview.setImageBitmap(bmp)
        val parent = b.previewHost
        parent.post {
            val maxW = parent.width
            val maxH = parent.height
            if (maxW <= 0 || maxH <= 0) return@post
            val density = resources.displayMetrics.density
            val capW = minOf(maxW, (220 * density).toInt())
            val capH = minOf(maxH, (280 * density).toInt())
            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            var w = capW
            var h = (w / aspect).toInt()
            if (h > capH) {
                h = capH
                w = (h * aspect).toInt()
            }
            w = w.coerceAtLeast(120)
            h = h.coerceAtLeast(120)
            b.previewBox.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            b.previewBox.requestLayout()
        }
    }

    private fun export(uri: Uri, opts: Converter.Options, hint: Converter.Plan?) {
        startedAt = SystemClock.elapsedRealtime()
        handler.post(ticker)
        job = lifecycleScope.launch {
            try {
                Converter.convert(
                    this@ProcessActivity, uri, opts,
                    log = { stage -> runOnMain { b.tvStage.text = stage } },
                    progress = { value -> runOnMain { showProgress(value) } },
                    planHint = hint
                ).also { result ->
                    showProgress(100)
                    finished = true
                    handler.removeCallbacks(ticker)
                    b.tvStage.text = "Live Photo selesai dibuat"
                    b.tvEta.text = "Selesai"
                    b.btnCancel.visibility = View.GONE
                    startActivity(
                        Intent(this@ProcessActivity, ResultActivity::class.java)
                            .putExtra(ResultActivity.EXTRA_URI, result.uri.toString())
                    )
                    finish()
                }
            } catch (_: CancellationException) {
                finished = true
            } catch (e: Exception) {
                finished = true
                handler.removeCallbacks(ticker)
                b.tvStage.text = "Export gagal"
                b.tvEta.text = "Berhenti"
                b.tvHint.text = e.message ?: "Coba gunakan 720p atau matikan efek."
                b.btnCancel.text = "KEMBALI"
                b.btnCancel.setOnClickListener { finish() }
                Toast.makeText(
                    this@ProcessActivity,
                    e.message ?: "Export gagal",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showProgress(value: Int) {
        progress = maxOf(progress, value.coerceIn(0, 100))
        b.boxProgress.progress = progress
        b.tvPercent.text = "$progress%"
        updateEta()
    }

    /**
     * Estimasi waktu tenang & manusiawi.
     * Tidak memantul tiap milidetik, memberikan perkiraan yang jelas dan tenang bagi user.
     */
    private fun updateEta() {
        if (finished) return
        if (progress >= 100) {
            b.tvEta.text = "Selesai"
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val etaText = when {
            progress < 15 -> "Menyiapkan video… perkiraan ~15–30 dtk"
            progress in 15..45 -> {
                val remSec = maxOf(5, ((100 - progress) * elapsed / (progress * 1000L)).toInt())
                val rounded = ((remSec + 4) / 5) * 5
                "Memproses video  ·  sisa sekitar ${rounded} dtk"
            }
            progress in 46..85 -> {
                val remSec = maxOf(3, ((100 - progress) * elapsed / (progress * 1000L)).toInt())
                val rounded = ((remSec + 4) / 5) * 5
                "Mengencode video  ·  sisa sekitar ${rounded} dtk"
            }
            else -> "Mengemas metadata Live Photo…"
        }

        b.tvEta.text = etaText
    }

    private fun cancelExport() {
        finished = true
        handler.removeCallbacks(ticker)
        job?.cancel()
        finish()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
