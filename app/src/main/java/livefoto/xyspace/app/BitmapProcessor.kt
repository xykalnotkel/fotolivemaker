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
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/**
 * Semua operasi Bitmap: enhance, crop, scale, rotasi, frame extraction.
 * Dipisah dari Converter supaya fokus dan gampang di-test.
 */
object BitmapProcessor {

    /**
     * Filter Bilateral Edge-Preserving dengan Coring Threshold.
     */
    fun enhance(src: Bitmap, sharpen: Float): Bitmap {
        // NDK dulu kalo ada
        if (NativeHD.isAvailable()) {
            try {
                val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
                val denoise = 0.82f
                if (NativeHD.enhance(mutable, denoise, sharpen)) return mutable
                else mutable.recycle()
            } catch (_: Throwable) { /* fallback ke Kotlin */ }
        }
        val w = src.width; val h = src.height
        if (w < 3 || h < 3) return src
        val work = if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false)
        val inn = IntArray(w * h); val out = IntArray(w * h)
        work.getPixels(inn, 0, w, 0, 0, w, h)

        val sigmaCSq = 26f * 26f
        val denoiseAmount = 0.65f

        for (y in 0 until h) {
            val y0 = (y - 2).coerceAtLeast(0); val y1 = (y + 2).coerceAtMost(h - 1)
            val row = y * w
            for (x in 0 until w) {
                val x0 = (x - 2).coerceAtLeast(0); val x1 = (x + 2).coerceAtMost(w - 1)
                val cp = inn[row + x]; val cr = (cp shr 16) and 0xFF; val cg = (cp shr 8) and 0xFF; val cb = cp and 0xFF; val a = cp shr 24
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (yy in y0..y1) {
                    val sRow = yy * w; val dy = yy - y
                    for (xx in x0..x1) {
                        val p = inn[sRow + xx]
                        val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
                        val dx = xx - x; val sd = dx * dx + dy * dy
                        val sp = when (sd) { 0 -> 4.0f; 1 -> 2.5f; 2 -> 1.8f; else -> 1.0f }
                        val cd = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)) / 3f
                        val cw = kotlin.math.exp((-cd / (2f * sigmaCSq)).toDouble()).toFloat()
                        val t = sp * cw; sr += pr * t; sg += pg * t; sb += pb * t; sw += t
                    }
                }
                val iw = if (sw > 0.0001f) 1f / sw else 1f
                var br = cr + (sr * iw - cr) * denoiseAmount
                var bg = cg + (sg * iw - cg) * denoiseAmount
                var bb = cb + (sb * iw - cb) * denoiseAmount

