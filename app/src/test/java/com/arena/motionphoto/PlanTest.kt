package com.arena.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trim manual sudah dihapus. Potongan dihitung otomatis, jadi logika ini
 * harus benar untuk SEMUA durasi video, termasuk yang sangat pendek.
 */
class PlanTest {

    private fun check(totalMs: Long) {
        val p = Converter.plan(totalMs)
        val label = "durasi=$totalMs"

        assertTrue("$label: start tidak boleh negatif", p.startMs >= 0)
        assertTrue("$label: durasi harus > 0", p.durationMs > 0)
        assertTrue(
            "$label: klip tidak boleh melewati akhir video",
            p.startMs + p.durationMs <= totalMs
        )
        assertTrue(
            "$label: frame kunci harus di dalam klip",
            p.keyframeOffsetMs in 0..p.durationMs
        )
        assertTrue(
            "$label: klip maksimal 3 dtk",
            p.durationMs <= Converter.TARGET_CLIP_MS
        )
    }

    @Test
    fun `video lebih panjang dari 3 detik diambil 3 detik`() {
        val p = Converter.plan(10_000)
        assertEquals(3000L, p.durationMs)
        assertEquals(3500L, p.startMs)          // tengah
        assertEquals(1500L, p.keyframeOffsetMs) // tengah klip
    }

    @Test
    fun `video lebih pendek dipakai seluruhnya`() {
        val p = Converter.plan(1200)
        assertEquals(1200L, p.durationMs)
        assertEquals(0L, p.startMs)
        assertEquals(600L, p.keyframeOffsetMs)
    }

    @Test
    fun `video tepat 3 detik`() {
        val p = Converter.plan(3000)
        assertEquals(3000L, p.durationMs)
        assertEquals(0L, p.startMs)
    }

    @Test
    fun `sapu banyak durasi tetap sah`() {
        for (ms in longArrayOf(100, 250, 500, 999, 1000, 2999, 3000, 3001, 5000, 60_000, 3_600_000)) {
            check(ms)
        }
        var ms = 60L
        while (ms <= 30_000) {
            check(ms)
            ms += 137
        }
    }

    @Test
    fun `klip selalu berada di dalam video`() {
        var ms = 100L
        while (ms <= 20_000) {
            val p = Converter.plan(ms)
            assertTrue(
                "durasi=$ms start=${p.startMs} dur=${p.durationMs}",
                p.startMs + p.durationMs <= ms
            )
            ms += 91
        }
    }
}
