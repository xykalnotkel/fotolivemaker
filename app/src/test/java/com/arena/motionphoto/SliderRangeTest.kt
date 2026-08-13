package com.arena.motionphoto

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Regresi untuk crash "aplikasi terhenti" saat memilih video.
 *
 * Material Slider melempar IllegalStateException kalau valueFrom >= valueTo,
 * atau kalau value berada di luar range. Kode lama menghasilkan kondisi itu
 * untuk video pendek (mis. 0.8 dtk) dan memakai valueTo = 0.01 yang juga
 * bertabrakan dengan stepSize 0.1.
 *
 * Tes ini meniru logika penentuan range di MainActivity dan memastikan
 * hasilnya selalu sah untuk berbagai durasi video, termasuk yang ekstrem.
 */
class SliderRangeTest {

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

    /** Meniru setSlider() di MainActivity. */
    private fun resolve(from: Float, to: Float, value: Float): Triple<Float, Float, Float> {
        val f = round1(from)
        val t = round1(max(to, f + 0.1f))
        val v = round1(value).coerceIn(f, t)
        return Triple(f, t, v)
    }

    private fun assertValid(label: String, r: Triple<Float, Float, Float>) {
        val (from, to, value) = r
        assertTrue("$label: valueFrom($from) harus < valueTo($to)", from < to)
        assertTrue("$label: value($value) harus >= valueFrom($from)", value >= from)
        assertTrue("$label: value($value) harus <= valueTo($to)", value <= to)
    }

    /** Meniru seluruh alur loadVideo() untuk satu durasi video. */
    private fun checkForDuration(durationMs: Long) {
        val label = "durasi ${durationMs}ms"
        val totalSec = round1(durationMs / 1000f)

        val durR = resolve(0.5f, min(6f, totalSec), min(3f, totalSec))
        assertValid("$label / sliderDur", durR)
        val dur = durR.third

        val startR = resolve(0f, max(0f, totalSec - dur), max(0f, totalSec - dur) / 2f)
        assertValid("$label / sliderStart", startR)

        val keyR = resolve(0f, dur, dur / 2f)
        assertValid("$label / sliderKey", keyR)
    }

    @Test
    fun `video sangat pendek tidak bikin crash`() {
        // inilah yang dulu bikin app terhenti
        checkForDuration(300)
        checkForDuration(500)
        checkForDuration(800)
        checkForDuration(1000)
    }

    @Test
    fun `video durasi normal aman`() {
        checkForDuration(3000)
        checkForDuration(5000)
        checkForDuration(15000)
        checkForDuration(60000)
    }

    @Test
    fun `video panjang aman`() {
        checkForDuration(600_000)      // 10 menit
        checkForDuration(3_600_000)    // 1 jam
    }

    @Test
    fun `durasi tanggung dan pecahan aman`() {
        for (ms in longArrayOf(333, 1234, 2999, 3001, 4567, 7777, 12345)) {
            checkForDuration(ms)
        }
    }

    @Test
    fun `menyapu seluruh rentang durasi tetap sah`() {
        var ms = 200L
        while (ms <= 120_000L) {
            checkForDuration(ms)
            ms += 137L    // langkah ganjil biar kena banyak nilai pecahan
        }
    }

    @Test
    fun `mengecilkan durasi klip menjaga range tetap sah`() {
        val totalSec = 10f
        // pengguna menggeser durasi dari 6 turun ke 0.5
        var d = 6f
        while (d >= 0.5f) {
            val startR = resolve(0f, max(0f, totalSec - d), totalSec)  // value sengaja kelebihan
            assertValid("dur=$d / start", startR)
            val keyR = resolve(0f, d, 99f)                              // value sengaja kelebihan
            assertValid("dur=$d / key", keyR)
            d -= 0.1f
        }
    }
}
