package com.arena.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menjaga perhitungan ukuran keluaran.
 *
 * Bug sebelumnya: crop 1:1 tidak pernah berfungsi karena dua efek
 * Presentation dipasang berurutan, dan yang kedua (createForHeight)
 * menimpa yang pertama (createForAspectRatio). Sekarang ukuran dihitung
 * eksplisit lalu dipakai oleh SATU Presentation.
 */
class OutputSizeTest {

    /** Meniru perhitungan ukuran di Converter.convert(). */
    private fun outSize(
        srcW: Int, srcH: Int, res: Converter.Res, square: Boolean
    ): Pair<Int, Int> {
        val opts = Converter.Options(square = square, res = res)
        val baseH = opts.heightFor(if (srcH > 0) srcH else 1080)
        return if (square) {
            val s = evenUp(baseH.toFloat())
            s to s
        } else {
            val ratio = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 9f / 16f
            val h = evenUp(baseH.toFloat())
            evenUp(h * ratio) to h
        }
    }

    private fun evenUp(v: Float): Int {
        var x = Math.round(v)
        if (x < 2) x = 2
        if (x % 2 != 0) x++
        return x
    }

    @Test
    fun `crop 1_1 menghasilkan sisi sama`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, square = true)
        assertEquals("harus kotak", w, h)
        assertEquals(1080, w)
    }

    @Test
    fun `crop 1_1 tetap kotak untuk video potret`() {
        val (w, h) = outSize(1080, 1920, Converter.Res.P720, square = true)
        assertEquals(w, h)
        assertEquals(720, w)
    }

    @Test
    fun `tanpa crop menjaga rasio sumber`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, square = false)
        assertEquals(1080, h)
        assertEquals(1920, w)
    }

    @Test
    fun `video potret tanpa crop tetap potret`() {
        val (w, h) = outSize(1080, 1920, Converter.Res.P1080, square = false)
        assertEquals(1080, h)
        assertEquals(608, w)   // 1080 * (1080/1920) = 607.5 -> genap ke atas
        assertTrue("harus lebih tinggi daripada lebar", h > w)
    }

    @Test
    fun `resolusi Asli mengikuti tinggi sumber`() {
        val (w, h) = outSize(3840, 2160, Converter.Res.SOURCE, square = false)
        assertEquals(2160, h)
        assertEquals(3840, w)
    }

    @Test
    fun `resolusi Asli dengan crop jadi kotak seukuran tinggi sumber`() {
        val (w, h) = outSize(3840, 2160, Converter.Res.SOURCE, square = true)
        assertEquals(2160, w)
        assertEquals(2160, h)
    }

    @Test
    fun `dimensi selalu genap`() {
        val sizes = arrayOf(
            intArrayOf(1920, 1080), intArrayOf(1080, 1920), intArrayOf(1440, 1080),
            intArrayOf(640, 480), intArrayOf(1234, 567), intArrayOf(999, 1777),
            intArrayOf(2160, 3840), intArrayOf(720, 1280)
        )
        for (s in sizes) {
            for (r in Converter.Res.values()) {
                for (sq in booleanArrayOf(true, false)) {
                    val (w, h) = outSize(s[0], s[1], r, sq)
                    assertEquals("lebar ganjil untuk ${s[0]}x${s[1]} $r sq=$sq", 0, w % 2)
                    assertEquals("tinggi ganjil untuk ${s[0]}x${s[1]} $r sq=$sq", 0, h % 2)
                    assertTrue("dimensi harus positif", w >= 2 && h >= 2)
                    if (sq) assertEquals("square harus 1:1", w, h)
                }
            }
        }
    }

    @Test
    fun `heightFor untuk SOURCE memakai tinggi sumber`() {
        val o = Converter.Options(res = Converter.Res.SOURCE)
        assertEquals(1440, o.heightFor(1440))
        assertEquals(2160, o.heightFor(2160))
    }

    @Test
    fun `heightFor untuk preset mengabaikan tinggi sumber`() {
        assertEquals(720, Converter.Options(res = Converter.Res.P720).heightFor(4000))
        assertEquals(1080, Converter.Options(res = Converter.Res.P1080).heightFor(240))
    }
}
