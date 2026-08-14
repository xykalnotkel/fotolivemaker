package livefoto.xystudio.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import livefoto.xystudio.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Settings.applyAccessibility(this, b.root)

        if (Settings.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        b.btnBack.setOnClickListener { finish() }

        val o = Settings.load(this)
        b.tvRes.text = o.res.label
        b.tvQuality.text = "${o.jpegQuality}%"
        b.tvTheme.text = themeLabel(Settings.theme(this))

        b.swKeepScreenOn.isChecked = Settings.keepScreenOn(this)
        b.swSplash.isChecked = Settings.showSplash(this)
        b.swHighContrast.isChecked = Settings.isHighContrast(this)
        b.swHaptics.isChecked = Settings.isHapticsEnabled(this)
        b.swReduceMotion.isChecked = Settings.isReduceMotion(this)

        b.rowRes.setOnClickListener {
            Settings.triggerHaptic(it)
            pickRes()
        }
        b.rowQuality.setOnClickListener {
            Settings.triggerHaptic(it)
            pickQuality()
        }
        b.rowTheme.setOnClickListener {
            Settings.triggerHaptic(it)
            pickTheme()
        }
        b.rowCache.setOnClickListener {
            Settings.triggerHaptic(it)
            clearCache()
        }
        b.rowDeleteAll.setOnClickListener {
            Settings.triggerHaptic(it)
            deleteAllResults()
        }

        b.swKeepScreenOn.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setKeepScreenOn(this, enabled)
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            notify(if (enabled) "Layar dijaga tetap menyala saat export" else "Layar boleh mati saat export")
        }
        b.swSplash.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setShowSplash(this, enabled)
            notify(if (enabled) "Splash intro aktif" else "Langsung ke beranda")
        }
        b.swHighContrast.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setHighContrast(this, enabled)
            recreate()
        }
        b.swHaptics.setOnCheckedChangeListener { v, enabled ->
            Settings.setHapticsEnabled(this, enabled)
            Settings.triggerHaptic(v)
            notify(if (enabled) "Getaran haptic aktif" else "Getaran haptic dimatikan")
        }
        b.swReduceMotion.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setReduceMotion(this, enabled)
            recreate()
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
                        "https://github.com/xykalnotkel/fotolivemaker".toUri()
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
            b.statProjectCount.text = "${s.projectCount} hasil dibuat"
            b.statFree.text = Stats.human(s.freeBytes)
            b.statCache.text = Stats.human(s.cacheBytes)
            b.barStorage.progress = s.usedPercent
            b.statStorageLine.text =
                "Penyimpanan: ${Stats.human(s.usedBytes)} / ${Stats.human(s.totalBytes)} (${s.usedPercent}%)"
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
        val choices = listOf(
            CustomDialogs.ChoiceItem("Ikut Sistem", "Menyesuaikan otomatis dengan tema perangkat"),
            CustomDialogs.ChoiceItem("Mode Terang", "Tampilan bersih & cerah"),
            CustomDialogs.ChoiceItem("Mode Gelap", "Tampilan pekat hemat baterai (Obsidian Slate)")
        )
        val cur = modes.indexOf(Settings.theme(this))

        CustomDialogs.showChoiceDialog(
            this,
            title = "Tema Aplikasi",
            subtitle = "Pilih preferensi visual tampilan",
            choices = choices,
            selectedIndex = cur
        ) { which ->
            Settings.setTheme(this, modes[which])
            b.tvTheme.text = choices[which].title
            recreate()
        }
    }

    private fun pickRes() {
        val items = Converter.Res.entries.toTypedArray()
        val choices = items.map {
            when (it) {
                Converter.Res.P720 -> CustomDialogs.ChoiceItem("720p (Hemat)", "Proses lebih cepat & ukuran berkas lebih ringan")
                Converter.Res.P1080 -> CustomDialogs.ChoiceItem("1080p (Full HD)", "Kualitas tajam & seimbang (sangat direkomendasikan)")
                Converter.Res.SOURCE -> CustomDialogs.ChoiceItem("Resolusi Asli", "Mengikuti resolusi maksimal video sumber")
            }
        }
        val cur = items.indexOf(Settings.load(this).res)

        CustomDialogs.showChoiceDialog(
            this,
            title = "Resolusi Bawaan",
            subtitle = "Resolusi yang dipakai saat pertama kali membuka editor",
            choices = choices,
            selectedIndex = cur
        ) { which ->
            b.tvRes.text = items[which].label
            persist(res = items[which])
            notify("Resolusi bawaan: ${items[which].label}")
        }
    }

    private fun pickQuality() {
        val values = intArrayOf(80, 85, 90, 95, 98, 100)
        val choices = values.map {
            CustomDialogs.ChoiceItem("$it%", when {
                it >= 98 -> "Kualitas kompresi tertinggi, detail maksimal"
                it >= 90 -> "Kualitas optimal seimbang (standar industri)"
                else -> "Kompresi lebih hemat ruang penyimpanan"
            })
        }
        val cur = values.indexOf(Settings.load(this).jpegQuality).let { if (it < 0) 3 else it }

        CustomDialogs.showChoiceDialog(
            this,
            title = "Kualitas Cover JPEG",
            subtitle = "Tingkat kompresi foto still Motion Photo",
            choices = choices,
            selectedIndex = cur
        ) { which ->
            val q = values[which]
            persist(quality = q)
            b.tvQuality.text = "$q%"
            notify("Kualitas JPEG diatur ke $q%")
        }
    }

    private fun deleteAllResults() {
        CustomDialogs.showConfirmDialog(
            this,
            title = "Hapus Semua Hasil?",
            message = "Seluruh berkas Motion Photo (MP_) yang tersimpan di DCIM/Camera akan dihapus permanen dari galeri.",
            confirmText = "Hapus Semua"
        ) {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) {
                    ProjectStore.deleteAll(this@SettingsActivity)
                }
                notify(if (n > 0) "$n berkas berhasil dihapus" else "Tidak ada berkas yang dihapus")
                loadStats()
            }
        }
    }

    private fun persist(res: Converter.Res? = null, quality: Int? = null) {
        val cur = Settings.load(this)
        Settings.save(
            this,
            cur.copy(
                res = res ?: cur.res,
                jpegQuality = quality ?: cur.jpegQuality
            )
        )
    }

    private fun notify(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
