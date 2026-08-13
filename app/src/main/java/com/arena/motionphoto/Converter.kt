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
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.GlEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
object Converter {

    /** Durasi klip mengikuti Live Photo Apple: 3 detik. */
    const val TARGET_CLIP_MS = 3000L

    /** Pilihan resolusi keluaran. SOURCE = ikut resolusi asli video. */
    enum class Res(val label: String, val height: Int) {
        P720("720p", 720),
        P1080("1080p", 1080),
        SOURCE("Asli", 0)
    }

    data class Options(
        val square: Boolean = false,
        val res: Res = Res.P1080,
        val enhance: Boolean = false,
        val stabilize: Boolean = false,
        val jpegQuality: Int = 95
    ) {
        /** Tinggi efektif; untuk SOURCE dipakai tinggi video aslinya. */
        fun heightFor(sourceHeight: Int): Int =
            if (res == Res.SOURCE) sourceHeight.coerceAtLeast(2) else res.height
    }

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

    /** Ukuran video sumber (lebar, tinggi) setelah memperhitungkan rotasi. */
    fun videoSize(context: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) {
            0 to 0
        } finally {
            runCatching { r.release() }
        }
    }

    fun extractFrame(
        context: Context, uri: Uri, atMs: Long, opts: Options, outHeight: Int
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime()
                ?: return null
            processBitmap(bmp, opts, outHeight)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun processBitmap(src: Bitmap, opts: Options, targetH: Int): Bitmap {
        var bmp = src
        if (opts.square) {
            val s = minOf(bmp.width, bmp.height)
            val x = (bmp.width - s) / 2
            val y = (bmp.height - s) / 2
            bmp = Bitmap.createBitmap(bmp, x, y, s, s)
        }
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
        outW: Int,
        outH: Int,
        stab: Stabilizer.Plan?,
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

        // 1. Penghalus/penajam lebih dulu, saat resolusi masih penuh.
        if (opts.enhance) {
            effects += GlEffect { ctx, useHdr ->
                EnhanceShader(ctx, useHdr, denoise = 0.75f, sharpen = 0.55f)
            }
            log("Efek: bersihkan noise + pertajam")
        }

        // 2. Stabilisasi: tiap frame digeser berlawanan arah guncangannya.
        if (stab != null && stab.zoom > 1.001f) {
            effects += GlEffect { ctx, useHdr -> StabilizeShader(ctx, useHdr, stab) }
            log("Efek: stabilisasi aktif (${stab.sampleCount} titik koreksi)")
        }

        // 3. Presentation HARUS satu saja dan paling akhir.
        //    Sebelumnya dipasang dua kali (aspect ratio lalu height), dan
        //    yang kedua menimpa yang pertama -> crop 1:1 tidak pernah jalan.
        effects += Presentation.createForWidthAndHeight(
            outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        log("Keluaran: ${outW}x${outH}")

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        // Bitrate proporsional dengan jumlah piksel supaya tidak pecah.
        val bitrate = (outW.toLong() * outH * 12).toInt().coerceIn(3_000_000, 40_000_000)
        log("Bitrate: ${bitrate / 1_000_000} Mbps")

        suspendCancellableCoroutine<ByteArray> { cont ->
            val handler = Handler(Looper.getMainLooper())
            var poll: Runnable? = null
            fun stopPolling() { poll?.let { handler.removeCallbacks(it) } }

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(bitrate)
                        .build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        stopPolling()
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
                        stopPolling()
                        outFile.delete()
                        cont.resumeWithException(
                            IllegalStateException(
                                "Encoder gagal: ${exception.errorCodeName}. " +
                                    "Coba turunkan resolusi atau matikan efek.",
                                exception
                            )
                        )
                    }
                })
                .build()

            val holder = ProgressHolder()
            poll = object : Runnable {
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
            handler.post(poll!!)

            cont.invokeOnCancellation {
                stopPolling()
                runCatching { transformer.cancel() }
                outFile.delete()
            }
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

        val (srcW, srcH) = withContext(Dispatchers.IO) { videoSize(context, uri) }
        log("Sumber: ${srcW}x${srcH}, ${total} ms")

        val p = plan(total)
        log("Potong otomatis: ${p.startMs} → ${p.startMs + p.durationMs} ms")

        // Tentukan ukuran keluaran
        val baseH = opts.heightFor(if (srcH > 0) srcH else 1080)
        val outH: Int
        val outW: Int
        if (opts.square) {
            val side = evenUp(baseH)
            outW = side; outH = side
        } else {
            val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 9f / 16f
            outH = evenUp(baseH)
            outW = evenUp(outH * ratio)
        }
        log("Target: ${outW}x${outH}${if (opts.square) " (kotak 1:1)" else ""}")

        // Stabilisasi: analisis gerakan untuk menyusun tabel koreksi per-frame
        var stab: Stabilizer.Plan? = null
        if (opts.stabilize) {
            stab = withContext(Dispatchers.Default) {
                Stabilizer.analyze(context, uri, p.startMs, p.durationMs, log)
            }
        }

        log("Mengambil frame kunci…")
        val bmp = withContext(Dispatchers.IO) {
            extractFrame(context, uri, p.startMs + p.keyframeOffsetMs, opts, outH)
        } ?: throw IllegalStateException("Gagal mengambil frame dari video")
        log("Foto: ${bmp.width}x${bmp.height}")

        val jpeg = withContext(Dispatchers.Default) { bmp.toJpeg(opts.jpegQuality) }
        log("JPEG: ${jpeg.size / 1024} KB")

        val mp4 = transcodeClip(context, uri, p, opts, outW, outH, stab, log, progress)

        log("Menyisipkan XMP GCamera + trailer Samsung…")
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

    /** Bulatkan ke atas ke bilangan genap; encoder menolak dimensi ganjil. */
    private fun evenUp(v: Number): Int {
        var x = Math.round(v.toFloat())
        if (x < 2) x = 2
        if (x % 2 != 0) x++
        return x
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
