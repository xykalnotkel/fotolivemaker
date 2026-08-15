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
            // JNI modify-in-place hanya jalan di ARGB_8888 mutable
            // Caller (Converter) selalu kirim ARGB_8888 mutable copy,
            // jadi branch ini mostly cuma fallback safety.
            if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) {
                enhanceBitmap(bitmap, denoise, sharpen)
            } else {
                // Copy dulu, enhance di copy, terus gambar balik ke aslinya
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val ok = enhanceBitmap(copy, denoise, sharpen)
                if (ok) {
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawBitmap(copy, 0f, 0f, null)
                }
                copy.recycle()
                ok
            }
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
