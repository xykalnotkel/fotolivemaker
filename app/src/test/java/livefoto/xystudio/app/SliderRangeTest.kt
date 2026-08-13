package livefoto.xystudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menjaga dua slider editor: jendela 3 dtk + frame kunci.
 * Material Slider melempar kalau valueFrom >= valueTo.
 */
class SliderRangeTest {

    private fun assertValid(label: String, r: Triple<Float, Float, Float>) {
        val (from, to, value) = r
        assertTrue("$label: valueFrom($from) harus < valueTo($to)", from < to)
        assertTrue("$label: value($value) harus >= valueFrom($from)", value >= from)
        assertTrue("$label: value($value) harus <= valueTo($to)", value <= to)
    }

    private fun check(durationMs: Long) {
        val sl = Converter.clipSliders(durationMs)
        assertValid("dur=$durationMs / start", sl.start)
        assertValid("dur=$durationMs / key", sl.key)
        assertTrue("clip harus > 0", sl.clipSec > 0f)
        assertTrue("clip maksimal 3 dtk", sl.clipSec <= 3.01f)
        val plan = Converter.sanitize(
            durationMs,
            (sl.start.third * 1000).toLong(),
            (sl.clipSec * 1000).toLong(),
            (sl.key.third * 1000).toLong()
        )
        assertTrue(plan.startMs >= 0)
        assertTrue(plan.startMs + plan.durationMs <= plan.totalMs)
        assertTrue(plan.keyframeOffsetMs in 0..plan.durationMs)
    }

    @Test
    fun `video sangat pendek tidak bikin range rusak`() {
        check(300)
        check(500)
        check(800)
        check(1000)
        assertFalse(Converter.clipSliders(800).showStart)
    }

    @Test
    fun `video di atas 3 detik menampilkan slider mulai`() {
        val sl = Converter.clipSliders(10_000)
        assertTrue(sl.showStart)
        assertEquals(3.0f, sl.clipSec, 0.01f)
        check(3000)
        check(5000)
        check(15_000)
        check(60_000)
    }

    @Test
    fun `video panjang aman`() {
        check(600_000)
        check(3_600_000)
    }

    @Test
    fun `menyapu seluruh rentang durasi tetap sah`() {
        var ms = 200L
        while (ms <= 30_000L) {
            check(ms)
            ms += 137L
        }
    }

    @Test
    fun `usulan di luar jangkauan dirapikan`() {
        val p = Converter.sanitize(5000, 9000, 8000, 9999)
        assertEquals(2000L, p.startMs) // 5000 - 3000
        assertEquals(3000L, p.durationMs)
        assertEquals(3000L, p.keyframeOffsetMs)
    }
}
