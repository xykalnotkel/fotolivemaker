package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivityProcessBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Layar export khusus: preview sengaja dibuka gelap lalu terang mengikuti
 * kemajuan. Progress encoder tidak selalu linear, maka teks tahap tetap jadi
 * sumber informasi utama untuk pengguna. */
class ProcessActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SQUARE = "square"
        const val EXTRA_RES = "res"
        const val EXTRA_ENHANCE = "enhance"
        const val EXTRA_STABILIZE = "stabilize"
        const val EXTRA_JPEG_QUALITY = "jpeg_quality"
    }

    private lateinit var b: ActivityProcessBinding
    private var job: Job? = null
    private var progress = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProcessBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (Settings.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        b.btnCancel.setOnClickListener { cancelExport() }

        val uri = intent.data ?: run { finish(); return }
        val res = intent.getStringExtra(EXTRA_RES)?.let { runCatching { Converter.Res.valueOf(it) }.getOrNull() }
            ?: Converter.Res.P1080
        val opts = Converter.Options(
            square = intent.getBooleanExtra(EXTRA_SQUARE, false),
            res = res,
            enhance = intent.getBooleanExtra(EXTRA_ENHANCE, false),
            stabilize = intent.getBooleanExtra(EXTRA_STABILIZE, false),
            jpegQuality = intent.getIntExtra(EXTRA_JPEG_QUALITY, 95)
        )
        loadPreview(uri, opts)
        export(uri, opts)
    }

    private fun loadPreview(uri: Uri, opts: Converter.Options) = lifecycleScope.launch {
        val bmp = withContext(Dispatchers.IO) { Converter.extractFrame(this@ProcessActivity, uri, 0, opts, 720) }
        if (bmp != null) b.preview.setImageBitmap(bmp)
    }

    private fun export(uri: Uri, opts: Converter.Options) {
        job = lifecycleScope.launch {
            try {
                Converter.convert(this@ProcessActivity, uri, opts,
                    log = { stage ->
                        b.tvStage.text = stage
                        when {
                            stage.startsWith("Membaca") -> showProgress(3)
                            stage.startsWith("Stabilizer") -> showProgress(8)
                            stage.startsWith("Mengambil") -> showProgress(16)
                            stage.startsWith("Menyisipkan") -> showProgress(96)
                            stage.startsWith("Menyimpan") -> showProgress(98)
                        }
                    },
                    progress = { showProgress(it.coerceIn(16, 95)) }
                ).also { result ->
                    showProgress(100)
                    b.tvStage.text = "Live Photo selesai dibuat"
                    b.btnCancel.visibility = View.GONE
                    startActivity(Intent(this@ProcessActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_URI, result.uri.toString()))
                    finish()
                }
            } catch (_: CancellationException) {
                // Tombol batal sudah menutup layar.
            } catch (e: Exception) {
                b.tvStage.text = "Export gagal"
                b.tvHint.text = e.message ?: "Coba gunakan 720p atau matikan efek."
                b.btnCancel.text = "KEMBALI"
                b.btnCancel.setOnClickListener { finish() }
                Toast.makeText(this@ProcessActivity, e.message ?: "Export gagal", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showProgress(value: Int) {
        progress = maxOf(progress, value)
        b.progressRing.setProgressCompat(progress, true)
        b.tvPercent.text = "$progress%"
        // Overlay hitam berkurang sampai 8% pada akhir agar preview masih punya kontras.
        b.previewShade.alpha = (1f - progress / 100f).coerceIn(0.08f, 1f)
    }

    private fun cancelExport() {
        job?.cancel()
        finish()
    }

    override fun onBackPressed() {
        cancelExport()
    }
}
