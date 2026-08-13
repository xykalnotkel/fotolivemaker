package com.arena.motionphoto

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Verifikasi sungguhan terhadap file yang SUDAH tersimpan di galeri.
 *
 * Bedanya dengan MotionPhotoWriter.verify(): yang itu cuma mengecek byte
 * buatan kita sendiri. Yang ini bertanya ke ANDROID — apakah sistem benar-benar
 * mengenali file ini sebagai motion photo, dan apakah videonya betul-betul
 * bisa didecode oleh media framework bawaan.
 *
 * Inilah dasar penentuan badge LIVE di layar hasil.
 */
object MotionPhotoVerifier {

    /**
     * Kolom internal MediaStore (@hide di SDK, tapi tetap bisa di-query
     * lewat nama kolomnya). Android 12+ mengisi kolom ini sendiri saat
     * memindai file. Nilai 3 = motion photo.
     *
     * Ini sinyal paling kuat yang bisa kita dapat: bukan klaim kita,
     * melainkan hasil pemindaian sistem.
     */
    private const val COL_SPECIAL_FORMAT = "_special_format"
    private const val SPECIAL_FORMAT_MOTION_PHOTO = 3

    enum class Level { CONFIRMED, LIKELY, FAILED }

    data class Report(
        val level: Level,
        val systemFlag: Boolean?,     // null = tidak tersedia di versi Android ini
        val videoPlayable: Boolean,
        val xmpOk: Boolean,
        val lengthOk: Boolean,
        val videoDurationMs: Long,
        val videoSize: String,
        val fileSizeKb: Long,
        val detail: String
    ) {
        val headline: String
            get() = when (level) {
                Level.CONFIRMED -> "Dikenali sistem sebagai Live Photo"
                Level.LIKELY -> "Format benar, siap diuji"
                Level.FAILED -> "Format bermasalah"
            }
    }

    fun verify(context: Context, uri: Uri): Report {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }.getOrNull() ?: return failed("File tidak bisa dibaca ulang")

        val sb = StringBuilder()

        // --- 1. cek struktur byte ---
        val struct = MotionPhotoWriter.verify(bytes)
        val text = String(bytes, 0, minOf(bytes.size, 65536), Charsets.ISO_8859_1)
        val xmpOk = text.contains("GCamera:MotionPhoto=\"1\"")
        val lengthOk = struct.ok

        sb.append("STRUKTUR FILE\n")
        sb.append(struct.log).append("\n\n")

        // --- 2. tanya ke MediaStore: sistem menganggapnya apa? ---
        val sysFlag = querySystemFlag(context, uri)
        sb.append("PEMINDAIAN SISTEM ANDROID\n")
        when (sysFlag) {
            true -> sb.append("✓ MediaStore menandai: MOTION PHOTO\n")
            false -> sb.append("• MediaStore belum menandai sebagai motion photo\n")
                .append("  (kadang perlu beberapa detik, atau versi Android\n")
                .append("   ini memang tidak memakai penanda tsb.)\n")
            null -> sb.append("• Penanda sistem tidak tersedia di Android ${Build.VERSION.SDK_INT}\n")
        }
        sb.append("\n")

        // --- 3. buktikan videonya benar-benar bisa diputar framework Android ---
        var playable = false
        var durMs = 0L
        var dim = "-"
        val mp4 = MotionPhotoWriter.extractMp4(bytes)
        if (mp4 != null) {
            val tmp = File(context.cacheDir, "verify_${System.currentTimeMillis()}.mp4")
            runCatching {
                tmp.writeBytes(mp4)
                val r = MediaMetadataRetriever()
                r.setDataSource(tmp.absolutePath)
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val hasFrame = r.getFrameAtTime() != null
                r.release()
                dim = if (w != null && h != null) "${w}x${h}" else "-"
                playable = durMs > 0 && hasFrame
            }
            tmp.delete()
        }

        sb.append("VIDEO DI DALAM FILE\n")
        if (playable) {
            sb.append("✓ Bisa didecode media framework Android\n")
            sb.append("  durasi  : ${durMs} ms\n")
            sb.append("  dimensi : $dim\n")
        } else {
            sb.append("✗ Video tidak bisa didecode\n")
        }

        val level = decideLevel(lengthOk, xmpOk, playable, sysFlag)

        return Report(
            level = level,
            systemFlag = sysFlag,
            videoPlayable = playable,
            xmpOk = xmpOk,
            lengthOk = lengthOk,
            videoDurationMs = durMs,
            videoSize = dim,
            fileSizeKb = bytes.size / 1024L,
            detail = sb.toString().trimEnd()
        )
    }

    /**
     * Baca kolom _special_format dari MediaStore.
     * @return true kalau sistem menandai motion photo, false kalau tidak,
     *         null kalau kolomnya memang tidak ada di versi Android ini.
     */
    private fun querySystemFlag(context: Context, uri: Uri): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(COL_SPECIAL_FORMAT), null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val i = c.getColumnIndex(COL_SPECIAL_FORMAT)
                if (i < 0 || c.isNull(i)) null
                else c.getInt(i) == SPECIAL_FORMAT_MOTION_PHOTO
            }
        }.getOrNull()
    }

    fun decideLevel(
        lengthOk: Boolean,
        xmpOk: Boolean,
        playable: Boolean,
        systemFlag: Boolean?
    ): Level = when {
        !lengthOk || !xmpOk || !playable -> Level.FAILED
        systemFlag == true -> Level.CONFIRMED
        else -> Level.LIKELY
    }

    private fun failed(msg: String) = Report(
        Level.FAILED, null, false, false, false, 0L, "-", 0L, msg
    )
}
