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
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import livefoto.xyspace.app.databinding.ActivityResultBinding
import kotlinx.coroutines.launch

/**
 * Hasil Video Editor - preview MP4 HD/UHD dan share.
 * Beda dari ResultActivity yang khusus Motion Photo.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
    }

    private lateinit var b: ActivityResultBinding
    private var savedUri: Uri? = null
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        savedUri = intent.getStringExtra(EXTRA_URI)?.toUri()
        val w = intent.getIntExtra(EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 0)

        b.btnClose.setOnClickListener { finish() }
        b.btnAgain.setOnClickListener { finish() }
        b.btnShare.setOnClickListener { shareGeneric() }
        b.btnTikTok.setOnClickListener { shareToPackage(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "TikTok") }
        b.btnInstagram.setOnClickListener { shareToInstagram() }
        b.btnWhatsApp.setOnClickListener { shareToPackage(listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp") }
        b.btnGallery.setOnClickListener { openGallery() }
        b.btnRecheck.visibility = android.view.View.GONE
        b.detail.visibility = android.view.View.GONE
        b.btnDetail.visibility = android.view.View.GONE

        b.statusTitle.text = "Video HD/UHD Siap"
        b.statusSub.text = if (w > 0 && h > 0) "Resolusi ${w}x${h} - tersimpan di DCIM/Camera" else "Tersimpan di DCIM/Camera"
        b.badgeText.text = "HD"
        b.hintHold.text = "Video Editor - Export MP4"
        b.modeStatic.visibility = android.view.View.GONE
        b.modeLive.visibility = android.view.View.GONE
        b.modeLoop.visibility = android.view.View.GONE

        loadVideo()
    }

    private fun loadVideo() {
        val uri = savedUri ?: return
        val p = ExoPlayer.Builder(this).build()
        player = p
        b.playerView.player = p
        b.playerView.useController = true
        p.setMediaItem(MediaItem.fromUri(uri))
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
        b.playerView.visibility = android.view.View.VISIBLE
        b.still.visibility = android.view.View.INVISIBLE
    }

    private fun openGallery() {
        val uri = savedUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, "Tidak ada galeri", Toast.LENGTH_SHORT).show()
        }
    }

    private fun baseShare(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun shareGeneric() {
        val uri = savedUri ?: return
        startActivity(Intent.createChooser(baseShare(uri), "Bagikan Video HD"))
    }

    private fun shareToPackage(pkgs: List<String>, label: String) {
        val uri = savedUri ?: return
        val target = pkgs.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (target == null) {
            shareGeneric()
            return
        }
        runCatching {
            startActivity(baseShare(uri).setPackage(target))
        }.onFailure {
            Toast.makeText(this, "Tidak bisa buka $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareToInstagram() = shareToPackage(listOf("com.instagram.android"), "Instagram")

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
