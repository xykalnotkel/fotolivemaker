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
import com.arena.motionphoto.databinding.DialogProgressBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var plan: Converter.Plan? = null
    private var srcW = 0
    private var srcH = 0
    private var busy = false
    private var previewJob: Job? = null

    private var opts = Converter.Options()

    private val logLines = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var dlg: AlertDialog? = null
    private var dlgB: DialogProgressBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        opts = Settings.load(this)

        b.btnBack.setOnClickListener { finish() }
        b.btnConvert.setOnClickListener { doConvert() }

        b.swSquare.isChecked = opts.square
        b.swEnhance.isChecked = opts.enhance
        b.swStab.isChecked = opts.stabilize
        b.tvRes.text = opts.res.label

        b.rowRes.setOnClickListener { pickRes() }
        b.swSquare.setOnCheckedChangeListener { _, v ->
            opts = opts.copy(square = v); refreshPreview()
        }
        b.swEnhance.setOnCheckedChangeListener { _, v -> opts = opts.copy(enhance = v) }
        b.swStab.setOnCheckedChangeListener { _, v -> opts = opts.copy(stabilize = v) }

        setBusy(false)

        val uri = intent?.data
        if (uri == null) {
            toast("Tidak ada video")
            finish()
            return
        }
        loadVideo(uri)
    }

    private fun pickRes() {
        val items = Converter.Res.values()
        val labels = items.map {
            when (it) {
                Converter.Res.P720 -> "720p  —  paling cepat"
                Converter.Res.P1080 -> "1080p  —  seimbang"
                Converter.Res.SOURCE ->
                    if (srcH > 0) "Asli  —  ${srcW}x${srcH}" else "Asli"
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Resolusi keluaran")
            .setSingleChoiceItems(labels, items.indexOf(opts.res)) { d, w ->
                opts = opts.copy(res = items[w])
                b.tvRes.text = opts.res.label
                updatePlanText()
                d.dismiss()
            }
            .show()
    }

    private fun loadVideo(uri: Uri) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                Converter.videoDurationMs(this@MainActivity, uri)
            }
            if (d <= 0) {
                toast("Video ini tidak bisa dibaca")
                finish()
                return@launch
            }
            val (w, h) = withContext(Dispatchers.IO) {
                Converter.videoSize(this@MainActivity, uri)
            }
            videoUri = uri
            srcW = w; srcH = h
            plan = Converter.plan(d)
            updatePlanText()
            refreshPreview()
        }
    }

    private fun updatePlanText() {
        val p = plan ?: return
        val outH = opts.heightFor(if (srcH > 0) srcH else 1080)
        val dim = if (opts.square) "${outH}x${outH}"
        else {
            val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 0.5625f
            var ow = Math.round(outH * ratio); if (ow % 2 != 0) ow++
            "${ow}x${outH}"
        }
        b.tvPlan.text = buildString {
            append("Sumber   : ${srcW}x${srcH}, %.1f dtk\n".format(p.totalMs / 1000f))
            append("Diambil  : %.1f – %.1f dtk\n".format(
                p.startMs / 1000f, (p.startMs + p.durationMs) / 1000f))
            append("Keluaran : $dim")
        }
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
        updatePlanText()
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val at = p.startMs + p.keyframeOffsetMs
            val bmp = withContext(Dispatchers.IO) {
                Converter.extractFrame(this@MainActivity, uri, at, opts, 640)
            }
            if (bmp != null) b.preview.setImageBitmap(bmp)
        }
    }

    // ---------------- proses ----------------

    private fun log(msg: String) {
        logLines.append(clock.format(Date())).append("  ").append(msg).append('\n')
        dlgB?.let { d ->
            d.tvLog.text = logLines.toString().trimEnd()
            d.logScroll.post { d.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Popup log muncul dari tombol proses. */
    private fun showProgressDialog() {
        val db = DialogProgressBinding.inflate(layoutInflater)
        dlgB = db
        db.tvLog.text = ""
        db.progress.isIndeterminate = true
        db.tvPercent.text = ""
        db.tvStage.text = "Menyiapkan…"
        db.btnClose.visibility = View.GONE

        dlg = AlertDialog.Builder(this)
            .setView(db.root)
            .setCancelable(false)
            .create()
        dlg?.show()
    }

    private fun finishDialog(success: Boolean, msg: String) {
        val db = dlgB ?: return
        db.progress.isIndeterminate = false
        db.progress.progress = if (success) 100 else db.progress.progress
        db.tvStage.text = msg
        db.btnClose.visibility = View.VISIBLE
        db.btnClose.setOnClickListener { dlg?.dismiss(); dlg = null; dlgB = null }
    }

    private fun doConvert() {
        val uri = videoUri ?: return
        if (busy) return
        setBusy(true)
        logLines.setLength(0)
        showProgressDialog()
        Settings.save(this, opts)
        log("Mulai proses")

        lifecycleScope.launch {
            try {
                val res = Converter.convert(
                    this@MainActivity, uri, opts,
                    log = { m ->
                        log(m)
                        dlgB?.tvStage?.text = m
                    },
                    progress = { pct ->
                        dlgB?.let {
                            it.progress.isIndeterminate = false
                            it.progress.progress = pct
                            it.tvPercent.text = "$pct%"
                        }
                    }
                )
                log("SELESAI")
                finishDialog(true, "Selesai")
                dlg?.dismiss(); dlg = null; dlgB = null

                startActivity(
                    Intent(this@MainActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_URI, res.uri.toString())
                )
            } catch (e: Exception) {
                log("GAGAL: ${e.message}")
                finishDialog(false, "Gagal: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(v: Boolean) {
        busy = v
        b.btnConvert.isEnabled = !v
        b.btnConvert.text = if (v) "Memproses…" else "Buat Live Photo"
        b.swSquare.isEnabled = !v
        b.swEnhance.isEnabled = !v
        b.swStab.isEnabled = !v
        b.rowRes.isEnabled = !v
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
