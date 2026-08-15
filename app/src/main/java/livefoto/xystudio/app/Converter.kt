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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
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
        val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
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
            aspectRatio = if (square) AspectRatio.RATIO_1_1 else AspectRatio.RATIO_9_16,
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

    private const val MAX_OUTPUT_EDGE = 4096
    private const val MAX_SOURCE_PIXELS = 3840L * 2160L
    private const val MAX_ENHANCE_PIXELS = 3840L * 2160L / 2  // 4147200, izinkan 1080x1920 dengan Bersih

    /**
     * Hitung dimensi keluaran berdasarkan rasio aspek dan resolusi pilihan.
     * SOURCE dibatasi 4K agar cover Bitmap dan encoder tidak menghabiskan heap.
     * Filter Bersih memakai batas Full HD karena cover diproses di CPU.
     */
    fun calculateDimensions(srcW: Int, srcH: Int, opts: Options): Pair<Int, Int> {
        val safeW = srcW.takeIf { it > 0 } ?: 1080
        val safeH = srcH.takeIf { it > 0 } ?: 1920
        val sourceRatio = safeW.toFloat() / safeH
        val targetRatio = if (opts.aspectRatio.isOriginal()) sourceRatio
        else opts.aspectRatio.ratioW.toFloat() / opts.aspectRatio.ratioH

        val (outW, outH) = if (opts.res == Res.SOURCE) {
            // Resolusi asli berarti crop terbesar yang muat di frame sumber,
            // tanpa membuat sisi baru yang lebih besar dari sumber.
            if (sourceRatio > targetRatio) {
                evenUp(safeH * targetRatio) to evenUp(safeH)
            } else {
                evenUp(safeW) to evenUp(safeW / targetRatio)
            }
        } else {
            // 1080p portrait harus 1080x1920, bukan 608x1080. Nilai preset
            // mewakili sisi pendek, seperti konvensi editor video.
            val shortEdge = opts.res.height
            if (targetRatio < 1f) {
                evenUp(shortEdge) to evenUp(shortEdge / targetRatio)
            } else {
                evenUp(shortEdge * targetRatio) to evenUp(shortEdge)
            }
        }

        val maxPixels = if (opts.enhance) MAX_ENHANCE_PIXELS else MAX_SOURCE_PIXELS
        return capDimensions(outW, outH, maxPixels, MAX_OUTPUT_EDGE)
    }

    private fun capDimensions(
        width: Int,
        height: Int,
        maxPixels: Long,
        maxEdge: Int
    ): Pair<Int, Int> {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val edgeScale = minOf(1.0, maxEdge.toDouble() / maxOf(w, h))
        val pixelScale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (w.toLong() * h)))
        val scale = minOf(edgeScale, pixelScale)
        if (scale >= 0.999999) return evenUp(w) to evenUp(h)

        fun evenDown(value: Double): Int {
            var x = value.toInt().coerceAtLeast(2)
            if (x % 2 != 0) x--
            return x.coerceAtLeast(2)
        }
        return evenDown(w * scale) to evenDown(h * scale)
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

            bmp = orientFrameIfNeeded(r, bmp)
            processBitmap(bmp, opts, targetW, targetH, applyLook)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * MediaMetadataRetriever tidak konsisten antar-vendor: ada yang memberi
     * frame mentah, ada yang sudah menerapkan rotation metadata. Rotasi hanya
     * dilakukan bila orientasi bitmap masih sama dengan dimensi encoded.
     */
    fun shouldApplyRotation(
        rotationRaw: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Boolean {
        val rotation = ((rotationRaw % 360) + 360) % 360
        return when (rotation) {
            0 -> false
            180 -> true
            90, 270 -> {
                if (encodedWidth <= 0 || encodedHeight <= 0 || encodedWidth == encodedHeight) true
                else {
                    val encodedPortrait = encodedHeight > encodedWidth
                    val displayPortrait = encodedWidth > encodedHeight
                    val bitmapPortrait = bitmapHeight > bitmapWidth
                    bitmapPortrait == encodedPortrait && bitmapPortrait != displayPortrait
                }
            }
            else -> true
        }
    }

    private fun orientFrameIfNeeded(r: MediaMetadataRetriever, source: Bitmap): Bitmap {
        val rotation = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0).let { ((it % 360) + 360) % 360 }
        val rawW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val rawH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        if (!shouldApplyRotation(rotation, rawW, rawH, source.width, source.height)) {
            return source
        }

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** Thumbnail strip untuk TimelineEditorView, memakai satu retriever. */
    fun extractTimelineFrames(
        context: Context,
        uri: Uri,
        totalMs: Long,
        count: Int = 10
    ): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val out = ArrayList<Bitmap>()
        return try {
            retriever.setDataSource(context, uri)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 16
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 9
            val samples = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                count.coerceIn(4, 12)
            } else {
                // Decoder Android 7 lebih mahal; enam sync-frame cukup untuk strip.
                count.coerceIn(4, 6)
            }
            val scale = 240f / maxOf(rawW, rawH)
            val thumbW = evenUp(rawW * scale)
            val thumbH = evenUp(rawH * scale)
            for (i in 0 until samples) {
                val atMs = if (samples <= 1) 0L
                else ((totalMs.coerceAtLeast(1L) - 1L) * i / (samples - 1))
                var bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        atMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        thumbW,
                        thumbH
                    )
                } else {
                    retriever.getFrameAtTime(
                        atMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                } ?: continue
                bitmap = orientFrameIfNeeded(retriever, bitmap)
                if (maxOf(bitmap.width, bitmap.height) > 320) {
                    val s = 320f / maxOf(bitmap.width, bitmap.height)
                    val scaled = bitmap.scale(
                        (bitmap.width * s).toInt().coerceAtLeast(2),
                        (bitmap.height * s).toInt().coerceAtLeast(2)
                    )
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                }
                out += bitmap
            }
            out
        } catch (_: Exception) {
            out.forEach { if (!it.isRecycled) it.recycle() }
            emptyList()
        } catch (_: OutOfMemoryError) {
            out.forEach { if (!it.isRecycled) it.recycle() }
            emptyList()
        } finally {
            runCatching { retriever.release() }
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
                val scaled = bmp.scale(targetW, targetH)
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
        // Coba pakai NDK C++ dulu kalau ada - lebih cepat untuk HD 1080p
        if (NativeHD.isAvailable()) {
            try {
                val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
                val denoise = 0.82f
                if (NativeHD.enhance(mutable, denoise, sharpen)) {
                    return mutable
                } else {
                    mutable.recycle()
                }
            } catch (_: Throwable) {
                // fallback ke Kotlin
            }
        }
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

        val resBmp = createBitmap(w, h)
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

    private data class EncodedVideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long
    )

    private fun inspectEncodedVideo(file: File): EncodedVideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (width, height) = if (rotation == 90 || rotation == 270) rawH to rawW
            else rawW to rawH
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            EncodedVideoInfo(width, height, duration)
        } finally {
            runCatching { retriever.release() }
        }
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
                EnhanceShader(ctx, useHdr, denoise = 0.90f, sharpen = 0.62f)
            }
            log("Efek: HD iPhone - bersih noise ultra + sharpen coring kuat")
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

        val pixelCount = outW.toLong() * outH
        // Bitrate lebih tinggi untuk HD mulus seperti iPhone, tapi tetap aman untuk encoder
        val bitrate = when {
            pixelCount >= 1920L * 1080L -> (pixelCount * 9).toInt().coerceIn(14_000_000, 32_000_000)
            pixelCount >= 1280L * 720L -> (pixelCount * 8).toInt().coerceIn(10_000_000, 20_000_000)
            else -> (pixelCount * 9).toInt().coerceIn(5_000_000, 14_000_000)
        }

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
                            val info = inspectEncodedVideo(outFile)
                            check(
                                kotlin.math.abs(info.width - outW) <= 2 &&
                                    kotlin.math.abs(info.height - outH) <= 2
                            ) {
                                "Resolusi encoder ${info.width}x${info.height}, target ${outW}x${outH}"
                            }
                            check(info.durationMs > 0L) { "Durasi hasil encode tidak valid" }
                            val bytes = outFile.readBytes()
                            log(
                                "Encode terverifikasi: ${info.width}x${info.height}, " +
                                    "${info.durationMs} ms, ${bytes.size / 1024} KB"
                            )
                            outFile.delete()
                            cont.resume(bytes)
                        } catch (e: Exception) {
                            outFile.delete()
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
            handler.post(poll)

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
            try {
                val looked = withContext(Dispatchers.Default) {
                    enhanceBitmap(framed, restoreSharpen(opts.stabilize))
                }
                if (looked !== framed) framed.recycle()
                looked
            } catch (_: OutOfMemoryError) {
                // Jangan jatuhkan seluruh export hanya karena filter cover.
                // Dimensi sudah dibatasi Full HD, ini fallback perangkat heap kecil.
                log("Memori filter cover terbatas; cover dipakai tanpa Bersih")
                framed
            }
        } else framed

        val jpeg = withContext(Dispatchers.Default) { bmp.toJpeg(opts.jpegQuality) }
        if (!bmp.isRecycled) bmp.recycle()
        log("JPEG: ${jpeg.size / 1024} KB")

        val mp4 = try {
            transcodeClip(context, uri, p, opts, outW, outH, stab, log) { enc ->
                progress(18 + enc.coerceIn(0, 100) * 76 / 100)
            }
        } catch (e: Exception) {
            // Fallback otomatis kalau 1080p gagal - coba 720p dengan bitrate lebih rendah
            if (opts.res == Res.P1080 && (outW > 1280 || outH > 1280)) {
                log("1080p gagal (${e.message}), coba fallback 720p...")
                val (fallbackW, fallbackH) = calculateDimensions(srcW, srcH, opts.copy(res = Res.P720))
                try {
                    transcodeClip(context, uri, p, opts.copy(res = Res.P720), fallbackW, fallbackH, stab, log) { enc ->
                        progress(18 + enc.coerceIn(0, 100) * 76 / 100)
                    }
                } catch (e2: Exception) {
                    throw IllegalStateException("Encoder 1080p & 720p gagal: ${e2.message}", e2)
                }
            } else {
                throw e
            }
        }

        val writerLayout = if (
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            Build.BRAND.equals("samsung", ignoreCase = true)
        ) MotionPhotoWriter.Layout.SAMSUNG_HYBRID
        else MotionPhotoWriter.Layout.GOOGLE

        log(
            if (writerLayout == MotionPhotoWriter.Layout.SAMSUNG_HYBRID)
                "Mengemas XMP + kompatibilitas Samsung SEF…"
            else
                "Mengemas Google Motion Photo 1.0…"
        )
        progress(96)
        val motionPhoto = withContext(Dispatchers.Default) {
            MotionPhotoWriter.build(
                jpeg, mp4, p.keyframeOffsetMs * 1000,
                layout = writerLayout
            )
        }
        log("Total berkas: ${motionPhoto.size / 1024} KB")

        val check = MotionPhotoWriter.verify(motionPhoto)
        log(if (check.ok) "Struktur: konsisten" else "Struktur: BERMASALAH")

        log("Menyimpan ke DCIM/Camera…")
        progress(98)
        val savedUri = withContext(Dispatchers.IO) { saveToGallery(context, motionPhoto) }
        log("Tersimpan.")

        return Result(savedUri, p, check.log, motionPhoto.size)
    }

    private fun saveToGallery(context: Context, data: ByteArray): Uri {
        // Motion Photo Format 1.0: nama harus berakhir "MP" sebelum ekstensi.
        val name = "MP_${System.currentTimeMillis()}MP.jpg"
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
