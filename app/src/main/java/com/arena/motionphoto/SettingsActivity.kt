package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arena.motionphoto.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        val o = Settings.load(this)
        b.swSquare.isChecked = o.square
        b.swEnhance.isChecked = o.enhance
        b.swStab.isChecked = o.stabilize
        b.tvRes.text = o.res.label
        b.swKeepScreenOn.isChecked = Settings.keepScreenOn(this)
        b.swSplash.isChecked = Settings.showSplash(this)
        b.tvTheme.text = themeLabel(Settings.theme(this))
        b.tvQuality.text = "${o.jpegQuality}"

        b.rowRes.setOnClickListener { pickRes() }
        b.rowTheme.setOnClickListener { pickTheme() }
        b.rowCache.setOnClickListener { clearCache() }
        b.rowQuality.setOnClickListener { pickQuality() }
        b.rowDeleteAll.setOnClickListener { deleteAllResults() }

        // konfirmasi tiap toggle, jangan diam saja
        b.swSquare.setOnCheckedChangeListener { _, v ->
            persist(); notify(if (v) "Crop 1:1 aktif" else "Crop 1:1 dimatikan")
        }
        b.swEnhance.setOnCheckedChangeListener { _, v ->
            persist(); notify(if (v) "Bersihkan & pertajam aktif" else "Penajaman dimatikan")
        }
        b.swStab.setOnCheckedChangeListener { _, v ->
            persist(); notify(if (v) "Stabilizer aktif" else "Stabilizer dimatikan")
        }
        b.swKeepScreenOn.setOnCheckedChangeListener { _, enabled ->
            Settings.setKeepScreenOn(this, enabled)
            notify(if (enabled) "Layar dijaga tetap menyala saat export" else "Layar boleh mati saat export")
        }
        b.swSplash.setOnCheckedChangeListener { _, enabled ->
            Settings.setShowSplash(this, enabled)
            notify(if (enabled) "Splash singkat aktif" else "Langsung ke beranda")
        }

        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}"

        b.rowLicense.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Lisensi & Privasi")
                .setMessage(getString(R.string.legal_notice))
                .setPositiveButton("Tutup", null)
                .show()
        }
        b.rowSource.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/xykalnotkel/fotolivemaker")
                    )
                )
            }
        }

        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { Stats.collect(this@SettingsActivity) }
            b.statProjectSize.text = Stats.human(s.projectBytes)
            b.statProjectCount.text = "${s.projectCount} hasil"
            b.statFree.text = Stats.human(s.freeBytes)
            b.statCache.text = Stats.human(s.cacheBytes)
            // Pisahkan pemakaian aplikasi dari pemakaian seluruh perangkat agar tidak menyesatkan.
            b.statData.text = Stats.human(s.appDataBytes + s.cacheBytes)
            b.barStorage.progress = s.usedPercent
            b.statStorageLine.text =
                "Perangkat: ${Stats.human(s.usedBytes)} / ${Stats.human(s.totalBytes)}" +
                "  ·  ${s.usedPercent}%"
        }
    }

    private fun clearCache() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { Stats.clearCache(this@SettingsActivity) }
            notify(
                if (freed > 0) "Cache dibersihkan, ${Stats.human(freed)} dikosongkan"
                else "Cache sudah bersih"
            )
            loadStats()
        }
    }

    private fun themeLabel(m: Int) = when (m) {
        Settings.THEME_LIGHT -> "Terang"
        Settings.THEME_DARK -> "Gelap"
        else -> "Ikut sistem"
    }

    private fun pickTheme() {
        val modes = intArrayOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK)
        val labels = arrayOf("Ikut sistem", "Terang", "Gelap")
        val cur = modes.indexOf(Settings.theme(this))
        AlertDialog.Builder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(labels, cur) { d, w ->
                Settings.setTheme(this, modes[w])
                b.tvTheme.text = labels[w]
                d.dismiss()
                recreate()
            }
            .show()
    }

    private fun pickRes() {
        val items = Converter.Res.entries.toTypedArray()
        val labels = items.map {
            when (it) {
                Converter.Res.P720 -> "720p  —  paling cepat"
                Converter.Res.P1080 -> "1080p  —  seimbang"
                Converter.Res.SOURCE -> "Asli  —  ikut resolusi video"
            }
        }.toTypedArray()
        val cur = items.indexOf(Settings.load(this).res)

        AlertDialog.Builder(this)
            .setTitle("Resolusi keluaran")
            .setSingleChoiceItems(labels, cur) { d, which ->
                b.tvRes.text = items[which].label
                persist(res = items[which])
                notify("Resolusi bawaan: ${items[which].label}")
                d.dismiss()
            }
            .show()
    }

    private fun pickQuality() {
        val values = intArrayOf(80, 85, 90, 95, 100)
        val labels = values.map { "$it" }.toTypedArray()
        val cur = values.indexOf(Settings.load(this).jpegQuality).let { if (it < 0) 3 else it }
        AlertDialog.Builder(this)
            .setTitle("Kualitas JPEG cover")
            .setSingleChoiceItems(labels, cur) { d, w ->
                persist(quality = values[w])
                b.tvQuality.text = "${values[w]}"
                notify("Kualitas JPEG: ${values[w]}")
                d.dismiss()
            }
            .show()
    }

    private fun deleteAllResults() {
        AlertDialog.Builder(this)
            .setTitle("Hapus semua hasil?")
            .setMessage("Berkas MP_ di DCIM/Camera yang dibuat app ini akan dihapus dari galeri.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) {
                        ProjectStore.deleteAll(this@SettingsActivity)
                    }
                    notify(if (n > 0) "$n berkas dihapus" else "Tidak ada yang terhapus")
                    loadStats()
                }
            }
            .show()
    }

    private fun persist(res: Converter.Res? = null, quality: Int? = null) {
        val cur = Settings.load(this)
        Settings.save(
            this,
            cur.copy(
                res = res ?: cur.res,
                square = b.swSquare.isChecked,
                enhance = b.swEnhance.isChecked,
                stabilize = b.swStab.isChecked,
                jpegQuality = quality ?: cur.jpegQuality
            )
        )
    }

    private fun notify(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
