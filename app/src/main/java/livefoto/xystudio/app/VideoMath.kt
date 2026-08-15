package livefoto.xystudio.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/**
 * Fungsi matematika & geometri murni untuk video.
 * Dipisah dari Converter biar gampang di-test dan gak nyampur sama
 * logic encode, bitmap processing, dan MediaStore.
 */
object VideoMath {

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
        P1440("2K", 1440),
        P2160("4K UHD", 2160),
        SOURCE("Asli", 0)
    }

    /** Opsi processing yang bisa dipilih user. */
    data class Options(
        val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
        val res: Res = Res.P1080,
        val enhance: Boolean = false,
        val stabilize: Boolean = false,
        val jpegQuality: Int = 96
    ) {
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

    private const val MAX_OUTPUT_EDGE = 4096
    private const val MAX_SOURCE_PIXELS = 3840L * 2160L
    private const val MAX_ENHANCE_PIXELS = 3840L * 2160L / 2

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

    /** Bulatkan ke atas ke bilangan genap; encoder menolak dimensi ganjil. */
    fun evenUp(v: Number): Int {
        var x = Math.round(v.toFloat())
        if (x < 2) x = 2
        if (x % 2 != 0) x++
        return x
    }

    /**
     * Hitung dimensi keluaran berdasarkan rasio aspek dan resolusi pilihan.
     */
    fun calculateDimensions(srcW: Int, srcH: Int, opts: Options): Pair<Int, Int> {
        val safeW = srcW.takeIf { it > 0 } ?: 1080
        val safeH = srcH.takeIf { it > 0 } ?: 1920
        val sourceRatio = safeW.toFloat() / safeH
        val targetRatio = if (opts.aspectRatio.isOriginal()) sourceRatio
        else opts.aspectRatio.ratioW.toFloat() / opts.aspectRatio.ratioH

        val (outW, outH) = if (opts.res == Res.SOURCE) {
            if (sourceRatio > targetRatio) {
                evenUp(safeH * targetRatio) to evenUp(safeH)
            } else {
                evenUp(safeW) to evenUp(safeW / targetRatio)
            }
        } else {
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
        width: Int, height: Int, maxPixels: Long, maxEdge: Int
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

    /** Ambil durasi video via MediaMetadataRetriever. */
    fun videoDurationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L }
        finally { runCatching { r.release() } }
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
        } catch (e: Exception) { 0 to 0 }
        finally { runCatching { r.release() } }
    }

    fun sliderRound(v: Float): Float = Math.round(v * 10f) / 10f

    /** Range slider yang selalu sah: from < to, value di dalamnya. */
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

    /** Dua slider: posisi jendela 3 dtk, dan frame kunci di dalam jendela. */
    fun clipSliders(
        totalMs: Long, startSec: Float? = null, keySec: Float? = null
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

    /** Konversi Bitmap ke JPEG byte array. */
    fun Bitmap.toJpeg(quality: Int = 96): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), stream)
        return stream.toByteArray()
    }

    /**
     * MediaMetadataRetriever tidak konsisten antar-vendor: ada yang memberi
     * frame mentah, ada yang sudah menerapkan rotation metadata.
     */
    fun shouldApplyRotation(
        rotationRaw: Int, encodedWidth: Int, encodedHeight: Int,
        bitmapWidth: Int, bitmapHeight: Int
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

    fun restoreSharpen(stabilize: Boolean): Float = if (stabilize) 0.38f else 0.28f
}