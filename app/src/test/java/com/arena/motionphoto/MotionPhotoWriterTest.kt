package com.arena.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes ini yang menjaga bagian paling rawan: kalau Length di XMP meleset
 * satu byte saja, galeri Android tidak akan mengenali file sebagai Motion Photo.
 * Dijalankan otomatis di GitHub Actions sebelum APK dibuat.
 */
class MotionPhotoWriterTest {

    /** JPEG minimal yang valid strukturnya: SOI + APP0(JFIF) + data + EOI */
    private fun fakeJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))          // SOI
        val jfif = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(9)
        val len = jfif.size + 2
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()))
        out.write(jfif)
        out.write(ByteArray(500) { 0x7F })                             // data dummy
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))          // EOI
        return out.toByteArray()
    }

    /** MP4 dummy dengan atom ftyp di awal, seperti file asli. */
    private fun fakeMp4(size: Int = 4096): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0x18))
        out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
        out.write("isom".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0, 2, 0))
        out.write("isomiso2avc1mp41".toByteArray(Charsets.ISO_8859_1))
        while (out.size() < size) out.write(0x42)
        return out.toByteArray()
    }

    @Test
    fun `hasil build lolos verifikasi`() {
        val result = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 1_500_000L)
        val v = MotionPhotoWriter.verify(result)
        assertTrue("verify gagal:\n${v.log}", v.ok)
    }

    @Test
    fun `Length di XMP mencakup mp4 plus trailer SEF`() {
        val mp4 = fakeMp4(7777)
        val result = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

        val text = String(result, Charsets.ISO_8859_1)
        val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
        val i = text.indexOf(marker)
        assertTrue("penanda Length tidak ditemukan", i >= 0)
        val declared = text.substring(i + marker.length).substringBefore('"').toInt()

        // trailer SEF (32 byte) berada SETELAH video, jadi ikut dihitung
        assertEquals("Length = mp4 + blok SEF", mp4.size + 32, declared)
    }

    @Test
    fun `mp4 bisa diekstrak utuh dari ekor file`() {
        val mp4 = fakeMp4(5000)
        val result = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)

        // hitung mundur dari akhir, lalu buang 32 byte blok SEF di ujung
        val extracted = result.copyOfRange(
            result.size - mp4.size - 32, result.size - 32
        )
        assertTrue("byte mp4 hasil ekstrak harus identik", mp4.contentEquals(extracted))
        assertEquals('f'.code.toByte(), extracted[4])  // awal atom ftyp
    }

    @Test
    fun `file tetap diawali SOI jpeg`() {
        val result = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
    }

    @Test
    fun `segment xmp tersisip setelah app0 bukan menimpanya`() {
        val result = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        val text = String(result, Charsets.ISO_8859_1)
        assertTrue("APP0 JFIF harus tetap ada", text.contains("JFIF"))
        assertTrue("namespace XMP harus ada", text.contains("http://ns.adobe.com/xap/1.0/"))
        // XMP harus muncul setelah JFIF
        assertTrue(text.indexOf("ns.adobe.com/xap") > text.indexOf("JFIF"))
    }

    @Test
    fun `timestamp frame kunci tertulis benar`() {
        val result = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 2_250_000L)
        val text = String(result, Charsets.ISO_8859_1)
        assertTrue(text.contains("GCamera:MotionPhotoPresentationTimestampUs=\"2250000\""))
    }

    @Test
    fun `extractMp4 membuang trailer SEF`() {
        val mp4 = fakeMp4(5000)
        val result = MotionPhotoWriter.build(fakeJpeg(), mp4, 0L)
        val extracted = MotionPhotoWriter.extractMp4(result)
        assertTrue(extracted != null)
        assertTrue("MP4 harus utuh tanpa SEF", mp4.contentEquals(extracted))
    }

    @Test
    fun `verify mendeteksi file rusak`() {
        val good = MotionPhotoWriter.build(fakeJpeg(), fakeMp4(), 0L)
        // buang 10 byte terakhir -> Length jadi tidak cocok
        val broken = good.copyOfRange(0, good.size - 40)
        assertTrue("harus terdeteksi rusak", !MotionPhotoWriter.verify(broken).ok)
    }
}
