package com.arena.motionphoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arena.motionphoto.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { openEditor(it, persist = true) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.f1.txt.text = "Pilih bagian video yang kamu mau"
        b.f2.txt.text = "Tes langsung dengan menahan layar"
        b.f3.txt.text = "Berjalan offline, tanpa iklan"

        // Tampilkan versi supaya bisa dipastikan APK mana yang terpasang
        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}"

        b.btnStart.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        b.btnLegal.setOnClickListener { showLegal() }

        // kalau dibuka lewat "Bagikan ke" dari galeri
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("handled", false)) return
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            intent.putExtra("handled", true)
            openEditor(uri, persist = false)
        }
    }

    private fun openEditor(uri: Uri, persist: Boolean) {
        if (persist) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun showLegal() {
        AlertDialog.Builder(this)
            .setTitle("Lisensi & Privasi")
            .setMessage(getString(R.string.legal_notice))
            .setPositiveButton("Tutup", null)
            .show()
    }
}
