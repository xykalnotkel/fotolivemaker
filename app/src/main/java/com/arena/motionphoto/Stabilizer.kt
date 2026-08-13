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

    /**
     * Rencana koreksi: zoom + tabel pergeseran per waktu.
     *
     * offsets disimpan dalam satuan UV (pecahan lebar/tinggi frame),
     * siap dipakai langsung oleh shader.
     */
    data class Plan(
        val zoom: Float,
        val shakiness: Float,
        val sampleCount: Int,
        /** waktu tiap sampel, milidetik relatif terhadap awal klip */
        val timesMs: LongArray,
        val offsetX: FloatArray,
        val offsetY: FloatArray
    ) {
        /** Koreksi pada waktu tertentu, diinterpolasi antar sampel. */
        fun offsetAt(tMs: Long): Pair<Float, Float> {
            if (timesMs.isEmpty()) return 0f to 0f
            if (tMs <= timesMs.first()) return offsetX.first() to offsetY.first()
            if (tMs >= timesMs.last()) return offsetX.last() to offsetY.last()

            var i = 1
            while (i < timesMs.size && timesMs[i] < tMs) i++
            val t0 = timesMs[i - 1]
            val t1 = timesMs[i]
            val f = if (t1 == t0) 0f else (tMs - t0).toFloat() / (t1 - t0)
            return (offsetX[i - 1] + (offsetX[i] - offsetX[i - 1]) * f) to
                   (offsetY[i - 1] + (offsetY[i] - offsetY[i - 1]) * f)
        }
    }

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
    ): Plan? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)

            val samples = MAX_SAMPLES.coerceAtMost(
                (durationMs / 33).toInt().coerceAtLeast(4)
            )
            val step = durationMs.toFloat() / samples

            log("Stabilizer: menganalisis $samples frame…")

            var prev: IntArray? = null
            val times = ArrayList<Long>(samples)
            val dxs = ArrayList<Float>(samples)
            val dys = ArrayList<Float>(samples)

            for (i in 0 until samples) {
                val rel = (i * step).toLong()
                val bmp = r.getFrameAtTime(
                    (startMs + rel) * 1000, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val gray = toGray(bmp)
                bmp.recycle()

                if (prev != null) {
                    val (dx, dy) = estimateShift(prev!!, gray)
                    times += rel
                    dxs += dx.toFloat()
                    dys += dy.toFloat()
                }
                prev = gray
            }

            if (dxs.size < 3) {
                log("Stabilizer: frame terlalu sedikit, dilewati")
                return null
            }

            // lintasan kumulatif kamera
            val n = dxs.size
            val trajX = FloatArray(n)
            val trajY = FloatArray(n)
            var ax = 0f
            var ay = 0f
            for (i in 0 until n) {
                ax += dxs[i]; ay += dys[i]
                trajX[i] = ax; trajY[i] = ay
            }

            // lintasan ideal = versi halus
            val smX = movingAverage(trajX, 9)
            val smY = movingAverage(trajY, 9)

            // koreksi = selisihnya, dibalik arahnya
            val corrX = FloatArray(n)
            val corrY = FloatArray(n)
            var maxDev = 0f
            var sumDev = 0f
            for (i in 0 until n) {
                val ex = trajX[i] - smX[i]
                val ey = trajY[i] - smY[i]
                corrX[i] = ex
                corrY[i] = ey
                val e = maxOf(abs(ex), abs(ey))
                if (e > maxDev) maxDev = e
                sumDev += e
            }
            val avgDev = sumDev / n

            // zoom secukupnya menutup simpangan terbesar, plus sedikit ruang
            val ratio = maxDev / ANALYZE_W
            val zoom = (1f + ratio * 2.4f).coerceIn(1.0f, 1.30f)

            // ubah koreksi ke satuan UV, dan batasi supaya tidak melewati
            // ruang yang disediakan zoom
            val limit = (1f - 1f / zoom) * 0.5f
            val offX = FloatArray(n)
            val offY = FloatArray(n)
            for (i in 0 until n) {
                offX[i] = (corrX[i] / ANALYZE_W).coerceIn(-limit, limit)
                offY[i] = (corrY[i] / ANALYZE_H).coerceIn(-limit, limit)
            }

            log("Stabilizer: guncangan %.2f px, zoom %.0f%%, %d titik koreksi"
                .format(avgDev, (zoom - 1f) * 100, n))

            Plan(zoom, avgDev, n, times.toLongArray(), offX, offY)
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
