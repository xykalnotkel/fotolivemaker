package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import java.io.ByteArrayOutputStream

/**
 * Penulis Motion Photo dengan dua tata letak yang eksplisit:
 *
 *  1. GOOGLE          -> JPEG + MP4, mengikuti Motion Photo Format 1.0.
 *  2. SAMSUNG_HYBRID  -> JPEG + field SEF + MP4 + indeks SEF.
 *
 * Tata letak Samsung sengaja dipisahkan karena indeks SEF wajib berada di
 * ujung berkas, sementara spesifikasi Google mensyaratkan MP4 sebagai item
 * terakhir. Jangan menyebut hybrid sebagai strict Google walaupun banyak
 * parser yang toleran tetap dapat membacanya.
 */
object MotionPhotoWriter {

    enum class Layout { GOOGLE, SAMSUNG_HYBRID }

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    // --- konstanta format Samsung SEF ---
    private val SEF_NAME = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
    private const val SEF_MARKER = 0x0A30
    private const val SEF_VERSION = 106
    private const val SEF_HEAD = "SEFH"
    private const val SEF_TAIL = "SEFT"
    private const val SEF_BLOCK_SIZE = 32
    private const val SEF_SIZE_VALUE = 24
    /** 00 00 + marker + name length + "MotionPhoto_Data". */
    private const val SEF_FIELD_HEADER_SIZE = 24

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    fun buildXmp(
        videoLength: Int,
        keyframeUs: Long,
        primaryPadding: Int = 0
    ): ByteArray {
        require(videoLength > 0) { "Panjang video harus positif" }
        require(primaryPadding >= 0) { "Padding tidak boleh negatif" }

        val xml = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
            append(" GCamera:MotionPhoto=\"1\"")
            append(" GCamera:MotionPhotoVersion=\"1\"")
            append(" GCamera:MotionPhotoPresentationTimestampUs=\"$keyframeUs\"")
            // Tag MicroVideo lama dipertahankan untuk reader lawas. Reader 1.0
            // wajib mengabaikannya dan memakai Container:Item Length.
            append(" GCamera:MicroVideo=\"1\"")
            append(" GCamera:MicroVideoVersion=\"1\"")
            append(" GCamera:MicroVideoOffset=\"$videoLength\"")
            append(" GCamera:MicroVideoPresentationTimestampUs=\"$keyframeUs\">")
            append("<Container:Directory><rdf:Seq>")
            append("<rdf:li rdf:parseType=\"Resource\">")
            append("<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"$primaryPadding\"/>")
            append("</rdf:li>")
            append("<rdf:li rdf:parseType=\"Resource\">")
            // Padding hanya sah pada item Primary.
            append("<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\"/>")
            append("</rdf:li>")
            append("</rdf:Seq></Container:Directory>")
            append("</rdf:Description></rdf:RDF></x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
        return xml.toByteArray(Charsets.UTF_8)
    }

    fun injectXmp(jpeg: ByteArray, xmp: ByteArray): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            "Data cover bukan JPEG yang valid"
        }
        val payload = XMP_NS.toByteArray(Charsets.UTF_8) + xmp
        require(payload.size + 2 <= 0xFFFF) { "XMP terlalu besar untuk satu segment APP1" }

