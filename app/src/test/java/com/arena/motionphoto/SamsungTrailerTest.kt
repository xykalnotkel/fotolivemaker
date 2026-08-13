package com.arena.motionphoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menjaga agar berkas hasil bisa dibaca DUA cara sekaligus:
 *
 *  - Samsung : cari penanda "MotionPhoto_Data", video ada tepat sesudahnya
 *  - Google  : hitung mundur Item:Length byte dari akhir berkas
 *
 * Kalau salah satu rusak, label Live tidak akan muncul di sebagian perangkat.
 */
class SamsungTrailerTest {

    private fun fakeJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val jfif = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(9)
        val len = jfif.size + 2
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()))
        out.write(jfif)
        out.write(ByteArray(500) { 0x7F })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun fakeMp4(size: Int = 8192): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0x18))
        out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
        out.write("isom".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0, 2, 0))
        out.write("isomiso2avc1mp41".toByteArray(Charsets.ISO_8859_1))
        while (out.size() < size) out.write(0x42)
        return out.toByteArray()
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun indexOf(data: ByteArray, pat: ByteArray): Int {
        outer@ for (i in 0..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `berkas diakhiri penanda SEFT`() {
        val f = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        assertEquals("SEFT", String(f, f.size - 4, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `jalur Samsung - video utuh tepat setelah penanda nama`() {
        val mp4 = fakeMp4(6000)
        val f = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

        val name = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        val i = indexOf(f, name)
        assertTrue("penanda MotionPhoto_Data tidak ada", i > 0)

        // video terbentang dari sesudah nama sampai sebelum blok SEF (32 byte)
        val extracted = f.copyOfRange(i + name.size, f.size - 32)
        assertArrayEquals("video jalur Samsung harus identik", mp4, extracted)
    }

    @Test
    fun `jalur Google - hitung mundur Item Length dari akhir berkas`() {
        val mp4 = fakeMp4(7000)
        val f = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

        val text = String(f, Charsets.ISO_8859_1)
        val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
        val mi = text.indexOf(marker)
        assertTrue(mi >= 0)
        val declared = text.substring(mi + marker.length).substringBefore('"').toInt()

        // potongan itu harus dimulai tepat di atom ftyp
        val slice = f.copyOfRange(f.size - declared, f.size)
        assertEquals('f'.code.toByte(), slice[4])
        assertEquals('t'.code.toByte(), slice[5])

        // dan berisi seluruh mp4 di bagian depannya
        assertArrayEquals(mp4, slice.copyOfRange(0, mp4.size))
    }

    @Test
    fun `Item Length mencakup video plus blok SEF`() {
        val mp4 = fakeMp4(5000)
        val f = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)
        val text = String(f, Charsets.ISO_8859_1)
        val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
        val declared = text.substring(text.indexOf(marker) + marker.length)
            .substringBefore('"').toInt()
        assertEquals("harus video + 32 byte SEF", mp4.size + 32, declared)
    }

    @Test
    fun `indeks SEF menunjuk balik ke awal field`() {
        val mp4 = fakeMp4(4096)
        val f = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

        val sefStart = f.size - 32
        assertEquals("SEFH", String(f, sefStart, 4, Charsets.ISO_8859_1))

        val fieldOffset = le32(f, sefStart + 12 + 4)
        val fieldSize = le32(f, sefStart + 12 + 8)
        assertEquals("offset harus sama dengan ukuran field", fieldSize, fieldOffset)

        // mundur sejauh offset harus mendarat di awal field (00 00)
        val start = sefStart - fieldOffset
        assertEquals(0, f[start].toInt())
        assertEquals(0, f[start + 1].toInt())

        // nama ada di posisi yang benar
        val name = String(f, start + 8, 16, Charsets.ISO_8859_1)
        assertEquals("MotionPhoto_Data", name)
    }

    @Test
    fun `sef_size bernilai 24`() {
        val f = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        val v = le32(f, f.size - 8)
        assertEquals(24, v)
    }

    @Test
    fun `verify menerima berkas yang benar`() {
        val f = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 1_500_000L)
        val r = MotionPhotoWriter.verify(f)
        assertTrue(r.log, r.ok)
        assertTrue(r.log.contains("Samsung"))
    }

    @Test
    fun `verify menolak kalau trailer SEF dipotong`() {
        val good = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        val broken = good.copyOfRange(0, good.size - 32)   // buang blok SEF
        assertTrue("harus terdeteksi rusak", !MotionPhotoWriter.verify(broken).ok)
    }

    @Test
    fun `berbagai ukuran video tetap konsisten dua jalur`() {
        val name = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        for (size in intArrayOf(2048, 4096, 10_000, 65_536, 200_000)) {
            val mp4 = fakeMp4(size)
            val f = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

            val i = indexOf(f, name)
            assertArrayEquals(
                "Samsung gagal di size=$size",
                mp4, f.copyOfRange(i + name.size, f.size - 32)
            )

            val text = String(f, Charsets.ISO_8859_1)
            val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
            val declared = text.substring(text.indexOf(marker) + marker.length)
                .substringBefore('"').toInt()
            assertEquals("Google gagal di size=$size", mp4.size + 32, declared)
        }
    }
}
