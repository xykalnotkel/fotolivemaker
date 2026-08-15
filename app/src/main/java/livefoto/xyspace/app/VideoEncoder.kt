package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.Presentation
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

/**
 * Transcoding H.264 + AAC dan compositing motion photo / video.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoEncoder {

    data class Result(val uri: Uri, val plan: VideoMath.Plan, val verifyLog: String, val bytes: Int)
    data class VideoResult(val uri: Uri, val plan: VideoMath.Plan, val width: Int, val height: Int, val bytes: Long)

    private data class EncodedVideoInfo(val width: Int, val height: Int, val durationMs: Long)

    /** Konversi internal: Converter.Options -> VideoMath.Options */
    private fun vOpts(o: Converter.Options) = VideoMath.Options(
        aspectRatio = VideoMath.AspectRatio.valueOf(o.aspectRatio.name),
        res = VideoMath.Res.valueOf(o.res.name),
        enhance = o.enhance, stabilize = o.stabilize, jpegQuality = o.jpegQuality
    )

    private fun inspectEncodedVideo(file: File): EncodedVideoInfo {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val rw = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = if (rot == 90 || rot == 270) rh to rw else rw to rh
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            EncodedVideoInfo(w, h, d)
        } finally { runCatching { r.release() } }
    }

    private fun bmpToJpeg(bmp: android.graphics.Bitmap, quality: Int): ByteArray {
        val s = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), s)
        return s.toByteArray()
    }

    private suspend fun transcodeClip(
        context: Context, uri: Uri, plan: VideoMath.Plan, opts: Converter.Options,
        outW: Int, outH: Int, stab: Stabilizer.Plan?,
        log: (String) -> Unit, progress: (Int) -> Unit
    ): ByteArray {
        val outDir = File(context.cacheDir, "transcode").apply { mkdirs() }
        val outFile = File(outDir, "clip_${System.currentTimeMillis()}.mp4")

        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(plan.startMs)
                .setEndPositionMs(plan.startMs + plan.durationMs).build()
        ).build()

        val effects = mutableListOf<Effect>()
        if (opts.enhance) {
            effects += GlEffect { ctx, useHdr -> EnhanceShader(ctx, useHdr, 0.90f, 0.62f) }
            log("Efek: HD iPhone - bersih noise ultra + sharpen coring kuat")
        }
        if (stab != null && stab.zoom > 1.001f) {
            effects += GlEffect { ctx, useHdr -> StabilizeShader(ctx, useHdr, stab) }
            log("Efek: stabilisasi aktif (${stab.sampleCount} titik koreksi)")
        }
        effects += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        log("Keluaran: ${outW}x${outH} (${opts.aspectRatio.label})")

        val edited = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), effects)).build()
        val bitrate = NativeHD.bitrateFor(outW, outH)

        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var poll: Runnable? = null
            fun stop() { poll?.let { handler.removeCallbacks(it) } }

            val ef = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()

            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(ef)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, res: ExportResult) {
                        stop()
                        try {
                            val info = inspectEncodedVideo(outFile)
                            check(kotlin.math.abs(info.width - outW) <= 2 && kotlin.math.abs(info.height - outH) <= 2) {
                                "Resolusi encoder ${info.width}x${info.height}, target ${outW}x${outH}"
                            }
                            check(info.durationMs > 0L) { "Durasi hasil encode tidak valid" }
                            val bytes = outFile.readBytes()
                            log("Encode terverifikasi: ${info.width}x${info.height}, ${info.durationMs} ms, ${bytes.size / 1024} KB")
                            outFile.delete(); cont.resume(bytes)
                        } catch (e: Exception) { outFile.delete(); cont.resumeWithException(e) }
                    }
                    override fun onError(c: Composition, res: ExportResult, e: ExportException) {
                        stop(); outFile.delete()
                        cont.resumeWithException(IllegalStateException("Encoder gagal: ${e.errorCodeName}. Coba turunkan resolusi atau matikan efek.", e))
                    }
                }).build()

            val holder = ProgressHolder()
            poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) progress(holder.progress)
                    handler.postDelayed(this, 200)
                }
            }
            log("Mulai encode H.264 + AAC…")
            t.start(edited, outFile.absolutePath); handler.post(poll)
            cont.invokeOnCancellation { stop(); runCatching { t.cancel() }; outFile.delete() }
        }
    }

    suspend fun convert(
        context: Context, uri: Uri, opts: Converter.Options,
        log: (String) -> Unit, progress: (Int) -> Unit, planHint: VideoMath.Plan? = null
    ): Result {
        log("Membaca info video…"); progress(3)
        val total = withContext(Dispatchers.IO) { VideoMath.videoDurationMs(context, uri) }
        if (total <= 0) throw IllegalStateException("Durasi video tidak terbaca")
        val (srcW, srcH) = withContext(Dispatchers.IO) { VideoMath.videoSize(context, uri) }
        log("Sumber: ${srcW}x${srcH}, ${total} ms")

        val raw = planHint ?: VideoMath.plan(total)
        val p = VideoMath.sanitize(total, raw.startMs, raw.durationMs, raw.keyframeOffsetMs)
        log("Potong: ${p.startMs} → ${p.startMs + p.durationMs} ms")
        val vo = vOpts(opts)
        val (outW, outH) = VideoMath.calculateDimensions(srcW, srcH, vo)
        log("Target: ${outW}x${outH} (${opts.aspectRatio.label})")

        var stab: Stabilizer.Plan? = null
        if (opts.stabilize) {
            stab = withContext(Dispatchers.Default) {
                Stabilizer.analyze(context, uri, p.startMs, p.durationMs, log) { s -> progress(5 + s * 11 / 100) }
            }
        }

        log("Mengambil frame kunci…"); progress(16)
        var rawBmp = withContext(Dispatchers.IO) {
            BitmapProcessor.extractFrame(context, uri, p.startMs + p.keyframeOffsetMs, opts, outW, outH, applyLook = false)
        } ?: throw IllegalStateException("Gagal mengambil frame dari video")

        val framed = stab?.let { BitmapProcessor.applyStabilization(rawBmp, it, p.keyframeOffsetMs) } ?: rawBmp
        if (framed !== rawBmp) rawBmp.recycle()
        rawBmp = framed

        val bmp = if (opts.enhance) {
            try {
                val looked = withContext(Dispatchers.Default) { BitmapProcessor.enhance(rawBmp, VideoMath.restoreSharpen(opts.stabilize)) }
                if (looked !== rawBmp) rawBmp.recycle(); looked
            } catch (_: OutOfMemoryError) {
                log("Memori filter cover terbatas; cover dipakai tanpa Bersih"); rawBmp
            }
        } else rawBmp

        val jpeg = withContext(Dispatchers.Default) { bmpToJpeg(bmp, opts.jpegQuality) }
        if (!bmp.isRecycled) bmp.recycle()
        log("JPEG: ${jpeg.size / 1024} KB")

        val mp4 = try {
            transcodeClip(context, uri, p, opts, outW, outH, stab, log) { e -> progress(18 + e.coerceIn(0, 100) * 76 / 100) }
        } catch (e: Exception) {
            if (opts.res == Converter.Res.P1080 && (outW > 1280 || outH > 1280)) {
                log("1080p gagal (${e.message}), coba fallback 720p...")
                val vo2 = vOpts(opts.copy(res = Converter.Res.P720))
                val (fw, fh) = VideoMath.calculateDimensions(srcW, srcH, vo2)
                try { transcodeClip(context, uri, p, opts.copy(res = Converter.Res.P720), fw, fh, stab, log) { e -> progress(18 + e.coerceIn(0, 100) * 76 / 100) } }
                catch (e2: Exception) { throw IllegalStateException("Encoder 1080p & 720p gagal: ${e2.message}", e2) }
            } else throw e
        }

        val writerLayout = if (Build.MANUFACTURER.equals("samsung", true) || Build.BRAND.equals("samsung", true))
            MotionPhotoWriter.Layout.SAMSUNG_HYBRID else MotionPhotoWriter.Layout.GOOGLE

        log(if (writerLayout == MotionPhotoWriter.Layout.SAMSUNG_HYBRID) "Mengemas XMP + SEF…" else "Mengemas Google Motion Photo 1.0…")
        progress(96)
        val motionPhoto = withContext(Dispatchers.Default) { MotionPhotoWriter.build(jpeg, mp4, p.keyframeOffsetMs * 1000, layout = writerLayout) }
        log("Total berkas: ${motionPhoto.size / 1024} KB")

        val check = MotionPhotoWriter.verify(motionPhoto)
        log(if (check.ok) "Struktur: konsisten" else "Struktur: BERMASALAH")
        log("Menyimpan ke DCIM/Camera…"); progress(98)
        val savedUri = withContext(Dispatchers.IO) { MediaStoreWriter.saveToGallery(context, motionPhoto) }
        log("Tersimpan.")
        return Result(savedUri, p, check.log, motionPhoto.size)
    }

    suspend fun convertVideo(
        context: Context, uri: Uri, opts: Converter.Options,
        log: (String) -> Unit, progress: (Int) -> Unit, planHint: VideoMath.Plan? = null
    ): VideoResult {
        log("Membaca info video..."); progress(3)
        val total = withContext(Dispatchers.IO) { VideoMath.videoDurationMs(context, uri) }
        if (total <= 0) throw IllegalStateException("Durasi video tidak terbaca")
        val (srcW, srcH) = withContext(Dispatchers.IO) { VideoMath.videoSize(context, uri) }
        log("Sumber: ${srcW}x${srcH}, ${total} ms")

        val raw = planHint ?: VideoMath.plan(total)
        val p = VideoMath.sanitize(total, raw.startMs, raw.durationMs, raw.keyframeOffsetMs)
        log("Potong: ${p.startMs} -> ${p.startMs + p.durationMs} ms")
        val vo = vOpts(opts)
        val (outW, outH) = VideoMath.calculateDimensions(srcW, srcH, vo)
        log("Target: ${outW}x${outH} (${opts.aspectRatio.label} ${opts.res.label})")

        var stab: Stabilizer.Plan? = null
        if (opts.stabilize) {
            stab = withContext(Dispatchers.Default) {
                Stabilizer.analyze(context, uri, p.startMs, p.durationMs, log) { s -> progress(5 + s * 11 / 100) }
            }
        }

        log("Mulai transcode video HD/UHD...")
        val mp4Bytes = try {
            transcodeClip(context, uri, p, opts, outW, outH, stab, log) { e -> progress(18 + e.coerceIn(0, 100) * 76 / 100) }
        } catch (e: Exception) {
            if (opts.res in listOf(Converter.Res.P2160, Converter.Res.P1440, Converter.Res.P1080)) {
                log("Resolusi tinggi gagal (${e.message}), fallback ke 720p...")
                val vo2 = vOpts(opts.copy(res = Converter.Res.P720))
                val (fw, fh) = VideoMath.calculateDimensions(srcW, srcH, vo2)
                transcodeClip(context, uri, p, opts.copy(res = Converter.Res.P720), fw, fh, stab, log) { e -> progress(18 + e.coerceIn(0, 100) * 76 / 100) }
            } else throw e
        }

        val tmp = withContext(Dispatchers.IO) {
            val d = File(context.cacheDir, "transcode").apply { mkdirs() }
            val f = File(d, "export_${System.currentTimeMillis()}.mp4"); f.writeBytes(mp4Bytes); f
        }
        val info = inspectEncodedVideo(tmp)
        log("Encode terverifikasi: ${info.width}x${info.height}, ${info.durationMs} ms, ${tmp.length()/1024} KB")
        val savedUri = withContext(Dispatchers.IO) { MediaStoreWriter.saveVideoToGallery(context, tmp) }
        tmp.delete(); log("Tersimpan di DCIM/Camera sebagai video")
        return VideoResult(savedUri, p, info.width, info.height, mp4Bytes.size.toLong())
    }
}