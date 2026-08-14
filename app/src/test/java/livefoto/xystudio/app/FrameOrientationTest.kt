package livefoto.xystudio.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOrientationTest {

    @Test
    fun `frame encoded landscape rotation 90 harus diputar bila masih landscape`() {
        assertTrue(Converter.shouldApplyRotation(90, 1920, 1080, 1920, 1080))
    }

    @Test
    fun `frame yang sudah portrait tidak boleh kena rotasi dua kali`() {
        assertFalse(Converter.shouldApplyRotation(90, 1920, 1080, 1080, 1920))
    }

    @Test
    fun `frame encoded portrait rotation 270 diputar bila masih portrait`() {
        assertTrue(Converter.shouldApplyRotation(270, 1080, 1920, 1080, 1920))
    }

    @Test
    fun `frame landscape hasil vendor tidak diputar ulang`() {
        assertFalse(Converter.shouldApplyRotation(270, 1080, 1920, 1920, 1080))
    }

    @Test
    fun `rotasi 180 selalu diterapkan sedangkan nol tidak`() {
        assertTrue(Converter.shouldApplyRotation(180, 1920, 1080, 1920, 1080))
        assertFalse(Converter.shouldApplyRotation(0, 1920, 1080, 1920, 1080))
    }
}
