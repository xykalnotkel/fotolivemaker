package livefoto.xystudio.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

    /** Rasio aspek keluaran video & foto */
    enum class AspectRatio(val label: String, val ratioW: Int, val ratioH: Int) {
        ORIGINAL("Asli", 0, 0),
        RATIO_9_16("9:16", 9, 16),
        RATIO_3_4("3:4", 3, 4),
        RATIO_1_1("1:1", 1, 1),
        RATIO_4_3("4:3", 4, 3),
        RATIO_16_9("16:9", 16, 9);

        fun isOriginal(): Boolean = this == ORIGINAL
    }

    /** Pilihan resolusi keluaran. SOURCE = ikut resolusi asli video. */
    enum class Res(val label: String, val height: Int) {
        P720("720p", 720),
        P1080("1080p", 1080),
        SOURCE("Asli", 0)
    }

    data class Options(
        val aspectRatio: AspectRatio = AspectRatio.ORIGINAL,
        val res: Res = Res.P1080,
        val enhance: Boolean = false,
        val stabilize: Boolean = false,
        val jpegQuality: Int = 96
    ) {
        val square: Boolean get() = aspectRatio == AspectRatio.RATIO_1_1

        constructor(
            square: Boolean,
            res: Res = Res.P1080,
            enhance: Boolean = false,
            stabilize: Boolean = false,
            jpegQuality: Int = 96
        ) : this(
            aspectRatio = if (square) AspectRatio.RATIO_1_1 else AspectRatio.ORIGINAL,
            res = res,
            enhance = enhance,
            stabilize = stabilize,
            jpegQuality = jpegQuality
        )

        /** Tinggi efektif; untuk SOURCE dipakai tinggi video aslinya. */
        fun heightFor(sourceHeight: Int): Int =
            if (res == Res.SOURCE) sourceHeight.coerceAtLeast(2) else res.height
    }

    /** Rencana potong: jendela klip + posisi frame kunci di dalam jendela. */
    data class Plan(
        val totalMs: Long,
        val startMs: Long,
        val durationMs: Long,
        val keyframeOffsetMs: Long
    )

    /**
     * Potongan otomatis (kalau user tidak menggeser slider):
     * - video >= 3 dtk  -> ambil 3 dtk di bagian tengah
     * - video <  3 dtk  -> pakai seluruh video apa adanya
     * Frame kunci selalu di tengah klip.
     */
    fun plan(totalMs: Long): Plan {
        val safe = totalMs.coerceAtLeast(1L)
        val dur = if (safe >= TARGET_CLIP_MS) TARGET_CLIP_MS else safe
        val start = if (safe > dur) (safe - dur) / 2 else 0L
        return Plan(safe, start, dur, dur / 2)
    }

    /** Rapikan usulan potongan supaya selalu di dalam video dan <= 3 dtk. */
    fun sanitize(totalMs: Long, startMs: Long, durationMs: Long, keyframeOffsetMs: Long): Plan {
        val safe = totalMs.coerceAtLeast(1L)
        val dur = durationMs.coerceIn(1L, minOf(TARGET_CLIP_MS, safe))
        val start = startMs.coerceIn(0L, (safe - dur).coerceAtLeast(0L))
        val key = keyframeOffsetMs.coerceIn(0L, dur)
        return Plan(safe, start, dur, key)
    }

    fun sliderRound(v: Float): Float = Math.round(v * 10f) / 10f

    /** Range Material Slider yang selalu sah: from < to, value di dalamnya. */
    fun sliderRange(from: Float, to: Float, value: Float): Triple<Float, Float, Float> {
        val f = sliderRound(from)
        val t = sliderRound(maxOf(to, f + 0.1f))
        val v = sliderRound(value).coerceIn(f, t)
        return Triple(f, t, v)
    }

    data class ClipSliders(
        val start: Triple<Float, Float, Float>,
        val key: Triple<Float, Float, Float>,
        val clipSec: Float,
        val showStart: Boolean
    )

    /**
     * Dua slider: posisi jendela 3 dtk, dan frame kunci di dalam jendela.
     * Durasi dikunci 3,0 dtk (atau seluruh video kalau lebih pendek).
     */
    fun clipSliders(
        totalMs: Long,
        startSec: Float? = null,
        keySec: Float? = null
    ): ClipSliders {
        val totalSec = sliderRound((totalMs.coerceAtLeast(1L)) / 1000f)
        val clip = sliderRound(minOf(TARGET_CLIP_MS / 1000f, totalSec.coerceAtLeast(0.1f)))
        val maxStart = maxOf(0f, sliderRound(totalSec - clip))
        val showStart = maxStart >= 0.1f
        val start = if (showStart) {
            sliderRange(0f, maxStart, startSec ?: (maxStart / 2f))
        } else {
            Triple(0f, 0.1f, 0f)
        }
        val key = sliderRange(0f, clip, keySec ?: (clip / 2f))
        return ClipSliders(start, key, clip, showStart)
    }

    /** Bulatkan ke atas ke bilangan genap; encoder menolak dimensi ganjil. */
    fun evenUp(v: Number): Int {
        var x = Math.round(v.toFloat())
        if (x < 2) x = 2
        if (x % 2 != 0) x++
        return x
    }

    /** Hitung dimensi keluaran berdasarkan rasio aspek dan resolusi pilihan */
    fun calculateDimensions(srcW: Int, srcH: Int, opts: Options): Pair<Int, Int> {
        val baseH = opts.heightFor(if (srcH > 0) srcH else 1080)
        val outH = evenUp(baseH)
        val outW = if (opts.aspectRatio.isOriginal()) {
            val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 9f / 16f
            evenUp(outH * ratio)
        } else {
            val targetRatio = opts.aspectRatio.ratioW.toFloat() / opts.aspectRatio.ratioH
            evenUp(outH * targetRatio)
        }
        return outW to outH
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
        context: Context, uri: Uri, atMs: Long, opts: Options,
        targetW: Int, targetH: Int, applyLook: Boolean = true
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            var bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime()
                ?: return null

            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot != 0) {
                val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }

            processBitmap(bmp, opts, targetW, targetH, applyLook)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun processBitmap(
        src: Bitmap, opts: Options, targetW: Int, targetH: Int, applyLook: Boolean
    ): Bitmap {
        var bmp = src
        if (!opts.aspectRatio.isOriginal()) {
            val targetRatio = opts.aspectRatio.ratioW.toFloat() / opts.aspectRatio.ratioH
            val srcRatio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val cropW: Int
            val cropH: Int
            if (srcRatio > targetRatio) {
                cropH = bmp.height
                cropW = (cropH * targetRatio).toInt().coerceAtMost(bmp.width)
            } else {
                cropW = bmp.width
                cropH = (cropW / targetRatio).toInt().coerceAtMost(bmp.height)
            }
            val x = ((bmp.width - cropW) / 2).coerceAtLeast(0)
            val y = ((bmp.height - cropH) / 2).coerceAtLeast(0)
            if (cropW > 0 && cropH > 0 && (cropW != bmp.width || cropH != bmp.height)) {
                val cropped = Bitmap.createBitmap(bmp, x, y, cropW, cropH)
                if (bmp !== src) bmp.recycle()
                bmp = cropped
            }
        }

        if (bmp.width != targetW || bmp.height != targetH) {
            if (targetW > 0 && targetH > 0) {
                val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                if (scaled !== bmp) {
                    if (bmp !== src) bmp.recycle()
                    bmp = scaled
                }
            }
        }

        if (applyLook && opts.enhance) {
            val enhanced = enhanceBitmap(bmp, restoreSharpen(opts.stabilize))
            if (enhanced !== bmp) {
                if (bmp !== src) bmp.recycle()
                bmp = enhanced
            }
        }
        if (bmp !== src) src.recycle()
        return bmp
    }

    fun restoreSharpen(stabilize: Boolean): Float = if (stabilize) 0.38f else 0.28f

    /**
     * Filter Bilateral Edge-Preserving dengan Coring Threshold:
     * Menghilangkan noise bintik pasir pada area halus (kulit, langit, gradasi)
     * dan hanya mempertajam tepi kontras nyata tanpa artefak.
     */
    private fun enhanceBitmap(src: Bitmap, sharpen: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return src
        val work = if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false)
        val inn = IntArray(w * h)
        val out = IntArray(w * h)
        work.getPixels(inn, 0, w, 0, 0, w, h)

        val sigmaCSq = 26f * 26f
        val denoiseAmount = 0.65f

        for (y in 0 until h) {
            val y0 = (y - 2).coerceAtLeast(0)
            val y1 = (y + 2).coerceAtMost(h - 1)
            val row = y * w
            for (x in 0 until w) {
                val x0 = (x - 2).coerceAtLeast(0)
                val x1 = (x + 2).coerceAtMost(w - 1)
                val centerP = inn[row + x]
                val cr = (centerP ushr 16) and 0xFF
                val cg = (centerP ushr 8) and 0xFF
                val cb = centerP and 0xFF
                val a = centerP ushr 24

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f

                for (yy in y0..y1) {
                    val sRow = yy * w
                    val dy = yy - y
                    for (xx in x0..x1) {
                        val p = inn[sRow + xx]
                        val pr = (p ushr 16) and 0xFF
                        val pg = (p ushr 8) and 0xFF
                        val pb = p and 0xFF
                        val dx = xx - x

                        val spatialDist = dx * dx + dy * dy
                        val spatialW = when (spatialDist) {
                            0 -> 4.0f
                            1 -> 2.5f
                            2 -> 1.8f
                            else -> 1.0f
                        }

                        val colorDist = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)) / 3f
                        val colorW = Math.exp((-colorDist / (2f * sigmaCSq)).toDouble()).toFloat()
                        val wTotal = spatialW * colorW

                        sumR += pr * wTotal
                        sumG += pg * wTotal
                        sumB += pb * wTotal
                        sumW += wTotal
                    }
                }

                val invW = if (sumW > 0.0001f) 1f / sumW else 1f
                val smoothR = sumR * invW
                val smoothG = sumG * invW
                val smoothB = sumB * invW

                var baseR = cr + (smoothR - cr) * denoiseAmount
                var baseG = cg + (smoothG - cg) * denoiseAmount
                var baseB = cb + (smoothB - cb) * denoiseAmount

                if (sharpen > 0.01f) {
                    val top = inn[((y - 1).coerceAtLeast(0)) * w + x]
                    val btm = inn[((y + 1).coerceAtMost(h - 1)) * w + x]
                    val lft = inn[row + (x - 1).coerceAtLeast(0)]
                    val rgt = inn[row + (x + 1).coerceAtMost(w - 1)]

                    val blurR = ((top ushr 16 and 0xFF) + (btm ushr 16 and 0xFF) + (lft ushr 16 and 0xFF) + (rgt ushr 16 and 0xFF)) * 0.25f
                    val blurG = ((top ushr 8 and 0xFF) + (btm ushr 8 and 0xFF) + (lft ushr 8 and 0xFF) + (rgt ushr 8 and 0xFF)) * 0.25f
                    val blurB = ((top and 0xFF) + (btm and 0xFF) + (lft and 0xFF) + (rgt and 0xFF)) * 0.25f

                    val diffR = baseR - blurR
                    val diffG = baseG - blurG
                    val diffB = baseB - blurB
                    val lumaDiff = Math.abs(0.299f * diffR + 0.587f * diffG + 0.114f * diffB)

                    // Coring gate: jika perbedaan halus < 6, biarkan mulus tanpa bintik noise
                    if (lumaDiff > 6.0f) {
                        val coringFactor = ((lumaDiff - 6.0f) / 12.0f).coerceIn(0f, 1f)
                        baseR += diffR * (sharpen * 0.65f * coringFactor)
                        baseG += diffG * (sharpen * 0.65f * coringFactor)
                        baseB += diffB * (sharpen * 0.65f * coringFactor)
                    }
                }

                val finalR = baseR.toInt().coerceIn(0, 255)
                val finalG = baseG.toInt().coerceIn(0, 255)
                val finalB = baseB.toInt().coerceIn(0, 255)
                out[row + x] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val resBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        resBmp.setPixels(out, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        return resBmp
    }

    private fun applyStabilizationToCover(
        src: Bitmap,
        stab: Stabilizer.Plan,
        keyframeOffsetMs: Long
    ): Bitmap {
        val zoom = stab.zoom.coerceAtLeast(1.0f)
        val (ox, oy) = stab.offsetAt(keyframeOffsetMs)
        val rot = stab.rotAt(keyframeOffsetMs)
        val w = src.width
        val h = src.height
        val matrix = Matrix()
        val deg = Math.toDegrees(rot.toDouble()).toFloat()
        matrix.postRotate(deg, w / 2f, h / 2f)
        matrix.postScale(zoom, zoom, w / 2f, h / 2f)
        matrix.postTranslate(-ox * w, -oy * h)
        val out = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
        val cropX = ((out.width - w) / 2).coerceAtLeast(0)
        val cropY = ((out.height - h) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(
            out, cropX, cropY, w.coerceAtMost(out.width - cropX),
            h.coerceAtMost(out.height - cropY)
        )
        if (cropped !== out) out.recycle()
        return cropped
    }

    fun Bitmap.toJpeg(quality: Int = 96): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), stream)
        return stream.toByteArray()
    }

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
    ): ByteArray {
        val outDir = File(context.cacheDir, "transcode").apply { mkdirs() }
        val outFile = File(outDir, "clip_${System.currentTimeMillis()}.mp4")

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

        if (opts.enhance) {
            effects += GlEffect { ctx, useHdr ->
                EnhanceShader(ctx, useHdr, denoise = 0.55f, sharpen = 0.28f)
            }
            log("Efek: bersih noise (bilateral + coring)")
        }

        if (stab != null && stab.zoom > 1.001f) {
            effects += GlEffect { ctx, useHdr -> StabilizeShader(ctx, useHdr, stab) }
            log("Efek: stabilisasi aktif (${stab.sampleCount} titik koreksi)")
        }

        effects += Presentation.createForWidthAndHeight(
            outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        log("Keluaran: ${outW}x${outH} (${opts.aspectRatio.label})")

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        val bitrate = (outW.toLong() * outH * 12).toInt().coerceIn(3_000_000, 40_000_000)

        return suspendCancellableCoroutine { cont ->
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
            poll?.let { handler.post(it) }

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
        progress: (Int) -> Unit,
        planHint: Plan? = null
    ): Result {
        log("Membaca info video…")
        progress(3)
        val total = withContext(Dispatchers.IO) { videoDurationMs(context, uri) }
        if (total <= 0) throw IllegalStateException("Durasi video tidak terbaca")

        val (srcW, srcH) = withContext(Dispatchers.IO) { videoSize(context, uri) }
        log("Sumber: ${srcW}x${srcH}, ${total} ms")

        val raw = planHint ?: plan(total)
        val p = sanitize(total, raw.startMs, raw.durationMs, raw.keyframeOffsetMs)
        log("Potong: ${p.startMs} → ${p.startMs + p.durationMs} ms")

        val (outW, outH) = calculateDimensions(srcW, srcH, opts)
        log("Target: ${outW}x${outH} (${opts.aspectRatio.label})")

        var stab: Stabilizer.Plan? = null
        if (opts.stabilize) {
            stab = withContext(Dispatchers.Default) {
                Stabilizer.analyze(
                    context, uri, p.startMs, p.durationMs, log,
                    progress = { sample -> progress(5 + sample * 11 / 100) }
                )
            }
        }

        log("Mengambil frame kunci…")
        progress(16)
        val rawBmp = withContext(Dispatchers.IO) {
            extractFrame(
                context, uri, p.startMs + p.keyframeOffsetMs, opts,
                outW, outH, applyLook = false
            )
        } ?: throw IllegalStateException("Gagal mengambil frame dari video")

        val framed = stab?.let { applyStabilizationToCover(rawBmp, it, p.keyframeOffsetMs) } ?: rawBmp
        if (framed !== rawBmp) rawBmp.recycle()

        val bmp = if (opts.enhance) {
            val looked = withContext(Dispatchers.Default) {
                enhanceBitmap(framed, restoreSharpen(opts.stabilize))
            }
            if (looked !== framed) framed.recycle()
            looked
        } else framed

        val jpeg = withContext(Dispatchers.Default) { bmp.toJpeg(opts.jpegQuality) }
        if (!bmp.isRecycled) bmp.recycle()
        log("JPEG: ${jpeg.size / 1024} KB")

        val mp4 = transcodeClip(context, uri, p, opts, outW, outH, stab, log) { enc ->
            progress(18 + enc.coerceIn(0, 100) * 76 / 100)
        }

        log("Menyisipkan XMP GCamera + trailer Samsung…")
        progress(96)
        val motionPhoto = withContext(Dispatchers.Default) {
            MotionPhotoWriter.build(jpeg, mp4, p.keyframeOffsetMs * 1000)
        }
        log("Total berkas: ${motionPhoto.size / 1024} KB")

        val check = MotionPhotoWriter.verify(motionPhoto)
        log(if (check.ok) "Struktur: VALID" else "Struktur: BERMASALAH")

        log("Menyimpan ke DCIM/Camera…")
        progress(98)
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

        try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("Tidak bisa menulis berkas")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, this, null, null)
                }
            }

            runCatching {
                val path = resolvePath(context, uri)
                if (path != null) android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(path), arrayOf("image/jpeg"), null
                )
            }
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

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
