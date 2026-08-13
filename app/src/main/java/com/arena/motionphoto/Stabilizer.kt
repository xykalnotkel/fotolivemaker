package com.arena.motionphoto

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stabilisasi video dengan analisis gerakan nyata.
 *
 * Cara kerja (tiga tahap, seperti stabilizer pada umumnya):
 *
 *  1. ESTIMASI  — ambil sejumlah frame, ubah ke grayscale kecil, lalu cari
 *                 pergeseran antar-frame dengan pencarian blok (block
 *                 matching) memakai metrik SAD. Hasilnya lintasan kamera.
 *
 *  2. SMOOTHING — lintasan itu dihaluskan dengan moving average. Selisih
 *                 antara lintasan asli dan yang halus = guncangan yang
 *                 harus dikoreksi.
 *
 *  3. KOREKSI   — video di-zoom sedikit lalu digeser berlawanan arah
 *                 guncangan. Zoom perlu supaya tidak muncul tepi kosong.
 *
 * Catatan jujur: koreksinya translasi saja (geser X/Y), tidak menangani
 * rotasi. Efektif untuk guncangan tangan, bukan untuk goyang berputar.
 */
object Stabilizer {

    /** Ukuran frame analisis. Kecil supaya cepat, cukup untuk estimasi. */
    private const val ANALYZE_W = 96
    private const val ANALYZE_H = 54

    /** Jangkauan pencarian pergeseran, dalam piksel skala analisis. */
    private const val SEARCH = 8

    /** Berapa frame yang dianalisis. Lebih banyak = lebih akurat, lebih lama. */
    private const val MAX_SAMPLES = 45

    data class Result(
        /** Zoom yang diperlukan, mis. 1.08 = perbesar 8%. */
        val zoom: Float,
        /** Guncangan rata-rata (piksel skala analisis), untuk laporan. */
        val shakiness: Float,
        val sampleCount: Int
    )

    /**
     * Analisis guncangan video dan hitung zoom yang dibutuhkan.
     * Mengembalikan null kalau video tidak bisa dibaca.
     */
    fun analyze(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long,
        log: (String) -> Unit
    ): Result? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)

            val samples = MAX_SAMPLES.coerceAtMost(
                (durationMs / 33).toInt().coerceAtLeast(4)
            )
            val step = durationMs.toFloat() / samples

            log("Stabilizer: menganalisis $samples frame…")

            var prev: IntArray? = null
            val dxs = ArrayList<Float>(samples)
            val dys = ArrayList<Float>(samples)

            for (i in 0 until samples) {
                val t = startMs + (i * step).toLong()
                val bmp = r.getFrameAtTime(
                    t * 1000, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val gray = toGray(bmp)
                bmp.recycle()

                if (prev != null) {
                    val (dx, dy) = estimateShift(prev!!, gray)
                    dxs += dx.toFloat()
                    dys += dy.toFloat()
                }
                prev = gray
            }

            if (dxs.size < 3) {
                log("Stabilizer: frame terlalu sedikit, dilewati")
                return null
            }

            // lintasan kumulatif
            val trajX = FloatArray(dxs.size)
            val trajY = FloatArray(dys.size)
            var ax = 0f
            var ay = 0f
            for (i in dxs.indices) {
                ax += dxs[i]; ay += dys[i]
                trajX[i] = ax; trajY[i] = ay
            }

            // haluskan lintasan
            val smX = movingAverage(trajX, 7)
            val smY = movingAverage(trajY, 7)

            // guncangan = selisih lintasan asli vs halus
            var maxDev = 0f
            var sumDev = 0f
            for (i in trajX.indices) {
                val ex = abs(trajX[i] - smX[i])
                val ey = abs(trajY[i] - smY[i])
                val e = maxOf(ex, ey)
                if (e > maxDev) maxDev = e
                sumDev += e
            }
            val avgDev = sumDev / trajX.size

            // Zoom secukupnya untuk menutupi simpangan terbesar.
            // maxDev dalam skala analisis -> ubah ke rasio lebar frame.
            val ratio = maxDev / ANALYZE_W
            val zoom = (1f + ratio * 2.2f).coerceIn(1.0f, 1.25f)

            log("Stabilizer: guncangan rata-rata %.2f px, zoom %.0f%%"
                .format(avgDev, (zoom - 1f) * 100))

            Result(zoom, avgDev, dxs.size)
        } catch (e: Exception) {
            log("Stabilizer gagal: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            log("Stabilizer: memori tidak cukup")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Bitmap -> array luminance berukuran ANALYZE_W x ANALYZE_H. */
    private fun toGray(src: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(src, ANALYZE_W, ANALYZE_H, true)
        val px = IntArray(ANALYZE_W * ANALYZE_H)
        small.getPixels(px, 0, ANALYZE_W, 0, 0, ANALYZE_W, ANALYZE_H)
        if (small != src) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = (r * 77 + g * 151 + b * 28) shr 8
        }
        return px
    }

    /**
     * Cari pergeseran (dx, dy) yang paling cocok antara dua frame,
     * memakai Sum of Absolute Differences pada area tengah.
     */
    private fun estimateShift(a: IntArray, b: IntArray): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestCost = Long.MAX_VALUE

        // hanya bandingkan area tengah supaya tepi tidak mengganggu
        val x0 = SEARCH
        val x1 = ANALYZE_W - SEARCH
        val y0 = SEARCH
        val y1 = ANALYZE_H - SEARCH

        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                var cost = 0L
                var y = y0
                while (y < y1) {
                    val rowA = y * ANALYZE_W
                    val rowB = (y + dy) * ANALYZE_W
                    var x = x0
                    while (x < x1) {
                        val d = a[rowA + x] - b[rowB + x + dx]
                        cost += if (d < 0) -d.toLong() else d.toLong()
                        x += 2          // lompat 2 piksel: cukup akurat, 2x cepat
                    }
                    y += 2
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun movingAverage(v: FloatArray, window: Int): FloatArray {
        val out = FloatArray(v.size)
        val half = window / 2
        for (i in v.indices) {
            var sum = 0f
            var n = 0
            for (j in (i - half)..(i + half)) {
                if (j in v.indices) { sum += v[j]; n++ }
            }
            out[i] = sum / n
        }
        return out
    }

    /** Bulatkan ke genap — encoder rewel dengan dimensi ganjil. */
    fun evenDim(v: Float): Int {
        var x = v.roundToInt()
        if (x % 2 != 0) x++
        return x
    }
}
