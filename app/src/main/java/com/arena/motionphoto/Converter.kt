package com.arena.motionphoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Converter {

    /** Durasi klip mengikuti Live Photo Apple: 3 detik. */
    const val TARGET_CLIP_MS = 3000L

    data class Options(
        val square: Boolean,
        val targetHeight: Int = 1080,
        val jpegQuality: Int = 92
    )

    /** Rencana potong yang dihitung otomatis dari durasi video. */
    data class Plan(
        val totalMs: Long,
        val startMs: Long,
        val durationMs: Long,
        val keyframeOffsetMs: Long
    )

    /**
     * Tentukan potongan secara otomatis:
     * - video >= 3 dtk  -> ambil 3 dtk di bagian tengah
     * - video <  3 dtk  -> pakai seluruh video apa adanya
     * Frame kunci selalu di tengah klip.
     */
    fun plan(totalMs: Long): Plan {
        val dur = if (totalMs >= TARGET_CLIP_MS) TARGET_CLIP_MS else totalMs
        val start = if (totalMs > dur) (totalMs - dur) / 2 else 0L
        return Plan(totalMs, start, dur, dur / 2)
    }

    fun videoDurationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    fun extractFrame(context: Context, uri: Uri, atMs: Long, opts: Options): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime()
                ?: return null
            processBitmap(bmp, opts)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun processBitmap(src: Bitmap, opts: Options): Bitmap {
        var bmp = src
        if (opts.square) {
            val s = minOf(bmp.width, bmp.height)
            val x = (bmp.width - s) / 2
            val y = (bmp.height - s) / 2
            bmp = Bitmap.createBitmap(bmp, x, y, s, s)
        }
        val targetH = opts.targetHeight
        if (bmp.height != targetH) {
            val ratio = targetH.toFloat() / bmp.height
            var w = (bmp.width * ratio).toInt()
            var h = targetH
            w -= w % 2
            h -= h % 2
            if (w > 0 && h > 0) bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        return bmp
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Trim + transcode + CROP dengan Media3 Transformer.
     *
     * Crop 1:1 dikerjakan di sini lewat efek Presentation, bukan cuma di
     * bitmap. Sebelumnya opsi kotak hanya mengubah fotonya sementara
     * videonya tetap utuh, sehingga rasio foto dan video jadi tidak sama.
     */
    private suspend fun transcodeClip(
        context: Context,
        uri: Uri,
        plan: Plan,
        opts: Options,
        log: (String) -> Unit,
        progress: (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.Main) {
        val outFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(plan.startMs)
                    .setEndPositionMs(plan.startMs + plan.durationMs)
                    .build()
            )
            .build()

        val effects = mutableListOf<Effect>()
        if (opts.square) {
            effects += Presentation.createForAspectRatio(
                1f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
            log("Efek: crop 1:1 diterapkan ke video")
        }
        effects += Presentation.createForHeight(opts.targetHeight)
        log("Efek: skala ke ${opts.targetHeight}p")

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        suspendCancellableCoroutine<ByteArray> { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        try {
                            val bytes = outFile.readBytes()
                            log("Encode selesai: ${bytes.size / 1024} KB")
                            outFile.delete()
                            cont.resume(bytes)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        outFile.delete()
                        cont.resumeWithException(
                            IllegalStateException(
                                "Encoder gagal: ${exception.errorCodeName}. " +
                                    "Coba aktifkan 720p atau pilih video lain.",
                                exception
                            )
                        )
                    }
                })
                .build()

            // Laporan kemajuan sungguhan dari Transformer
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        progress(holder.progress)
                    }
                    handler.postDelayed(this, 200)
                }
            }

            log("Mulai encode H.264 + AAC…")
            transformer.start(edited, outFile.absolutePath)
            handler.post(poll)

            cont.invokeOnCancellation {
                handler.removeCallbacks(poll)
                runCatching { transformer.cancel() }
                outFile.delete()
            }
            // hentikan polling begitu selesai, apa pun hasilnya
            cont.invokeOnCompletion { handler.removeCallbacks(poll) }
        }
    }

    data class Result(val uri: Uri, val plan: Plan, val verifyLog: String, val bytes: Int)

    suspend fun convert(
        context: Context,
        uri: Uri,
        opts: Options,
        log: (String) -> Unit,
        progress: (Int) -> Unit
    ): Result {
        log("Membaca info video…")
        val total = withContext(Dispatchers.IO) { videoDurationMs(context, uri) }
        if (total <= 0) throw IllegalStateException("Durasi video tidak terbaca")
        val p = plan(total)
        log("Durasi video: ${total} ms")
        log("Potong otomatis: ${p.startMs} → ${p.startMs + p.durationMs} ms")
        log("Frame kunci: +${p.keyframeOffsetMs} ms")

        log("Mengambil frame kunci…")
        val bmp = withContext(Dispatchers.IO) {
            extractFrame(context, uri, p.startMs + p.keyframeOffsetMs, opts)
        } ?: throw IllegalStateException("Gagal mengambil frame dari video")
        log("Foto: ${bmp.width}x${bmp.height}")

        val jpeg = withContext(Dispatchers.Default) { bmp.toJpeg(opts.jpegQuality) }
        log("JPEG: ${jpeg.size / 1024} KB")

        val mp4 = transcodeClip(context, uri, p, opts, log, progress)

        log("Menyisipkan XMP GCamera…")
        val motionPhoto = withContext(Dispatchers.Default) {
            MotionPhotoWriter.build(jpeg, mp4, p.keyframeOffsetMs * 1000)
        }
        log("Total berkas: ${motionPhoto.size / 1024} KB")

        val check = MotionPhotoWriter.verify(motionPhoto)
        log(if (check.ok) "Struktur: VALID" else "Struktur: BERMASALAH")

        log("Menyimpan ke DCIM/Camera…")
        val savedUri = withContext(Dispatchers.IO) { saveToGallery(context, motionPhoto) }
        log("Tersimpan.")

        return Result(savedUri, p, check.log, motionPhoto.size)
    }

    private fun saveToGallery(context: Context, data: ByteArray): Uri {
        val name = "MP_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/Camera"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Tidak bisa membuat berkas di galeri")

        resolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw IllegalStateException("Tidak bisa menulis berkas")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        // Paksa MediaStore memindai berkasnya sekarang juga.
        // Tanpa ini, di sebagian perangkat berkas baru muncul di galeri
        // setelah beberapa saat atau setelah HP di-restart.
        runCatching {
            val path = resolvePath(context, uri)
            if (path != null) {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(path), arrayOf("image/jpeg"), null
                )
            }
        }
        return uri
    }

    /** Cari path fisik berkas supaya bisa dipindai MediaScanner. */
    private fun resolvePath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(MediaStore.Images.Media.DATA)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    }.getOrNull()
}
