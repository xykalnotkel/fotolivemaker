package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arena.motionphoto.databinding.ActivitySettingsBinding

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

        b.rowRes.setOnClickListener { pickRes() }

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
    }

    private fun pickRes() {
        val items = Converter.Res.values()
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
                d.dismiss()
            }
            .show()
    }

    private fun persist(res: Converter.Res? = null) {
        val cur = Settings.load(this)
        Settings.save(
            this,
            cur.copy(
                res = res ?: cur.res,
                square = b.swSquare.isChecked,
                enhance = b.swEnhance.isChecked,
                stabilize = b.swStab.isChecked
            )
        )
    }

    override fun onPause() {
        super.onPause()
        persist()
    }
}
