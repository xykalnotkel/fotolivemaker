package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

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
    fun `sanitize menahan usulan di dalam video`() {
        val p = Converter.sanitize(8_000, 6_500, 3_000, 1_500)
        assertEquals(5_000L, p.startMs)
        assertEquals(3_000L, p.durationMs)
        assertEquals(1_500L, p.keyframeOffsetMs)
        assertTrue(p.startMs + p.durationMs <= 8_000L)
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
