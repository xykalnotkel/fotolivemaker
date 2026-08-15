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

class StabilizerBoundsTest {

    @Test
    fun `radius pencarian maksimum tidak melewati array`() {
        val maxX = Stabilizer.safeSearchMax(127, dimension = 128, searchRadius = 12)
        val maxY = Stabilizer.safeSearchMax(71, dimension = 72, searchRadius = 12)
        assertEquals(115, maxX)
        assertEquals(59, maxY)
        assertTrue(maxX + 12 < 128)
        assertTrue(maxY + 12 < 72)
    }

    @Test
    fun `batas blok kecil tetap dipertahankan`() {
        assertEquals(38, Stabilizer.safeSearchMax(38, dimension = 128, searchRadius = 12))
    }
}
