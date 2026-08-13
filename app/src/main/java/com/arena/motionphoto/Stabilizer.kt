package com.arena.motionphoto

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Stabilisasi 3 detik: translasi + rotasi kecil.
 *
 * Bukan gimbal / CapCut warp. Cukup untuk goyang tangan.
 * Tidak memperbaiki jalan kaki, rolling shutter, atau putaran besar.
 */
object Stabilizer {

    private const val ANALYZE_W = 128
    private const val ANALYZE_H = 72
    private const val SEARCH = 10
    private const val MAX_SAMPLES = 50

    data class Plan(
        val zoom: Float,
        val shakiness: Float,
        val sampleCount: Int,
        val timesMs: LongArray,
        val offsetX: FloatArray,
        val offsetY: FloatArray,
        val rot: FloatArray
    ) {
        fun offsetAt(tMs: Long): Pair<Float, Float> {
            if (timesMs.isEmpty()) return 0f to 0f
            val i = indexAt(tMs)
            if (i <= 0) return offsetX.first() to offsetY.first()
            if (i >= timesMs.size) return offsetX.last() to offsetY.last()
            val f = frac(tMs, i)
            return (offsetX[i - 1] + (offsetX[i] - offsetX[i - 1]) * f) to
                (offsetY[i - 1] + (offsetY[i] - offsetY[i - 1]) * f)
        }

        fun rotAt(tMs: Long): Float {
            if (timesMs.isEmpty() || rot.isEmpty()) return 0f
            val i = indexAt(tMs)
            if (i <= 0) return rot.first()
            if (i >= timesMs.size) return rot.last()
            val f = frac(tMs, i)
            return rot[i - 1] + (rot[i] - rot[i - 1]) * f
        }

        private fun indexAt(tMs: Long): Int {
            if (tMs <= timesMs.first()) return 0
            if (tMs >= timesMs.last()) return timesMs.size
            var i = 1
            while (i < timesMs.size && timesMs[i] < tMs) i++
            return i
        }

        private fun frac(tMs: Long, i: Int): Float {
            val t0 = timesMs[i - 1]
            val t1 = timesMs[i]
            return if (t1 == t0) 0f else (tMs - t0).toFloat() / (t1 - t0)
        }
    }

    fun analyze(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long,
        log: (String) -> Unit,
        progress: (Int) -> Unit = {}
    ): Plan? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val samples = MAX_SAMPLES.coerceAtMost(
                (durationMs / 33).toInt().coerceAtLeast(4)
            )
            val step = durationMs.toFloat() / samples
            log("Stabilizer: menganalisis $samples frame (geser + putar)…")

            var prev: IntArray? = null
            val times = ArrayList<Long>(samples)
            val dxs = ArrayList<Float>(samples)
            val dys = ArrayList<Float>(samples)
            val drs = ArrayList<Float>(samples)

            for (i in 0 until samples) {
                progress(((i + 1) * 100) / samples)
                val rel = (i * step).toLong()
                val bmp = r.getFrameAtTime(
                    (startMs + rel) * 1000, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val gray = toGray(bmp)
                bmp.recycle()
                val last = prev
                if (last != null) {
                    val motion = estimateMotion(last, gray)
                    times += rel
                    dxs += motion.first
                    dys += motion.second
                    drs += motion.third
                }
                prev = gray
            }

            if (dxs.size < 3) {
                log("Stabilizer: frame terlalu sedikit, dilewati")
                return null
            }

            val n = dxs.size
            val trajX = FloatArray(n)
            val trajY = FloatArray(n)
            val trajR = FloatArray(n)
            var ax = 0f
            var ay = 0f
            var ar = 0f
            for (i in 0 until n) {
                ax += dxs[i]; ay += dys[i]; ar += drs[i]
                trajX[i] = ax; trajY[i] = ay; trajR[i] = ar
            }

            val smX = movingAverage(trajX, 9)
            val smY = movingAverage(trajY, 9)
            val smR = movingAverage(trajR, 9)

            var maxDev = 0f
            var sumDev = 0f
            var maxRot = 0f
            val corrX = FloatArray(n)
            val corrY = FloatArray(n)
            val corrR = FloatArray(n)
            for (i in 0 until n) {
                corrX[i] = trajX[i] - smX[i]
                corrY[i] = trajY[i] - smY[i]
                corrR[i] = (trajR[i] - smR[i]).coerceIn(-0.07f, 0.07f)
                val e = maxOf(abs(corrX[i]), abs(corrY[i]))
                if (e > maxDev) maxDev = e
                if (abs(corrR[i]) > maxRot) maxRot = abs(corrR[i])
                sumDev += e
            }

            val zoom = (1f + (maxDev / ANALYZE_W) * 2.2f + maxRot * 0.9f)
                .coerceIn(1.02f, 1.28f)
            val limit = (1f - 1f / zoom) * 0.5f
            val offX = FloatArray(n)
            val offY = FloatArray(n)
            for (i in 0 until n) {
                offX[i] = (corrX[i] / ANALYZE_W).coerceIn(-limit, limit)
                offY[i] = (corrY[i] / ANALYZE_H).coerceIn(-limit, limit)
            }

            log(
                "Stabilizer: goyang %.1f px, putar %.1f°, zoom %.0f%%, %d titik"
                    .format(sumDev / n, Math.toDegrees(maxRot.toDouble()), (zoom - 1f) * 100, n)
            )
            Plan(zoom, sumDev / n, n, times.toLongArray(), offX, offY, corrR)
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

    /** dx, dy (piksel analisis), dRot (radian). */
    private fun estimateMotion(a: IntArray, b: IntArray): Triple<Float, Float, Float> {
        val mid = ANALYZE_W / 2
        val left = estimateShift(a, b, SEARCH, mid)
        val right = estimateShift(a, b, mid, ANALYZE_W - SEARCH)
        val dx = (left.first + right.first) / 2f
        val dy = (left.second + right.second) / 2f
        val span = (ANALYZE_W * 0.5f).coerceAtLeast(1f)
        val rot = atan2((right.second - left.second).toFloat(), span)
        return Triple(dx, dy, rot.coerceIn(-0.06f, 0.06f))
    }

    private fun estimateShift(
        a: IntArray, b: IntArray, xFrom: Int, xTo: Int
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestCost = Long.MAX_VALUE
        val x0 = xFrom.coerceAtLeast(SEARCH)
        val x1 = xTo.coerceAtMost(ANALYZE_W - SEARCH)
        val y0 = SEARCH
        val y1 = ANALYZE_H - SEARCH
        if (x1 - x0 < 8) return 0 to 0
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
                        x += 2
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
}
