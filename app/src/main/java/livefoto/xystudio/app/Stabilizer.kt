package livefoto.xystudio.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Stabilisasi 3 detik: Multi-Block Motion Estimation + Gaussian Trajectory Smoothing.
 * Memisahkan getaran tangan (jitter) dari pergerakan kamera disengaja (pan).
 */
object Stabilizer {

    private const val ANALYZE_W = 128
    private const val ANALYZE_H = 72
    private const val SEARCH = 12
    private const val MAX_SAMPLES = 45

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
                (durationMs / 33).toInt().coerceAtLeast(6)
            )
            val step = durationMs.toFloat() / samples
            log("Stabilizer: menganalisis $samples frame (Multi-Block)…")

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
                    val motion = estimateMotionGrid(last, gray)
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

            val smX = gaussianSmooth(trajX)
            val smY = gaussianSmooth(trajY)
            val smR = gaussianSmooth(trajR)

            var maxDev = 0f
            var sumDev = 0f
            var maxRot = 0f
            val corrX = FloatArray(n)
            val corrY = FloatArray(n)
            val corrR = FloatArray(n)
            for (i in 0 until n) {
                corrX[i] = trajX[i] - smX[i]
                corrY[i] = trajY[i] - smY[i]
                corrR[i] = (trajR[i] - smR[i]).coerceIn(-0.06f, 0.06f)
                val e = maxOf(abs(corrX[i]), abs(corrY[i]))
                if (e > maxDev) maxDev = e
                if (abs(corrR[i]) > maxRot) maxRot = abs(corrR[i])
                sumDev += e
            }

            val zoom = (1f + (maxDev / ANALYZE_W) * 2.2f + maxRot * 0.8f)
                .coerceIn(1.03f, 1.25f)
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

    /**
     * Multi-Block Grid Motion Estimation (3x3 grid) dengan Median Outlier Rejection.
     * Mengabaikan objek bergerak di foreground dan mengunci gerakan latar belakang.
     */
    private fun estimateMotionGrid(a: IntArray, b: IntArray): Triple<Float, Float, Float> {
        val blockW = ANALYZE_W / 3
        val blockH = ANALYZE_H / 3
        val listDx = ArrayList<Float>()
        val listDy = ArrayList<Float>()
        val leftDys = ArrayList<Float>()
        val rightDys = ArrayList<Float>()

        for (by in 0..2) {
            for (bx in 0..2) {
                val x0 = bx * blockW + 4
                val x1 = (bx + 1) * blockW - 4
                val y0 = by * blockH + 4
                val y1 = (by + 1) * blockH - 4

                if (!hasTexture(a, x0, x1, y0, y1)) continue

                val shift = estimateBlockShift(a, b, x0, x1, y0, y1)
                listDx.add(shift.first.toFloat())
                listDy.add(shift.second.toFloat())

                if (bx == 0) leftDys.add(shift.second.toFloat())
                if (bx == 2) rightDys.add(shift.second.toFloat())
            }
        }

        val medX = median(listDx)
        val medY = median(listDy)

        val leftMedY = if (leftDys.isNotEmpty()) median(leftDys) else medY
        val rightMedY = if (rightDys.isNotEmpty()) median(rightDys) else medY
        val span = (ANALYZE_W * 0.66f).coerceAtLeast(1f)
        val rot = atan2((rightMedY - leftMedY).toDouble(), span.toDouble()).toFloat()

        return Triple(medX, medY, rot.coerceIn(-0.06f, 0.06f))
    }

    private fun hasTexture(a: IntArray, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        var minVal = 255
        var maxVal = 0
        for (y in y0..y1 step 2) {
            val row = y * ANALYZE_W
            for (x in x0..x1 step 2) {
                val v = a[row + x]
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
        }
        return (maxVal - minVal) >= 12
    }

    private fun estimateBlockShift(
        a: IntArray, b: IntArray,
        x0: Int, x1: Int, y0: Int, y1: Int
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestCost = Long.MAX_VALUE
        val xMin = x0.coerceAtLeast(SEARCH)
        val xMax = x1.coerceAtMost(ANALYZE_W - SEARCH)
        val yMin = y0.coerceAtLeast(SEARCH)
        val yMax = y1.coerceAtMost(ANALYZE_H - SEARCH)

        if (xMax <= xMin || yMax <= yMin) return 0 to 0

        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                var cost = 0L
                var y = yMin
                while (y <= yMax) {
                    val rowA = y * ANALYZE_W
                    val rowB = (y + dy) * ANALYZE_W
                    var x = xMin
                    while (x <= xMax) {
                        val d = a[rowA + x] - b[rowB + x + dx]
                        cost += if (d < 0) -d else d
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

    private fun median(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val sorted = list.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2f
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

    private fun gaussianSmooth(v: FloatArray): FloatArray {
        val out = FloatArray(v.size)
        val weights = floatArrayOf(0.06f, 0.12f, 0.20f, 0.24f, 0.20f, 0.12f, 0.06f)
        val half = weights.size / 2
        for (i in v.indices) {
            var sum = 0f
            var wSum = 0f
            for (k in -half..half) {
                val idx = (i + k).coerceIn(0, v.size - 1)
                val w = weights[k + half]
                sum += v[idx] * w
                wSum += w
            }
            out[i] = if (wSum > 0f) sum / wSum else v[i]
        }
        return out
    }
}
