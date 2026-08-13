package com.arena.motionphoto

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
import com.arena.motionphoto.databinding.ActivityProcessBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar export: preview di tengah tanpa background,
 * progress mengikuti kotak preview, estimasi sisa waktu realtime.
 */
class ProcessActivity : AppCompatActivity() {
    companion object {
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
    private var etaEma = -1.0
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (finished) return
            updateEta()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProcessBinding.inflate(layoutInflater)
        setContentView(b.root)
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
        val opts = Converter.Options(
            square = intent.getBooleanExtra(EXTRA_SQUARE, false),
            res = res,
            enhance = intent.getBooleanExtra(EXTRA_ENHANCE, false),
            stabilize = intent.getBooleanExtra(EXTRA_STABILIZE, false),
            jpegQuality = intent.getIntExtra(EXTRA_JPEG_QUALITY, 95)
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
                Converter.extractFrame(this@ProcessActivity, uri, at, opts, 720)
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
            val capW = minOf(maxW, (200 * density).toInt())
            val capH = minOf(maxH, (268 * density).toInt())
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

    private fun updateEta() {
        if (finished) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (progress < 4 || elapsed < 400L) {
            b.tvEta.text = "Berjalan ${fmt(elapsed)}  ·  menghitung sisa…"
            return
        }
        if (progress >= 100) {
            b.tvEta.text = "Selesai"
            return
        }
        val raw = elapsed * (100.0 - progress) / progress.toDouble()
        etaEma = if (etaEma < 0) raw else etaEma * 0.72 + raw * 0.28
        b.tvEta.text = "Berjalan ${fmt(elapsed)}  ·  sisa ± ${fmt(etaEma.toLong())}"
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return if (s < 60) "${s} dtk" else "${s / 60}:${"%02d".format(s % 60)}"
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
