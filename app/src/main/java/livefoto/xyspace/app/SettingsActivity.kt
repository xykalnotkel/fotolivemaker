package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import livefoto.xyspace.app.databinding.ActivitySettingsBinding
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
        b.tvRatio.text = o.aspectRatio.label
        b.tvQuality.text = "${o.jpegQuality}%"
        b.tvTheme.text = themeLabel(Settings.theme(this))
        b.swDefaultEnhance.isChecked = o.enhance
        b.swDefaultStabilize.isChecked = o.stabilize

        b.swKeepScreenOn.isChecked = Settings.keepScreenOn(this)
        b.swSplash.isChecked = Settings.showSplash(this)
        b.swHighContrast.isChecked = Settings.isHighContrast(this)
        b.swHaptics.isChecked = Settings.isHapticsEnabled(this)
        b.swReduceMotion.isChecked = Settings.isReduceMotion(this)

        b.rowRes.setOnClickListener {
            Settings.triggerHaptic(it)
            pickRes()
        }
        b.rowRatio.setOnClickListener {
            Settings.triggerHaptic(it)
            pickRatio()
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

        b.swDefaultEnhance.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            persist(enhance = enabled)
            notify(if (enabled) "Bersih aktif secara bawaan" else "Bersih bawaan dimatikan")
        }
        b.swDefaultStabilize.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            persist(stabilize = enabled)
            notify(if (enabled) "Stabilizer aktif secara bawaan" else "Stabilizer bawaan dimatikan")
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


        b.swHwAccel.isChecked = Settings.hwAccel(this)
        b.swNdk.isChecked = Settings.ndkEnabled(this)
        b.tvExportFormat.text = Settings.exportFormat(this)
        b.tvUhdBitrate.text = "${Settings.uhdBitrate(this)} Mbps"
        b.tvLayerBlend.text = Settings.layerBlend(this)
        b.tvCacheLimit.text = "${Settings.cacheLimitMB(this)} MB"

        b.rowExportFormat.setOnClickListener {
            Settings.triggerHaptic(it)
            pickExportFormat()
        }
        b.rowUhdBitrate.setOnClickListener {
            Settings.triggerHaptic(it)
            pickUhdBitrate()
        }
        b.rowLayerBlend.setOnClickListener {
            Settings.triggerHaptic(it)
            pickLayerBlend()
        }
        b.rowCacheLimit.setOnClickListener {
            Settings.triggerHaptic(it)
            pickCacheLimit()
        }
        b.swHwAccel.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setHwAccel(this, enabled)
            notify(if (enabled) "Hardware Accel aktif" else "Hardware Accel mati - pakai CPU")
        }
        b.swNdk.setOnCheckedChangeListener { v, enabled ->
            Settings.triggerHaptic(v)
            Settings.setNdkEnabled(this, enabled)
            notify(if (enabled) "NDK HD aktif (.so)" else "NDK HD mati - pakai Kotlin")
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

    private fun pickRatio() {
        val items = Converter.AspectRatio.entries.toTypedArray()
        val choices = items.map {
            val description = when (it) {
                Converter.AspectRatio.ORIGINAL -> "Ikuti rasio video sumber"
                Converter.AspectRatio.RATIO_9_16 -> "Portrait penuh untuk TikTok, Reels, dan Status"
                Converter.AspectRatio.RATIO_3_4 -> "Portrait klasik"
                Converter.AspectRatio.RATIO_1_1 -> "Persegi untuk feed"
                Converter.AspectRatio.RATIO_4_3 -> "Landscape klasik"
                Converter.AspectRatio.RATIO_16_9 -> "Landscape layar lebar"
            }
            CustomDialogs.ChoiceItem(it.label, description)
        }
        CustomDialogs.showChoiceDialog(
            this,
            title = "Rasio Bawaan",
            subtitle = "Dipakai ketika editor dibuka",
            choices = choices,
            selectedIndex = items.indexOf(Settings.load(this).aspectRatio)
        ) { which ->
            val ratio = items[which]
            b.tvRatio.text = ratio.label
            persist(ratio = ratio)
            notify("Rasio bawaan: ${ratio.label}")
        }
    }

    private fun pickRes() {
        val items = Converter.Res.entries.toTypedArray()
        val choices = items.map {
            when (it) {
                Converter.Res.P720 -> CustomDialogs.ChoiceItem("720p (Hemat)", "Proses lebih cepat & ukuran berkas lebih ringan")
                Converter.Res.P1080 -> CustomDialogs.ChoiceItem("1080p (Full HD)", "Kualitas tajam & seimbang (sangat direkomendasikan)")
                Converter.Res.P1440 -> CustomDialogs.ChoiceItem("2K QHD", "Lebih tajam untuk layar besar")
                Converter.Res.P2160 -> CustomDialogs.ChoiceItem("4K UHD", "Ultra HD - file besar")
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


    private fun pickExportFormat() {
        val fmts = arrayOf("MP4", "GIF", "PNG", "JPG")
        val choices = fmts.map { CustomDialogs.ChoiceItem(it, "Export sebagai $it") }
        val cur = fmts.indexOf(Settings.exportFormat(this))
        CustomDialogs.showChoiceDialog(this, "Format Export", "Pilih format default Video Editor", choices, cur) { which ->
            Settings.setExportFormat(this, fmts[which])
            b.tvExportFormat.text = fmts[which]
            notify("Format export: ${fmts[which]}")
        }
    }

    private fun pickUhdBitrate() {
        val vals = intArrayOf(10, 15, 20, 25, 32)
        val choices = vals.map { CustomDialogs.ChoiceItem("$it Mbps", if (it>=20) "Kualitas UHD tinggi" else "Hemat") }
        val cur = vals.indexOf(Settings.uhdBitrate(this)).let { if (it<0) 2 else it }
        CustomDialogs.showChoiceDialog(this, "Bitrate UHD", "Pilih bitrate untuk 4K/UHD", choices, cur) { which ->
            Settings.setUhdBitrate(this, vals[which])
            b.tvUhdBitrate.text = "${vals[which]} Mbps"
            notify("Bitrate UHD: ${vals[which]} Mbps")
        }
    }

    private fun pickLayerBlend() {
        val blends = arrayOf("Normal", "Multiply", "Screen", "Overlay", "Add", "Subtract")
        val choices = blends.map { CustomDialogs.ChoiceItem(it, "Mode $it") }
        val cur = blends.indexOf(Settings.layerBlend(this))
        CustomDialogs.showChoiceDialog(this, "Layer Blend", "Mode blending antar layer", choices, cur) { which ->
            Settings.setLayerBlend(this, blends[which])
            b.tvLayerBlend.text = blends[which]
            notify("Blend: ${blends[which]}")
        }
    }

    private fun pickCacheLimit() {
        val vals = intArrayOf(200, 500, 1000, 2000)
        val choices = vals.map { CustomDialogs.ChoiceItem("$it MB", "Batas cache transcode") }
        val cur = vals.indexOf(Settings.cacheLimitMB(this)).let { if (it<0) 1 else it }
        CustomDialogs.showChoiceDialog(this, "Batas Cache", "Pilih batas cache", choices, cur) { which ->
            Settings.setCacheLimitMB(this, vals[which])
            b.tvCacheLimit.text = "${vals[which]} MB"
            notify("Cache limit: ${vals[which]} MB")
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

    private fun persist(
        res: Converter.Res? = null,
        ratio: Converter.AspectRatio? = null,
        quality: Int? = null,
        enhance: Boolean? = null,
        stabilize: Boolean? = null
    ) {
        val cur = Settings.load(this)
        Settings.save(
            this,
            cur.copy(
                res = res ?: cur.res,
                aspectRatio = ratio ?: cur.aspectRatio,
                jpegQuality = quality ?: cur.jpegQuality,
                enhance = enhance ?: cur.enhance,
                stabilize = stabilize ?: cur.stabilize
            )
        )
    }

    private fun notify(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
