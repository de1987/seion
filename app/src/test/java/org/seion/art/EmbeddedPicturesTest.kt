package org.seion.art

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedPicturesTest {
    private val front = "FRONT".toByteArray()
    private val back = "BACK".toByteArray()

    private fun pictureStruct(type: Int, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).apply {
            writeInt(type)
            "image/jpeg".toByteArray().let {
                writeInt(it.size)
                write(it)
            }
            "desc".toByteArray().let {
                writeInt(it.size)
                write(it)
            }
            repeat(4) { writeInt(0) }
            writeInt(data.size)
            write(data)
        }
        return o.toByteArray()
    }

    private fun flacFile(vararg pictures: Pair<Int, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("fLaC".toByteArray())
        // STREAMINFO (34 bytes), then the picture blocks; the last block carries the "last" flag.
        o.write(byteArrayOf(0, 0, 0, 34))
        o.write(ByteArray(34))
        pictures.forEachIndexed { i, (type, data) ->
            val block = pictureStruct(type, data)
            o.write((if (i == pictures.lastIndex) 0x80 else 0) or 6)
            o.write(byteArrayOf((block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()))
            o.write(block)
        }
        o.write(ByteArray(64)) // audio frames
        return o.toByteArray()
    }

    private fun id3v23(vararg pictures: Pair<Int, ByteArray>): ByteArray {
        val frames = ByteArrayOutputStream()
        // A text frame first, then APIC frames.
        val tit2 = byteArrayOf(0) + "Title".toByteArray()
        frames.write("TIT2".toByteArray())
        frames.write(int32(tit2.size))
        frames.write(byteArrayOf(0, 0))
        frames.write(tit2)
        for ((type, data) in pictures) {
            val body = ByteArrayOutputStream().apply {
                write(0) // ISO-8859-1
                write("image/jpeg".toByteArray())
                write(0)
                write(type)
                write("cover".toByteArray())
                write(0)
                write(data)
            }.toByteArray()
            frames.write("APIC".toByteArray())
            frames.write(int32(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        frames.write(ByteArray(20)) // padding
        val tag = frames.toByteArray()
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(tag.size)
        return header + tag + ByteArray(100)
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun synchsafe(v: Int) = byteArrayOf(
        (v shr 21 and 0x7F).toByte(),
        (v shr 14 and 0x7F).toByte(),
        (v shr 7 and 0x7F).toByte(),
        (
            v and
                0x7F
            ).toByte()
    )

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun oggPage(packets: List<ByteArray>): ByteArray {
        val lacing = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (p in packets) {
            var left = p.size
            while (left >= 255) {
                lacing.write(255)
                left -= 255
            }
            lacing.write(left)
            body.write(p)
        }
        val l = lacing.toByteArray()
        return "OggS".toByteArray() + ByteArray(22) + byteArrayOf(l.size.toByte()) + l + body.toByteArray()
    }

    private fun opusFile(vararg pictures: Pair<Int, ByteArray>): ByteArray {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            val vendor = "test".toByteArray()
            write(le32(vendor.size))
            write(vendor)
            val comments = listOf("TITLE=x".toByteArray()) +
                pictures.map { (t, d) ->
                    ("METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(pictureStruct(t, d))).toByteArray()
                }
            write(le32(comments.size))
            comments.forEach {
                write(le32(it.size))
                write(it)
            }
        }.toByteArray()
        return oggPage(listOf(head)) + oggPage(listOf(tags))
    }

    @Test fun `flac prefers the front cover over an earlier back cover`() {
        val pics = EmbeddedPictures.flac(flacFile(4 to back, 3 to front).inputStream())
        assertEquals(2, pics.size)
        assertArrayEquals(front, EmbeddedPictures.pick(pics)!!.data)
    }

    @Test fun `without a front cover the first picture is used`() {
        val pics = EmbeddedPictures.flac(flacFile(4 to back, 8 to front).inputStream())
        assertArrayEquals(back, EmbeddedPictures.pick(pics)!!.data)
        assertNull(EmbeddedPictures.pick(emptyList()))
    }

    @Test fun `id3v2_3 APIC frames are read with their picture type`() {
        val pics = EmbeddedPictures.id3(id3v23(4 to back, 3 to front).inputStream())
        assertEquals(listOf(4, 3), pics.map { it.type })
        assertArrayEquals(front, EmbeddedPictures.pick(pics)!!.data)
        assertEquals("image/jpeg", pics[0].mime)
    }

    @Test fun `opus METADATA_BLOCK_PICTURE comments are read, even across 255-byte lacing`() {
        val big = ByteArray(1000) { it.toByte() }
        val pics = EmbeddedPictures.ogg(opusFile(0 to back, 3 to big).inputStream())
        assertEquals(2, pics.size)
        assertArrayEquals(big, EmbeddedPictures.pick(pics)!!.data)
    }

    @Test fun `non-matching or truncated input yields no pictures instead of throwing`() {
        assertTrue(EmbeddedPictures.flac("RIFF0000WAVE".toByteArray().inputStream()).isEmpty())
        assertTrue(EmbeddedPictures.id3("xxxxxxxxxxxxx".toByteArray().inputStream()).isEmpty())
        assertTrue(EmbeddedPictures.ogg(ByteArray(3).inputStream()).isEmpty())
    }
}