        val segLen = payload.size + 2
        val header = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((segLen shr 8) and 0xFF).toByte(),
            (segLen and 0xFF).toByte()
        )

        // Sisipkan setelah APP0/APP1 awal agar JFIF/EXIF tetap utuh.
        var i = 2
        while (
            i + 4 <= jpeg.size &&
            (jpeg[i].toInt() and 0xFF) == 0xFF &&
            ((jpeg[i + 1].toInt() and 0xFF) == 0xE0 ||
                (jpeg[i + 1].toInt() and 0xFF) == 0xE1)
        ) {
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or
                (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) break
            i += 2 + length
        }

        val out = ByteArrayOutputStream(jpeg.size + payload.size + 4)
        out.write(jpeg, 0, i)
        out.write(header)
        out.write(payload)
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }

    /** Field SEF: [00 00][marker][panjang nama][nama][video]. */
    fun buildSefField(mp4: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(mp4.size + SEF_FIELD_HEADER_SIZE)
        out.write(byteArrayOf(0, 0))
        out.write(le16(SEF_MARKER))
        out.write(le32(SEF_NAME.size))
        out.write(SEF_NAME)
        out.write(mp4)
        return out.toByteArray()
    }

    /** Blok indeks SEF di ujung berkas. */
    fun buildSefIndex(fieldSize: Int): ByteArray {
        require(fieldSize > SEF_FIELD_HEADER_SIZE) { "Field SEF kosong" }
        val out = ByteArrayOutputStream(SEF_BLOCK_SIZE)
        out.write(SEF_HEAD.toByteArray(Charsets.US_ASCII))
        out.write(le32(SEF_VERSION))
        out.write(le32(1))
        out.write(byteArrayOf(0, 0))
        out.write(le16(SEF_MARKER))
        out.write(le32(fieldSize))
        out.write(le32(fieldSize))
        out.write(le32(SEF_SIZE_VALUE))
        out.write(SEF_TAIL.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** Gabungkan JPEG dan MP4 menggunakan tata letak yang dipilih. */
    fun build(
        jpegBytes: ByteArray,
        mp4Bytes: ByteArray,
        keyframeUs: Long,
        layout: Layout = Layout.GOOGLE
    ): ByteArray {
        require(mp4Bytes.size >= 8 &&
            String(mp4Bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
            "Data video bukan MP4 yang valid"
        }

        return when (layout) {
            Layout.GOOGLE -> {
                val xmp = buildXmp(mp4Bytes.size, keyframeUs, primaryPadding = 0)
                val jpegWithXmp = injectXmp(jpegBytes, xmp)
                ByteArrayOutputStream(jpegWithXmp.size + mp4Bytes.size).apply {
                    write(jpegWithXmp)
                    write(mp4Bytes)
                }.toByteArray()
            }

            Layout.SAMSUNG_HYBRID -> {
                val field = buildSefField(mp4Bytes)
                val index = buildSefIndex(field.size)
                // Reader yang menghitung mundur dari EOF tetap mendarat tepat
                // di awal MP4. Header field SEF dinyatakan sebagai padding.
                val googleLength = mp4Bytes.size + index.size
                val xmp = buildXmp(
                    googleLength,
                    keyframeUs,
                    primaryPadding = SEF_FIELD_HEADER_SIZE
                )
                val jpegWithXmp = injectXmp(jpegBytes, xmp)
                ByteArrayOutputStream(jpegWithXmp.size + field.size + index.size).apply {
                    write(jpegWithXmp)
                    write(field)
                    write(index)
                }.toByteArray()
            }
        }
    }

    /** Offset atom `ftyp` (bukan awal box). -1 kalau tidak ada. */
    fun indexOfFtyp(data: ByteArray): Int {
        // Jalur utama: gunakan Item:Length sehingga string "ftyp" acak di
        // data JPEG tidak pernah salah dianggap sebagai awal MP4.
        val declared = declaredVideoLength(data)
        if (declared in 8..data.size) {
            val start = data.size - declared
            if (start + 8 <= data.size && asciiAt(data, start + 4, "ftyp")) {
                return start + 4
            }
        }

        // Fallback untuk berkas lawas/tanpa Length: cari sesudah EOI JPEG.
        val eoi = jpegEnd(data)
        return indexOf(
            data,
            "ftyp".toByteArray(Charsets.US_ASCII),
            start = if (eoi >= 0) eoi else 0
        )
    }

    /** Ambil MP4 dari Motion Photo. Indeks Samsung dibuang bila ada. */
    fun extractMp4(data: ByteArray): ByteArray? {
        val ftyp = indexOfFtyp(data)
        if (ftyp < 4) return null
        val start = ftyp - 4
        val hasSamsung = indexOf(data, SEF_NAME) >= 0 &&
            data.size >= 4 && asciiAt(data, data.size - 4, SEF_TAIL)
        val end = if (hasSamsung) data.size - SEF_BLOCK_SIZE else data.size
        if (end <= start) return null
        return data.copyOfRange(start, end)
    }

    data class VerifyResult(
        val ok: Boolean,
        val log: String,
        val layout: Layout? = null
    )

    /**
     * Verifikasi struktur yang kita tulis. Ini tidak menggantikan verifikasi
     * MediaStore/perangkat nyata, tetapi mendeteksi offset, padding, dan trailer
     * yang terpotong sebelum berkas disimpan.
     */
    fun verify(data: ByteArray): VerifyResult {
        val sb = StringBuilder()
        var ok = true
        val text = String(data, 0, minOf(data.size, 65536), Charsets.ISO_8859_1)

        val jpegOk = data.size >= 4 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        if (jpegOk) sb.append("✓ JPEG valid\n")
        else { sb.append("✗ Awal JPEG tidak valid\n"); ok = false }

        val xmpOk = text.contains("GCamera:MotionPhoto=\"1\"") &&
            text.contains("GCamera:MotionPhotoVersion=\"1\"")
        if (xmpOk) sb.append("✓ XMP Motion Photo ada\n")
        else { sb.append("✗ XMP Motion Photo tidak lengkap\n"); ok = false }

        val hasName = indexOf(data, SEF_NAME) >= 0
        val hasTail = data.size >= 4 && asciiAt(data, data.size - 4, SEF_TAIL)
        val layout = when {
            hasName && hasTail -> Layout.SAMSUNG_HYBRID
            !hasName && !hasTail -> Layout.GOOGLE
            else -> null
        }
        when (layout) {
            Layout.GOOGLE -> sb.append("✓ Tata letak Google strict (MP4 di EOF)\n")
            Layout.SAMSUNG_HYBRID -> sb.append("✓ Trailer Samsung SEF lengkap\n")
            null -> { sb.append("✗ Trailer Samsung SEF terpotong\n"); ok = false }
        }

        val ftyp = indexOfFtyp(data)
        if (ftyp >= 4) {
            val mp4Start = ftyp - 4
            val declared = declaredVideoLength(data)
            val actual = data.size - mp4Start
            if (declared == actual) {
                sb.append("✓ Item:Length cocok ($declared byte)\n")
            } else {
                sb.append("✗ Item:Length=$declared, nyata=$actual\n")
                ok = false
            }

            val endOfJpeg = jpegEnd(data)
            val actualPadding = if (endOfJpeg >= 0) mp4Start - endOfJpeg else -1
            val declaredPadding = declaredPrimaryPadding(data)
            if (actualPadding >= 0 && declaredPadding == actualPadding) {
                sb.append("✓ Item:Padding cocok ($declaredPadding byte)\n")
            } else {
                sb.append("✗ Item:Padding=$declaredPadding, nyata=$actualPadding\n")
                ok = false
            }
            sb.append("✓ MP4 (ftyp) di offset $mp4Start\n")
        } else {
            sb.append("✗ MP4 tidak ditemukan\n")
            ok = false
        }

        sb.append(
            when {
                !ok -> "=> STRUKTUR BERMASALAH"
                layout == Layout.GOOGLE -> "=> VALID GOOGLE MOTION PHOTO 1.0"
                else -> "=> HYBRID SAMSUNG KONSISTEN"
            }
        )
        return VerifyResult(ok, sb.toString(), layout)
    }

    private fun declaredVideoLength(data: ByteArray): Int {
        val text = String(data, 0, minOf(data.size, 65536), Charsets.ISO_8859_1)
        val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
        val i = text.indexOf(marker)
        if (i < 0) return -1
        return text.substring(i + marker.length).substringBefore('"').toIntOrNull() ?: -1
    }

    private fun declaredPrimaryPadding(data: ByteArray): Int {
        val text = String(data, 0, minOf(data.size, 65536), Charsets.ISO_8859_1)
        val primary = text.indexOf("Item:Semantic=\"Primary\"")
        if (primary < 0) return -1
        val end = text.indexOf("/>", primary).let { if (it < 0) text.length else it }
        val marker = "Item:Padding=\""
        val i = text.indexOf(marker, primary)
        if (i < 0 || i > end) return 0
        return text.substring(i + marker.length).substringBefore('"').toIntOrNull() ?: -1
    }

    /** Posisi tepat setelah EOI JPEG. */
    private fun jpegEnd(data: ByteArray): Int {
        for (i in 2 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return i + 2
        }
        return -1
    }

    private fun asciiAt(data: ByteArray, start: Int, value: String): Boolean {
        if (start < 0 || start + value.length > data.size) return false
        for (i in value.indices) {
            if (data[start + i] != value[i].code.toByte()) return false
        }
        return true
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, start: Int = 0): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        val from = start.coerceAtLeast(0)
        val last = data.size - pattern.size
        outer@ for (i in from..last) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
