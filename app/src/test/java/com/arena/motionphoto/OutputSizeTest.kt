package com.arena.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menjaga perhitungan ukuran keluaran untuk SEMUA rasio aspek dan resolusi.
 */
class OutputSizeTest {

    private fun outSize(
        srcW: Int,
        srcH: Int,
        res: Converter.Res,
        aspectRatio: Converter.AspectRatio
    ): Pair<Int, Int> {
        val opts = Converter.Options(aspectRatio = aspectRatio, res = res)
        return Converter.calculateDimensions(srcW, srcH, opts)
    }

    @Test
    fun `crop 1_1 menghasilkan sisi sama`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, Converter.AspectRatio.RATIO_1_1)
        assertEquals("harus kotak", w, h)
        assertEquals(1080, w)
    }

    @Test
    fun `crop 1_1 tetap kotak untuk video potret`() {
        val (w, h) = outSize(1080, 1920, Converter.Res.P720, Converter.AspectRatio.RATIO_1_1)
        assertEquals(w, h)
        assertEquals(720, w)
    }

    @Test
    fun `rasio 9_16 menghasilkan proporsi layar penuh vertikal`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, Converter.AspectRatio.RATIO_9_16)
        assertEquals(1080, h)
        assertEquals(Converter.evenUp(1080 * 9f / 16f), w)
        assertEquals(608, w)
    }

    @Test
    fun `rasio 3_4 menghasilkan proporsi portrait`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, Converter.AspectRatio.RATIO_3_4)
        assertEquals(1080, h)
        assertEquals(Converter.evenUp(1080 * 3f / 4f), w)
        assertEquals(810, w)
    }

    @Test
    fun `rasio 16_9 menghasilkan proporsi landscape`() {
        val (w, h) = outSize(1080, 1920, Converter.Res.P1080, Converter.AspectRatio.RATIO_16_9)
        assertEquals(1080, h)
        assertEquals(Converter.evenUp(1080 * 16f / 9f), w)
        assertEquals(1920, w)
    }

    @Test
    fun `tanpa crop menjaga rasio sumber`() {
        val (w, h) = outSize(1920, 1080, Converter.Res.P1080, Converter.AspectRatio.ORIGINAL)
        assertEquals(1080, h)
        assertEquals(1920, w)
    }

    @Test
    fun `video potret tanpa crop tetap potret`() {
        val (w, h) = outSize(1080, 1920, Converter.Res.P1080, Converter.AspectRatio.ORIGINAL)
        assertEquals(1080, h)
        assertEquals(608, w)   // 1080 * (1080/1920) = 607.5 -> genap ke atas
        assertTrue("harus lebih tinggi daripada lebar", h > w)
    }

    @Test
    fun `resolusi Asli mengikuti tinggi sumber`() {
        val (w, h) = outSize(3840, 2160, Converter.Res.SOURCE, Converter.AspectRatio.ORIGINAL)
        assertEquals(2160, h)
        assertEquals(3840, w)
    }

    @Test
    fun `resolusi Asli dengan crop 1_1 jadi kotak seukuran tinggi sumber`() {
        val (w, h) = outSize(3840, 2160, Converter.Res.SOURCE, Converter.AspectRatio.RATIO_1_1)
        assertEquals(2160, w)
        assertEquals(2160, h)
    }

    @Test
    fun `dimensi selalu genap untuk semua rasio dan resolusi`() {
        val sizes = arrayOf(
            intArrayOf(1920, 1080), intArrayOf(1080, 1920), intArrayOf(1440, 1080),
            intArrayOf(640, 480), intArrayOf(1234, 567), intArrayOf(999, 1777),
            intArrayOf(2160, 3840), intArrayOf(720, 1280)
        )
        for (s in sizes) {
            for (r in Converter.Res.entries) {
                for (ar in Converter.AspectRatio.entries) {
                    val (w, h) = outSize(s[0], s[1], r, ar)
                    assertEquals("lebar ganjil untuk ${s[0]}x${s[1]} $r ar=$ar", 0, w % 2)
                    assertEquals("tinggi ganjil untuk ${s[0]}x${s[1]} $r ar=$ar", 0, h % 2)
                    assertTrue("dimensi harus positif", w >= 2 && h >= 2)
                    if (ar == Converter.AspectRatio.RATIO_1_1) {
                        assertEquals("square harus 1:1", w, h)
                    }
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
