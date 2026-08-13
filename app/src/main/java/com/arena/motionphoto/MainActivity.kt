package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editor: slider jendela potong + frame kunci + tool rail rasio & kualitas. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var plan: Converter.Plan? = null
    private var srcW = 0
    private var srcH = 0
    private var totalMs = 0L
    private var startSec = 0f
    private var keySec = 1.5f
    private var previewJob: Job? = null
    private var bindingSliders = false
    private var opts = Converter.Options()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        opts = Settings.load(this)

        b.btnBack.setOnClickListener { finish() }
        b.btnExport.setOnClickListener { openExport() }
        b.toolRatio.setOnClickListener { pickRatio() }
        b.toolEnhance.setOnClickListener {
            opts = opts.copy(enhance = !opts.enhance)
            paintTools(); updatePlanText(); refreshPreview()
            toast(
                if (opts.enhance) "Bersih aktif: filter bilateral & tepi tajam tanpa bintik noise."
                else "Bersih dinonaktifkan"
            )
        }
        b.toolStab.setOnClickListener {
            opts = opts.copy(stabilize = !opts.stabilize)
            paintTools(); updatePlanText()
            toast(
                if (opts.stabilize) "Stabilisasi aktif: kompensasi multi-blok getaran tangan."
                else "Stabilisasi dinonaktifkan"
            )
        }
        b.toolRes.setOnClickListener { pickRes() }

        b.sliderStart.addOnChangeListener { _, value, fromUser ->
            if (bindingSliders || !fromUser) return@addOnChangeListener
            startSec = value
            rebuildPlan()
            refreshPreview()
        }
        b.sliderKey.addOnChangeListener { _, value, fromUser ->
            if (bindingSliders || !fromUser) return@addOnChangeListener
            keySec = value
            rebuildPlan()
            refreshPreview()
        }

        paintTools()
        intent?.data?.let(::loadVideo) ?: run { toast("Tidak ada video"); finish() }
    }

    private fun paintTools() {
        val isRatioActive = opts.aspectRatio != Converter.AspectRatio.ORIGINAL
        paintTool(b.iconRatio, b.lblRatio, isRatioActive)
        b.lblRatio.text = opts.aspectRatio.label

        paintTool(b.iconEnhance, b.lblEnhance, opts.enhance)
        paintTool(b.iconStab, b.lblStab, opts.stabilize)
        b.lblRes.text = opts.res.label
        val ink = ContextCompat.getColor(this, R.color.ink)
        b.iconRes.setColorFilter(ink)
        b.lblRes.setTextColor(ink)
    }

    private fun paintTool(icon: ImageView, label: TextView, on: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (on) R.color.gold_live_dark else R.color.text_mid
        )
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    private fun pickRatio() {
        val items = Converter.AspectRatio.entries.toTypedArray()
        val labels = items.map {
            when (it) {
                Converter.AspectRatio.ORIGINAL -> "Asli · Mengikuti rasio video sumber"
                Converter.AspectRatio.RATIO_9_16 -> "9:16 · Layar Penuh (TikTok / Reels / Story)"
                Converter.AspectRatio.RATIO_3_4 -> "3:4 · Standar Foto Portrait"
                Converter.AspectRatio.RATIO_1_1 -> "1:1 · Persegi / Square Feed"
                Converter.AspectRatio.RATIO_4_3 -> "4:3 · Format Foto Klasik"
                Converter.AspectRatio.RATIO_16_9 -> "16:9 · Landscape Cinematic"
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pilih Rasio Aspek")
            .setSingleChoiceItems(labels, items.indexOf(opts.aspectRatio)) { dialog, which ->
                opts = opts.copy(aspectRatio = items[which])
                paintTools()
                updatePlanText()
                refreshPreview()
                dialog.dismiss()
            }
            .show()
    }

    private fun pickRes() {
        val items = Converter.Res.entries.toTypedArray()
        val labels = items.map {
            when (it) {
                Converter.Res.P720 -> "Hemat · 720p — proses lebih cepat & file ringan"
                Converter.Res.P1080 -> "HD · 1080p — tajam & seimbang (direkomendasikan)"
                Converter.Res.SOURCE ->
                    if (srcH > 0) "Asli · ${srcW}x${srcH} — kualitas maksimal sumber" else "Asli"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kualitas Resolusi")
            .setSingleChoiceItems(labels, items.indexOf(opts.res)) { dialog, which ->
                opts = opts.copy(res = items[which])
                paintTools(); updatePlanText(); refreshPreview(); dialog.dismiss()
            }.show()
    }

    private fun loadVideo(uri: Uri) = lifecycleScope.launch {
        val duration = withContext(Dispatchers.IO) {
            Converter.videoDurationMs(this@MainActivity, uri)
        }
        if (duration <= 0) {
            toast("Video ini tidak bisa dibaca"); finish(); return@launch
        }
        val (w, h) = withContext(Dispatchers.IO) {
            Converter.videoSize(this@MainActivity, uri)
        }
        videoUri = uri
        srcW = w
        srcH = h
        totalMs = duration
        val auto = Converter.plan(duration)
        startSec = auto.startMs / 1000f
        keySec = auto.keyframeOffsetMs / 1000f
        applySliders()
        refreshPreview()
    }

    private fun applySliders() {
        val sl = Converter.clipSliders(totalMs, startSec, keySec)
        bindingSliders = true
        setSlider(b.sliderStart, sl.start)
        setSlider(b.sliderKey, sl.key)
        startSec = sl.start.third
        keySec = sl.key.third
        b.rowStart.visibility = if (sl.showStart) View.VISIBLE else View.GONE
        bindingSliders = false
        rebuildPlan()
    }

    private fun setSlider(slider: Slider, range: Triple<Float, Float, Float>) {
        val (from, to, value) = range
        slider.stepSize = 0f
        slider.valueFrom = 0f
        slider.valueTo = 100f
        slider.value = 0f
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = 0.1f
        slider.value = value
    }

    private fun rebuildPlan() {
        if (totalMs <= 0) return
        val sl = Converter.clipSliders(totalMs, startSec, keySec)
        plan = Converter.sanitize(
            totalMs,
            (sl.start.third * 1000f).toLong(),
            (sl.clipSec * 1000f).toLong(),
            (sl.key.third * 1000f).toLong()
        )
        updatePlanText()
    }

    private fun updatePlanText() {
        val p = plan ?: return
        val tools = buildList {
            if (opts.aspectRatio != Converter.AspectRatio.ORIGINAL) {
                add("rasio ${opts.aspectRatio.label}")
            }
            if (opts.enhance) add("bersih")
            if (opts.stabilize) add("stabil")
        }
        val (w, h) = Converter.calculateDimensions(srcW, srcH, opts)
        b.tvStartValue.text = "%.1f dtk".format(p.startMs / 1000f)
        b.tvKeyValue.text = "%.1f dtk".format(p.keyframeOffsetMs / 1000f)
        b.tvClipHint.text = if (p.durationMs < Converter.TARGET_CLIP_MS) {
            "Video pendek — seluruh klip ${"%.1f".format(p.durationMs / 1000f)} dtk dipakai"
        } else {
            "Durasi klip dikunci 3,0 dtk (standar Live Photo)"
        }
        b.tvPlan.text = "Sumber   : ${srcW}x${srcH}, %.1f dtk\n".format(p.totalMs / 1000f) +
            "Diambil  : %.1f – %.1f dtk\n".format(
                p.startMs / 1000f, (p.startMs + p.durationMs) / 1000f
            ) +
            "Cover    : %.1f dtk dari awal klip\n".format(p.keyframeOffsetMs / 1000f) +
            "Keluaran : ${w}x${h} (${opts.res.label} · ${opts.aspectRatio.label})\n" +
            "Efek     : ${if (tools.isEmpty()) "standar" else tools.joinToString(", ")}"
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(60)
            val (targetW, targetH) = Converter.calculateDimensions(srcW, srcH, opts)
            val previewH = 640
            val previewW = (previewH * (targetW.toFloat() / targetH.coerceAtLeast(1))).toInt()
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(
                    this@MainActivity, uri,
                    p.startMs + p.keyframeOffsetMs, opts,
                    previewW, previewH
                )
            }
            if (bmp != null) b.preview.setImageBitmap(bmp)
        }
    }

    private fun openExport() {
        val uri = videoUri ?: return
        val p = plan ?: run { toast("Video masih disiapkan"); return }
        Settings.save(this, opts)
        startActivity(
            Intent(this, ProcessActivity::class.java).withReadGrant(uri).apply {
                putExtra(ProcessActivity.EXTRA_ASPECT_RATIO, opts.aspectRatio.name)
                putExtra(ProcessActivity.EXTRA_SQUARE, opts.aspectRatio == Converter.AspectRatio.RATIO_1_1)
                putExtra(ProcessActivity.EXTRA_RES, opts.res.name)
                putExtra(ProcessActivity.EXTRA_ENHANCE, opts.enhance)
                putExtra(ProcessActivity.EXTRA_STABILIZE, opts.stabilize)
                putExtra(ProcessActivity.EXTRA_JPEG_QUALITY, opts.jpegQuality)
                putExtra(ProcessActivity.EXTRA_START_MS, p.startMs)
                putExtra(ProcessActivity.EXTRA_DURATION_MS, p.durationMs)
                putExtra(ProcessActivity.EXTRA_KEY_MS, p.keyframeOffsetMs)
            }
        )
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
