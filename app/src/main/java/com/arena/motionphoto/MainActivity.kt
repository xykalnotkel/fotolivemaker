package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editor: pilih jendela 3 dtk + frame kunci, lalu export di ProcessActivity. */
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
        b.swSquare.isChecked = opts.square
        b.swEnhance.isChecked = opts.enhance
        b.swStab.isChecked = opts.stabilize
        b.tvRes.text = opts.res.label
        b.rowRes.setOnClickListener { pickRes() }

        b.swSquare.setOnCheckedChangeListener { _, on ->
            opts = opts.copy(square = on); updatePlanText(); refreshPreview()
        }
        b.swEnhance.setOnCheckedChangeListener { _, on ->
            opts = opts.copy(enhance = on); updatePlanText(); refreshPreview()
        }
        b.swStab.setOnCheckedChangeListener { _, on ->
            opts = opts.copy(stabilize = on); updatePlanText()
            toast(
                if (on) "Stabilisasi ringan aktif — video akan sedikit di-crop"
                else "Stabilisasi dimatikan"
            )
        }

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

        intent?.data?.let(::loadVideo) ?: run { toast("Tidak ada video"); finish() }
    }

    private fun pickRes() {
        val items = Converter.Res.entries.toTypedArray()
        val labels = items.map {
            when (it) {
                Converter.Res.P720 -> "Hemat · 720p — lebih cepat, file kecil"
                Converter.Res.P1080 -> "HD · 1080p — kualitas seimbang"
                Converter.Res.SOURCE ->
                    if (srcH > 0) "Asli · ${srcW}x${srcH} — file bisa besar" else "Asli"
            }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Kualitas keluaran")
            .setSingleChoiceItems(labels, items.indexOf(opts.res)) { dialog, which ->
                opts = opts.copy(res = items[which])
                b.tvRes.text = opts.res.label
                updatePlanText(); refreshPreview(); dialog.dismiss()
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

    private fun outDim(): Pair<Int, Int> {
        val h = Converter.evenUp(opts.heightFor(srcH.takeIf { it > 0 } ?: 1080))
        if (opts.square) return h to h
        val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 9f / 16f
        return Converter.evenUp(h * ratio) to h
    }

    private fun updatePlanText() {
        val p = plan ?: return
        val tools = buildList {
            if (opts.square) add("crop 1:1")
            if (opts.enhance) add("pertajam")
            if (opts.stabilize) add("stabilisasi ringan")
        }
        val (w, h) = outDim()
        b.tvStartValue.text = "%.1f dtk".format(p.startMs / 1000f)
        b.tvKeyValue.text = "%.1f dtk".format(p.keyframeOffsetMs / 1000f)
        b.tvClipHint.text = if (p.durationMs < Converter.TARGET_CLIP_MS) {
            "Video pendek — seluruh klip ${"%.1f".format(p.durationMs / 1000f)} dtk dipakai"
        } else {
            "Durasi dikunci 3,0 dtk · sama seperti Live Photo iPhone"
        }
        b.tvPlan.text = "Sumber   : ${srcW}x${srcH}, %.1f dtk\n".format(p.totalMs / 1000f) +
            "Diambil  : %.1f – %.1f dtk\n".format(
                p.startMs / 1000f, (p.startMs + p.durationMs) / 1000f
            ) +
            "Kunci    : %.1f dtk dari awal klip\n".format(p.keyframeOffsetMs / 1000f) +
            "Keluaran : ${w}x${h} (${opts.res.label})\n" +
            "Tools    : ${if (tools.isEmpty()) "tidak ada" else tools.joinToString(", ")}"
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(70)
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(
                    this@MainActivity, uri,
                    p.startMs + p.keyframeOffsetMs, opts, 640
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
                putExtra(ProcessActivity.EXTRA_SQUARE, opts.square)
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
