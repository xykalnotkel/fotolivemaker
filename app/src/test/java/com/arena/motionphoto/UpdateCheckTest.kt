package com.arena.motionphoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun `tag lebih baru terdeteksi`() {
        assertTrue(UpdateCheck.isNewer("1.1.0", "v1.2.0"))
        assertTrue(UpdateCheck.isNewer("1.0.15-ci", "v1.1.0"))
    }

    @Test
    fun `versi sama bukan update`() {
        assertFalse(UpdateCheck.isNewer("1.1.0", "v1.1.0"))
        assertFalse(UpdateCheck.isNewer("v1.1.0", "1.1.0"))
    }

    @Test
    fun `installed lebih baru tidak muncul banner`() {
        assertFalse(UpdateCheck.isNewer("1.2.0", "v1.1.0"))
    }
}
