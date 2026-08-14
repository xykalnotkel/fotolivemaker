package livefoto.xystudio.app

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

    @Test
    fun `cek otomatis pertama kali langsung jatuh tempo`() {
        assertTrue(UpdateCheck.isCheckDue(0L, 1_000L))
    }

    @Test
    fun `cek otomatis tidak diulang sebelum 24 jam`() {
        val last = 1_000L
        assertFalse(UpdateCheck.isCheckDue(last, last + UpdateCheck.AUTO_CHECK_INTERVAL_MS - 1L))
        assertTrue(UpdateCheck.isCheckDue(last, last + UpdateCheck.AUTO_CHECK_INTERVAL_MS))
    }

    @Test
    fun `jam perangkat mundur memicu sinkronisasi ulang`() {
        assertTrue(UpdateCheck.isCheckDue(lastCheckMs = 10_000L, nowMs = 5_000L))
    }
}
