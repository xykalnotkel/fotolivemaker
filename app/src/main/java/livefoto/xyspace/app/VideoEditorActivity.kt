package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import livefoto.xyspace.app.databinding.ActivityVideoEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Video Editor terpisah ala CapCut / Alight Motion:
 * - Timeline trim 3 detik ke atas (tidak dikunci 3 detik, bisa full)
 * - Ratio, Resolusi HD/UHD (720p, 1080p, 2K, 4K UHD), Bersih, Stabil
 * - Export sebagai MP4 murni (bukan Motion Photo)
 * - Alur terpisah dari Foto Live agar gampang atur HD/UHD
 */
class VideoEditorActivity : AppCompatActivity() {
    private lateinit var b: ActivityVideoEditorBinding
    private var videoUri: Uri? = null
    private var plan: Converter.Plan? = null
    private var srcW = 0
    private var srcH = 0
    private var totalMs = 0L
    private var startSec = 0f
    private var keySec = 1.5f
    private var previewJob: Job? = null
    private var timelineJob: Job? = null
    private var previewBitmap: Bitmap? = null
    private var opts = Converter.Options(res = Converter.Res.P1080)

    private val requestLegacyWrite = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openExportGranted()
        else toast("Izin penyimpanan diperlukan di Android 8-9")
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)
        opts = Settings.load(this).copy(res = Converter.Res.P1080)

        b.btnBack.setOnClickListener { finish() }
        b.btnExportVideo.setOnClickListener {
            Settings.triggerHaptic(it)
            openExport()
        }
        b.toolRatio.setOnClickListener {
            Settings.triggerHaptic(it)
            pickRatio()
        }
        b.toolEnhance.setOnClickListener {
            Settings.triggerHaptic(it)
            opts = opts.copy(enhance = !opts.enhance)
            paintTools(); updatePlanText(); refreshPreview()
            toast(if (opts.enhance) "HD Bersih aktif" else "HD Bersih mati")
        }
        b.toolStab.setOnClickListener {
            Settings.triggerHaptic(it)
            opts = opts.copy(stabilize = !opts.stabilize)
            paintTools(); updatePlanText()
            toast(if (opts.stabilize) "Stabilizer aktif - mulus kayak iPhone" else "Stabilizer mati")
        }
        b.toolRes.setOnClickListener {
            Settings.triggerHaptic(it)
            pickRes()
        }

        b.timeline.setListener { startMs, keyOffsetMs, finished ->
            startSec = startMs / 1000f
            keySec = keyOffsetMs / 1000f
            rebuildPlan(updateTimeline = false)
            refreshPreview()
            if (finished) Settings.triggerHaptic(b.timeline)
        }

        paintTools()

        // Tombol fitur yang SUDAH jadi (aktif di semua build)
        b.toolVideo.setOnClickListener { toast("Video: pilih media dari galeri") ; pickVideoLauncher.launch("video/*") }
        b.toolCrop.setOnClickListener { toast("Crop: potong video - pakai Ratio") ; pickRatio() }

        // Tombol fitur yang MASIH DEVELOPMENT — di release cukup disable dengan toast informatif
        val devMsg = if (BuildConfig.DEBUG) {
            { s: String -> toast("$s - segera") }
        } else {
            { s: String -> toast("$s (akan datang di update berikutnya)") }
        }
        b.toolAudio.setOnClickListener { devMsg("Audio") }
        b.toolText.setOnClickListener { devMsg("Teks") }
        b.toolShape.setOnClickListener { devMsg("Shape") }
        b.toolEffect.setOnClickListener { devMsg("Efek") }
        b.toolFilter.setOnClickListener { devMsg("Filter") }
        b.toolOverlay.setOnClickListener { devMsg("Overlay") }
        b.toolKeyframe.setOnClickListener { devMsg("Keyframe") }
        b.toolCurve.setOnClickListener { devMsg("Kurva") }
        b.toolMask.setOnClickListener { devMsg("Masking") }
        b.toolGroup.setOnClickListener { devMsg("Grup") }
        b.toolRotate.setOnClickListener { devMsg("Rotate") }
        b.toolDraw.setOnClickListener { devMsg("Drawing") }
        b.btnAddLayer.setOnClickListener { devMsg("Tambah layer") }

        // Layers list dummy adapter
        setupLayersList()

        // Cek intent: kalau dari share video atau picker
        val incoming = intent?.data
        if (incoming != null) {
            loadVideo(incoming)
        } else {
            // Buka picker langsung
            pickVideoLauncher.launch("video/*")
        }
    }

    private fun paintTools() {
        val accent = Settings.color(this, R.attr.appAccent)
        val muted = Settings.color(this, R.attr.appTextMid)
        val high = Settings.color(this, R.attr.appTextHigh)

        b.toolRatio.isSelected = opts.aspectRatio != Converter.AspectRatio.RATIO_9_16
        b.toolEnhance.isSelected = opts.enhance
        b.toolStab.isSelected = opts.stabilize
        b.toolRes.isSelected = true

        b.lblRatio.text = opts.aspectRatio.label
        b.lblRatio.setTextColor(if (b.toolRatio.isSelected) accent else high)
        b.lblEnhance.setTextColor(if (opts.enhance) accent else muted)
        b.lblStab.setTextColor(if (opts.stabilize) accent else muted)
        b.lblRes.text = opts.res.label
        b.lblRes.setTextColor(accent)

        ImageViewCompat.setImageTintList(b.iconRatio, ColorStateList.valueOf(if (b.toolRatio.isSelected) accent else high))
        ImageViewCompat.setImageTintList(b.iconEnhance, ColorStateList.valueOf(if (opts.enhance) accent else muted))
        ImageViewCompat.setImageTintList(b.iconStab, ColorStateList.valueOf(if (opts.stabilize) accent else muted))
        ImageViewCompat.setImageTintList(b.iconRes, ColorStateList.valueOf(accent))
    }

    private fun pickRatio() {
        val items = Converter.AspectRatio.entries.toTypedArray()
        val choices = items.map {
            when (it) {
                Converter.AspectRatio.ORIGINAL -> CustomDialogs.ChoiceItem("Asli", "Rasio bawaan video")
                Converter.AspectRatio.RATIO_9_16 -> CustomDialogs.ChoiceItem("9:16", "Portrait penuh - TikTok, Reels")
                Converter.AspectRatio.RATIO_3_4 -> CustomDialogs.ChoiceItem("3:4", "Portrait")
                Converter.AspectRatio.RATIO_1_1 -> CustomDialogs.ChoiceItem("1:1", "Persegi")
                Converter.AspectRatio.RATIO_4_3 -> CustomDialogs.ChoiceItem("4:3", "Klasik")
                Converter.AspectRatio.RATIO_16_9 -> CustomDialogs.ChoiceItem("16:9", "Landscape cinematic")
            }
        }
        CustomDialogs.showChoiceDialog(this, "Rasio", "Pilih rasio video", choices, items.indexOf(opts.aspectRatio)) { which ->
            opts = opts.copy(aspectRatio = items[which])
            paintTools(); updatePlanText(); refreshPreview()
        }
    }

    private fun pickRes() {
        val items = arrayOf(
            Converter.Res.P720,
            Converter.Res.P1080,
            Converter.Res.P1440,
            Converter.Res.P2160,
            Converter.Res.SOURCE
        )
        val choices = items.map {
            when (it) {
                Converter.Res.P720 -> CustomDialogs.ChoiceItem("720p HD", "Hemat, cepat")
                Converter.Res.P1080 -> CustomDialogs.ChoiceItem("1080p Full HD", "Tajam standar - rekomendasi")
                Converter.Res.P1440 -> CustomDialogs.ChoiceItem("2K QHD", "Lebih tajam untuk layar besar")
                Converter.Res.P2160 -> CustomDialogs.ChoiceItem("4K UHD", "Ultra HD - file besar, butuh HP kencang")
                Converter.Res.SOURCE -> CustomDialogs.ChoiceItem("Asli ${srcW}x${srcH}", "Ikut resolusi sumber")
            }
        }
        CustomDialogs.showChoiceDialog(this, "Resolusi HD/UHD", "Pilih kualitas export", choices, items.indexOf(opts.res)) { which ->
            opts = opts.copy(res = items[which])
            paintTools(); updatePlanText(); refreshPreview()
        }
    }

    private fun setupLayersList() {
        // Layer list dinamis yang nunjukin video yang lagi diedit
        refreshLayerList()
        b.tvLayerCount.text = "1 LAYER"
    }

    private fun refreshLayerList() {
        val layers = mutableListOf<String>()
        if (videoUri != null) {
            layers.add("Video Utama (${srcW}x${srcH})")
        } else {
            layers.add("(pilih video)")
        }

        b.layersList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
        )
        b.layersList.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = layers.size
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup, viewType: Int
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_video_layer, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                    init {
                        v.setOnClickListener {
                            // Tap layer → edit video yang dipilih
                            toast("Layer: ${layers[adapterPosition]}")
                        }
                    }
                }
            }
            override fun onBindViewHolder(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int
            ) {
                val tv = holder.itemView.findViewById<android.widget.TextView>(R.id.tvLayerName)
                tv?.text = layers[position]
            }
        }
        b.tvLayerCount.text = "${layers.size} LAYERS"
    }


    private fun loadVideo(uri: Uri) = lifecycleScope.launch {
        val duration = withContext(Dispatchers.IO) { Converter.videoDurationMs(this@VideoEditorActivity, uri) }
        if (duration <= 0) {
            toast("Video tidak bisa dibaca"); finish(); return@launch
        }
        val (w, h) = withContext(Dispatchers.IO) { Converter.videoSize(this@VideoEditorActivity, uri) }
        videoUri = uri
        srcW = w; srcH = h; totalMs = duration
        val auto = Converter.plan(duration)
        startSec = auto.startMs / 1000f
        keySec = auto.keyframeOffsetMs / 1000f
        rebuildPlan(true)
        loadTimelineFrames(uri)
        refreshPreview()
        refreshLayerList()
    }

    private fun rebuildPlan(updateTimeline: Boolean = true) {
        if (totalMs <= 0) return
        val clipMs = totalMs // di video editor, default full video, bukan 3 detik
        plan = Converter.sanitize(totalMs, (startSec * 1000f).toLong(), clipMs, (keySec * 1000f).toLong())
        plan?.let {
            startSec = it.startMs / 1000f
            keySec = it.keyframeOffsetMs / 1000f
            if (updateTimeline) {
                b.timeline.configure(it.totalMs, it.startMs, it.durationMs, it.keyframeOffsetMs)
            }
        }
        updatePlanText()
    }

    private fun loadTimelineFrames(uri: Uri) {
        timelineJob?.cancel()
        timelineJob = lifecycleScope.launch {
            val frames = withContext(Dispatchers.IO) {
                Converter.extractTimelineFrames(this@VideoEditorActivity, uri, totalMs, 10)
            }
            b.timeline.setFrames(frames)
        }
    }

    private fun updatePlanText() {
        val p = plan ?: return
        val tools = buildList {
            if (opts.aspectRatio != Converter.AspectRatio.ORIGINAL) add(opts.aspectRatio.label)
            if (opts.enhance) add("HD Bersih")
            if (opts.stabilize) add("iPhone Stabil")
        }
        val (w, h) = Converter.calculateDimensions(srcW, srcH, opts)
        b.tvStartValue.text = "%.1fs".format(p.startMs / 1000f)
        b.tvKeyValue.text = "%.1fs".format(p.keyframeOffsetMs / 1000f)
        b.tvClipHint.text = "%.1fs durasi".format(p.durationMs / 1000f)
        b.tvPlan.text = "Sumber: ${srcW}x${srcH}, %.1fs\n".format(p.totalMs / 1000f) +
                "Keluaran: ${w}x${h} (${opts.res.label} ${opts.aspectRatio.label})\n" +
                "Efek: ${if (tools.isEmpty()) "standar" else tools.joinToString(", ")}"
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(50)
            val (targetW, targetH) = Converter.calculateDimensions(srcW, srcH, opts)
            val previewH = 640
            val previewW = (previewH * (targetW.toFloat() / targetH.coerceAtLeast(1))).toInt()
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@VideoEditorActivity, uri, p.startMs + p.keyframeOffsetMs, opts, previewW, previewH)
            }
            if (bmp != null) {
                val old = previewBitmap
                previewBitmap = bmp
                fitPreviewBox(bmp)
                b.preview.setImageBitmap(bmp)
                if (old != null && old !== bmp && !old.isRecycled) old.recycle()
            }
        }
    }

    private fun fitPreviewBox(bmp: android.graphics.Bitmap) {
        val parent = b.previewHost
        parent.post {
            val maxW = parent.width
            val maxH = parent.height
            if (maxW <= 0 || maxH <= 0) return@post
            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            var w = maxW
            var h = (w / aspect).toInt()
            if (h > maxH) {
                h = maxH
                w = (h * aspect).toInt()
            }
            w = w.coerceAtLeast(120)
            h = h.coerceAtLeast(120)
            b.previewBox.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            b.previewBox.requestLayout()
        }
    }

    private fun openExport() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLegacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        openExportGranted()
    }

    private fun openExportGranted() {
        val uri = videoUri ?: return
        val p = plan ?: run { toast("Video masih disiapkan"); return }
        Settings.save(this, opts)
        startActivity(
            Intent(this, VideoProcessActivity::class.java).withReadGrant(uri).apply {
                putExtra(ProcessActivity.EXTRA_ASPECT_RATIO, opts.aspectRatio.name)
                putExtra(ProcessActivity.EXTRA_RES, opts.res.name)
                putExtra(ProcessActivity.EXTRA_ENHANCE, opts.enhance)
                putExtra(ProcessActivity.EXTRA_STABILIZE, opts.stabilize)
                putExtra(ProcessActivity.EXTRA_JPEG_QUALITY, 96)
                putExtra(ProcessActivity.EXTRA_START_MS, p.startMs)
                putExtra(ProcessActivity.EXTRA_DURATION_MS, p.durationMs)
                putExtra(ProcessActivity.EXTRA_KEY_MS, p.keyframeOffsetMs)
            }
        )
    }

    override fun onDestroy() {
        previewJob?.cancel()
        timelineJob?.cancel()
        b.timeline.release()
        previewBitmap?.takeIf { !it.isRecycled }?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
