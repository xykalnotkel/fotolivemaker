package com.arena.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Badge CONFIRMED hanya kalau SEMUA pemeriksaan lolos DAN Android mengonfirmasi.
 */
class VerifyLevelTest {

    private fun level(
        lengthOk: Boolean,
        xmpOk: Boolean,
        playable: Boolean,
        systemFlag: Boolean?
    ) = MotionPhotoVerifier.decideLevel(lengthOk, xmpOk, playable, systemFlag)

    @Test
    fun `semua lolos dan sistem konfirmasi maka CONFIRMED`() {
        assertEquals(
            MotionPhotoVerifier.Level.CONFIRMED,
            level(true, true, true, true)
        )
    }

    @Test
    fun `semua lolos tapi sistem belum menandai maka LIKELY`() {
        assertEquals(
            MotionPhotoVerifier.Level.LIKELY,
            level(true, true, true, false)
        )
    }

    @Test
    fun `semua lolos tapi penanda sistem tidak tersedia maka LIKELY`() {
        assertEquals(
            MotionPhotoVerifier.Level.LIKELY,
            level(true, true, true, null)
        )
    }

    @Test
    fun `video tidak bisa diputar tidak boleh CONFIRMED walau sistem bilang ya`() {
        assertEquals(
            MotionPhotoVerifier.Level.FAILED,
            level(true, true, false, true)
        )
    }

    @Test
    fun `xmp hilang tidak boleh CONFIRMED walau sistem bilang ya`() {
        assertEquals(
            MotionPhotoVerifier.Level.FAILED,
            level(true, false, true, true)
        )
    }

    @Test
    fun `length meleset tidak boleh CONFIRMED walau sistem bilang ya`() {
        assertEquals(
            MotionPhotoVerifier.Level.FAILED,
            level(false, true, true, true)
        )
    }

    @Test
    fun `sapu semua kombinasi - CONFIRMED hanya saat semuanya benar`() {
        val bools = listOf(true, false)
        val flags = listOf(true, false, null)
        for (l in bools) for (x in bools) for (p in bools) for (s in flags) {
            val r = level(l, x, p, s)
            val semuaBenar = l && x && p
            if (r == MotionPhotoVerifier.Level.CONFIRMED) {
                assertEquals(true, semuaBenar)
                assertEquals(true, s)
            }
            if (!semuaBenar) {
                assertEquals(MotionPhotoVerifier.Level.FAILED, r)
            }
        }
    }
}