                if (sharpen > 0.01f) {
                    val top = inn[((y - 1).coerceAtLeast(0)) * w + x]
                    val btm = inn[((y + 1).coerceAtMost(h - 1)) * w + x]
                    val lft = inn[row + (x - 1).coerceAtLeast(0)]
                    val rgt = inn[row + (x + 1).coerceAtMost(w - 1)]
                    val brr = ((top shr 16 and 0xFF) + (btm shr 16 and 0xFF) + (lft shr 16 and 0xFF) + (rgt shr 16 and 0xFF)) * 0.25f
                    val bgg = ((top shr 8 and 0xFF) + (btm shr 8 and 0xFF) + (lft shr 8 and 0xFF) + (rgt shr 8 and 0xFF)) * 0.25f
                    val bbb = ((top and 0xFF) + (btm and 0xFF) + (lft and 0xFF) + (rgt and 0xFF)) * 0.25f
                    val dr = br - brr; val dg = bg - bgg; val db = bb - bbb
                    val luma = kotlin.math.abs(0.299f * dr + 0.587f * dg + 0.114f * db)
                    if (luma > 6.0f) {
                        val cf = ((luma - 6.0f) / 12.0f).coerceIn(0f, 1f)
                        br += dr * (sharpen * 0.65f * cf)
                        bg += dg * (sharpen * 0.65f * cf)
                        bb += db * (sharpen * 0.65f * cf)
                    }
                }
                out[row + x] = (a shl 24) or (br.toInt().coerceIn(0, 255) shl 16) or
                        (bg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255)
            }
        }
        val res = createBitmap(w, h)
        res.setPixels(out, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        return res
    }

    fun process(src: Bitmap, opts: Converter.Options, tw: Int, th: Int, applyLook: Boolean): Bitmap {
        var bmp = src
        if (!opts.aspectRatio.isOriginal()) {
            val tr = opts.aspectRatio.ratioW.toFloat() / opts.aspectRatio.ratioH
            val sr = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val (cw, ch) = if (sr > tr) {
                (bmp.height * tr).toInt().coerceAtMost(bmp.width) to bmp.height
            } else {
                bmp.width to (bmp.width / tr).toInt().coerceAtMost(bmp.height)
            }
            val x = ((bmp.width - cw) / 2).coerceAtLeast(0)
            val y = ((bmp.height - ch) / 2).coerceAtLeast(0)
            if (cw > 0 && ch > 0 && (cw != bmp.width || ch != bmp.height)) {
                val c = Bitmap.createBitmap(bmp, x, y, cw, ch)
                if (bmp !== src) bmp.recycle(); bmp = c
            }
        }
        if (bmp.width != tw || bmp.height != th && tw > 0 && th > 0) {
            val s = bmp.scale(tw, th)
            if (s !== bmp) { if (bmp !== src) bmp.recycle(); bmp = s }
        }
        if (applyLook && opts.enhance) {
            val e = enhance(bmp, VideoMath.restoreSharpen(opts.stabilize))
            if (e !== bmp) { if (bmp !== src) bmp.recycle(); bmp = e }
        }
        if (bmp !== src) src.recycle()
        return bmp
    }

    fun applyStabilization(src: Bitmap, stab: Stabilizer.Plan, keyMs: Long): Bitmap {
        val zoom = stab.zoom.coerceAtLeast(1.0f); val (ox, oy) = stab.offsetAt(keyMs)
        val rot = stab.rotAt(keyMs); val w = src.width; val h = src.height
        val m = Matrix().apply {
            postRotate(java.lang.Math.toDegrees(rot.toDouble()).toFloat(), w / 2f, h / 2f)
            postScale(zoom, zoom, w / 2f, h / 2f); postTranslate(-ox * w, -oy * h)
        }
        val out = Bitmap.createBitmap(src, 0, 0, w, h, m, true)
        val cx = ((out.width - w) / 2).coerceAtLeast(0); val cy = ((out.height - h) / 2).coerceAtLeast(0)
        val cr = Bitmap.createBitmap(out, cx, cy, w.coerceAtMost(out.width - cx), h.coerceAtMost(out.height - cy))
        if (cr !== out) out.recycle(); return cr
    }

    fun orientFrame(r: MediaMetadataRetriever, src: Bitmap): Bitmap {
        val rot = ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0).let { ((it % 360) + 360) % 360 })
        val ew = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val eh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (!VideoMath.shouldApplyRotation(rot, ew, eh, src.width, src.height)) return src
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle(); return rotated
    }

    fun extractFrame(context: Context, uri: Uri, atMs: Long, opts: Converter.Options,
                     tw: Int, th: Int, applyLook: Boolean = true): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            var bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime() ?: return null
            bmp = orientFrame(r, bmp)
            process(bmp, opts, tw, th, applyLook)
        } catch (_: Exception) { null }
        catch (_: OutOfMemoryError) { null }
        finally { runCatching { r.release() } }
    }

    fun extractTimelineFrames(context: Context, uri: Uri, totalMs: Long, count: Int = 10): List<Bitmap> {
        val r = MediaMetadataRetriever(); val out = ArrayList<Bitmap>()
        return try {
            r.setDataSource(context, uri)
            val rw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1) ?: 16
            val rh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1) ?: 9
            val samples = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) count.coerceIn(4, 12) else count.coerceIn(4, 6)
            val sc = 240f / maxOf(rw, rh); val tww = VideoMath.evenUp(rw * sc); val thh = VideoMath.evenUp(rh * sc)
            for (i in 0 until samples) {
                val at = if (samples <= 1) 0L else ((totalMs.coerceAtLeast(1L) - 1L) * i / (samples - 1))
                var bmp = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tww, thh)
                else r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                bmp = orientFrame(r, bmp)
                if (maxOf(bmp.width, bmp.height) > 320) {
                    val s = 320f / maxOf(bmp.width, bmp.height)
                    val scaled = bmp.scale((bmp.width * s).toInt().coerceAtLeast(2), (bmp.height * s).toInt().coerceAtLeast(2))
                    if (scaled !== bmp) bmp.recycle(); bmp = scaled
                }
                out += bmp
            }
            out
        } catch (_: Exception) { out.forEach { if (!it.isRecycled) it.recycle() }; emptyList() }
        catch (_: OutOfMemoryError) { out.forEach { if (!it.isRecycled) it.recycle() }; emptyList() }
        finally { runCatching { r.release() } }
    }

    fun Bitmap.toJpeg(quality: Int = 96): ByteArray {
        val s = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), s)
        return s.toByteArray()
    }
}