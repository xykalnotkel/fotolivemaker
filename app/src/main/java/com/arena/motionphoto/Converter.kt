package com.arena.motionphoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Converter {

    data class Options(
        val startMs: Long,          // mulai potong
        val durationMs: Long,       // panjang klip (disarankan 3000)
        val keyframeOffsetMs: Long, // posisi frame kunci di dalam klip
        val square: Boolean,
        val targetHeight: Int = 1080,
        val jpegQuality: Int = 92
    )

    /** Baca durasi total video (ms). */
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

    /** Ambil satu frame sebagai bitmap pada posisi tertentu. */
    fun extractFrame(context: Context, uri: Uri, atMs: Long, opts: Options): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            processBitmap(bmp, opts)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Crop 1:1 kalau diminta, lalu scale ke tinggi target, dimensi dibulatkan genap. */
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
            w -= w % 2   // dimensi ganjil bikin encoder & parser rewel
            h -= h % 2
            if (w > 0 && h > 0) {
                bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
            }
        }
        return bmp
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Trim + transcode video pakai Media3 Transformer.
     * H.264 + AAC, hardware accelerated, jauh lebih ringan daripada bundling ffmpeg.
     */
    private suspend fun transcodeClip(
        context: Context,
        uri: Uri,
        opts: Options,
        onProgress: (String) -> Unit
    ): ByteArray = withContext(Dispatchers.Main) {
        val outFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(opts.startMs)
                    .setEndPositionMs(opts.startMs + opts.durationMs)
                    .build()
            )
            .build()

        val edited = EditedMediaItem.Builder(mediaItem).build()

        suspendCancellableCoroutine<ByteArray> { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        try {
                            val bytes = outFile.readBytes()
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
                        cont.resumeWithException(exception)
                    }
                })
                .build()

            onProgress("Meng-encode klip video…")
            transformer.start(edited, outFile.absolutePath)

            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
                outFile.delete()
            }
        }
    }

    /**
     * Proses penuh: video -> Motion Photo, langsung tersimpan ke DCIM/Camera.
     * Disimpan ke DCIM/Camera (bukan Download) supaya ter-index MediaStore
     * dan muncul di picker galeri TikTok.
     */
    suspend fun convert(
        context: Context,
        uri: Uri,
        opts: Options,
        onProgress: (String) -> Unit
    ): Pair<Uri, String> {
        onProgress("Mengambil frame kunci…")
        val bmp = withContext(Dispatchers.IO) {
            extractFrame(context, uri, opts.startMs + opts.keyframeOffsetMs, opts)
        } ?: throw IllegalStateException("Gagal mengambil frame dari video")

        val jpeg = withContext(Dispatchers.Default) { bmp.toJpeg(opts.jpegQuality) }

        val mp4 = transcodeClip(context, uri, opts, onProgress)

        onProgress("Menggabungkan foto + video…")
        val motionPhoto = withContext(Dispatchers.Default) {
            MotionPhotoWriter.build(jpeg, mp4, opts.keyframeOffsetMs * 1000)
        }

        val check = MotionPhotoWriter.verify(motionPhoto)

        onProgress("Menyimpan ke galeri…")
        val savedUri = withContext(Dispatchers.IO) {
            saveToGallery(context, motionPhoto)
        }

        return savedUri to check.log
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
            ?: throw IllegalStateException("Tidak bisa membuat file di galeri")

        resolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw IllegalStateException("Tidak bisa menulis file")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
