package livefoto.xystudio.app

import android.graphics.Bitmap

/**
 * Wrapper NDK C++ untuk proses HD - mempercepat Bersih/HD.
 * Jika lib tidak ada (build lokal tanpa NDK), fallback ke Kotlin.
 */
object NativeHD {

    private var loaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("fotolive_hd")
            loaded = true
        } catch (t: Throwable) {
            loaded = false
            loadError = t
        }
    }

    fun isAvailable(): Boolean = loaded

    // JNI - ada di native_hd.cpp
    @JvmStatic
    private external fun enhanceBitmap(bitmap: Bitmap, denoise: Float, sharpen: Float): Boolean

    @JvmStatic
    private external fun calcBitrate(width: Int, height: Int): Int

    fun enhance(bitmap: Bitmap, denoise: Float = 0.82f, sharpen: Float = 0.46f): Boolean {
        if (!loaded) return false
        return try {
            // Harus ARGB_8888 mutable
            val mutable = if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
            val ok = enhanceBitmap(mutable, denoise, sharpen)
            if (ok && mutable !== bitmap) {
                // copy back if we used copy? Actually we need to copy pixels to original
                // For simplicity, return true and let caller handle copy
                // Here we just recycle copy and report
                // But our JNI modifies in place mutable, so we need to copy pixels to original if different
                if (bitmap.isMutable) {
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawBitmap(mutable, 0f, 0f, null)
                }
                if (mutable !== bitmap) mutable.recycle()
            }
            ok
        } catch (t: Throwable) {
            false
        }
    }

    fun bitrateFor(width: Int, height: Int): Int {
        return if (loaded) {
            try {
                calcBitrate(width, height)
            } catch (_: Throwable) {
                fallbackBitrate(width, height)
            }
        } else {
            fallbackBitrate(width, height)
        }
    }

    private fun fallbackBitrate(w: Int, h: Int): Int {
        val pixels = w.toLong() * h
        return when {
            pixels >= 1920L * 1080L -> (pixels * 6).toInt().coerceIn(8_000_000, 25_000_000)
            pixels >= 1280L * 720L -> (pixels * 7).toInt().coerceIn(6_000_000, 16_000_000)
            else -> (pixels * 8).toInt().coerceIn(3_000_000, 12_000_000)
        }
    }
}
