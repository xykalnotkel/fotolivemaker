package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityMainBinding
import com.arena.motionphoto.databinding.PopupProgressBinding
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
    private var convertJob: Job? = null

    private var opts = Converter.Options()

    private val logLines = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var popup: PopupWindow? = null
    private var pb: PopupProgressBinding? = null
    private var startedAt = 0L

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

        // Setiap toggle memberi konfirmasi + memperbarui rencana secara nyata
        b.swSquare.setOnCheckedChangeListener { _, v ->
            opts = opts.copy(square = v)
            updatePlanText()
            refreshPreview()
            notify(if (v) "Crop 1:1 aktif — preview & video ikut kotak"
                   else "Crop 1:1 dimatikan — rasio asli")
        }
        b.swEnhance.setOnCheckedChangeListener { _, v ->
            opts = opts.copy(enhance = v)
            updatePlanText()
            notify(if (v) "Bersihkan & pertajam aktif — proses agak lebih lama"
                   else "Penajaman dimatikan")
        }
        b.swStab.setOnCheckedChangeListener { _, v ->
            opts = opts.copy(stabilize = v)
            updatePlanText()
            notify(if (v) "Stabilizer aktif — video dianalisis dulu, sedikit ter-zoom"
                   else "Stabilizer dimatikan")
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
                refreshPreview()
                notify("Resolusi: ${outDimText()}")
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

    /** Ukuran keluaran, dihitung sama persis seperti di Converter. */
    private fun outDim(): Pair<Int, Int> {
        val outH = evenUp(opts.heightFor(if (srcH > 0) srcH else 1080).toFloat())
        return if (opts.square) outH to outH
        else {
            val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 0.5625f
            evenUp(outH * ratio) to outH
        }
    }

    private fun evenUp(v: Float): Int {
        var x = Math.round(v); if (x < 2) x = 2; if (x % 2 != 0) x++
        return x
    }

    private fun outDimText(): String {
        val (w, h) = outDim()
        return "${w}x${h}"
    }

    private fun updatePlanText() {
        val p = plan ?: return
        val tools = buildList {
            if (opts.square) add("crop 1:1")
            if (opts.enhance) add("pertajam")
            if (opts.stabilize) add("stabilizer")
        }
        b.tvPlan.text = buildString {
            append("Sumber   : ${srcW}x${srcH}, %.1f dtk\n".format(p.totalMs / 1000f))
            append("Diambil  : %.1f – %.1f dtk\n".format(
                p.startMs / 1000f, (p.startMs + p.durationMs) / 1000f))
            append("Keluaran : ${outDimText()}\n")
            append("Tools    : ${if (tools.isEmpty()) "tidak ada" else tools.joinToString(", ")}")
        }
    }

    private fun refreshPreview() {
        val uri = videoUri ?: return
        val p = plan ?: return
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
        pb?.let { p ->
            p.tvLog.text = logLines.toString().trimEnd()
            p.logScroll.post { p.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Panel melayang tepat di atas tombol Proses, muncul dengan animasi
     * tumbuh dari tombol — bukan dialog di tengah layar.
     */
    private fun showPopup() {
        val v = PopupProgressBinding.inflate(layoutInflater)
        pb = v

        val width = b.btnConvert.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(40))

        popup = PopupWindow(v.root, width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = false
            isFocusable = false
            elevation = dp(14).toFloat()
            setBackgroundDrawable(null)
        }

        v.btnToggleLog.setOnClickListener {
            val show = v.logScroll.visibility != View.VISIBLE
            v.logScroll.visibility = if (show) View.VISIBLE else View.GONE
            v.btnToggleLog.text = if (show) "Sembunyikan log" else "Lihat log"
            popup?.update(
                b.btnConvert, 0, dp(10),
                width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        v.btnCancel.setOnClickListener { cancelConvert() }

        // muncul di ATAS tombol, jarak dekat
        popup?.showAsDropDown(b.btnConvert, 0, -(b.btnConvert.height + dp(280)), Gravity.START)

        // animasi tumbuh dari arah tombol
        v.root.apply {
            alpha = 0f
            scaleX = 0.90f
            scaleY = 0.80f
            pivotY = height.toFloat()
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(240)
                .setInterpolator(OvershootInterpolator(0.9f))
                .start()
        }
    }

    private fun dismissPopup() {
        val v = pb?.root
        if (v == null) { popup?.dismiss(); popup = null; pb = null; return }
        v.animate().alpha(0f).scaleY(0.85f).setDuration(150).withEndAction {
            popup?.dismiss(); popup = null; pb = null
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateEta(pct: Int) {
        if (pct <= 2) return
        val elapsed = System.currentTimeMillis() - startedAt
        val total = elapsed * 100.0 / pct
        val left = ((total - elapsed) / 1000).toLong().coerceAtLeast(0)
        pb?.tvEta?.text = if (left >= 60)
            "sisa ± %d menit %02d detik".format(left / 60, left % 60)
        else "sisa ± $left detik"
    }

    private fun cancelConvert() {
        convertJob?.cancel()
        log("Dibatalkan pengguna")
        pb?.tvStage?.text = "Dibatalkan"
        pb?.tvEta?.text = ""
        dismissPopup()
        setBusy(false)
        toast("Proses dibatalkan")
    }

    private fun doConvert() {
        val uri = videoUri ?: return
        if (busy) return
        setBusy(true)
        logLines.setLength(0)
        startedAt = System.currentTimeMillis()
        showPopup()
        Settings.save(this, opts)
        log("Mulai proses")

        convertJob = lifecycleScope.launch {
            try {
                val res = Converter.convert(
                    this@MainActivity, uri, opts,
                    log = { m ->
                        log(m)
                        pb?.tvStage?.text = m
                    },
                    progress = { pct ->
                        pb?.let {
                            it.progress.isIndeterminate = false
                            it.progress.progress = pct
                            it.tvPercent.text = "$pct%"
                        }
                        updateEta(pct)
                    }
                )
                log("SELESAI")
                val secs = (System.currentTimeMillis() - startedAt) / 1000
                pb?.tvStage?.text = "Selesai dalam ${secs}s"
                pb?.tvEta?.text = ""
                dismissPopup()

                startActivity(
                    Intent(this@MainActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_URI, res.uri.toString())
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // sudah ditangani cancelConvert()
            } catch (e: Exception) {
                log("GAGAL: ${e.message}")
                pb?.tvStage?.text = "Gagal"
                pb?.tvEta?.text = e.message ?: ""
                pb?.btnCancel?.text = "Tutup"
                pb?.btnCancel?.setOnClickListener { dismissPopup(); setBusy(false) }
                toast(e.message ?: "Konversi gagal")
            } finally {
                if (popup == null) setBusy(false)
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

    override fun onDestroy() {
        super.onDestroy()
        runCatching { popup?.dismiss() }
        popup = null; pb = null
    }

    private fun notify(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
