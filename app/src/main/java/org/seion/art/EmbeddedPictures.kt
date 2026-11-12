package org.seion.art

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * Embedded pictures with their picture type, so the front cover (type 3) wins over a back cover or booklet page.
 * `MediaMetadataRetriever.getEmbeddedPicture()` returns "any one" picture, so FLAC (`PICTURE` blocks), MP3 (ID3v2.3/2.4
 * `APIC` frames) and Ogg Vorbis/Opus (`METADATA_BLOCK_PICTURE` comments) are read here; MP4 (`covr`, no picture type)
 * and anything unparsable fall back to the retriever. Pure JVM code, unit-tested.
 */
object EmbeddedPictures {
    const val FRONT_COVER = 3
    private const val MAX_PICTURE = 16 * 1024 * 1024

    class Picture(val type: Int, val mime: String, val data: ByteArray)

    /** Front cover first, else the first picture. */
    fun pick(pictures: List<Picture>): Picture? = pictures.firstOrNull { it.type == FRONT_COVER } ?: pictures.firstOrNull()

    /** The best embedded picture, or null when the container is not handled here or holds none. */
    fun read(file: File): ByteArray? = runCatching {
        when (file.extension.lowercase()) {
            "flac" -> file.inputStream().buffered().use { pick(flac(it)) }
            "mp3" -> file.inputStream().buffered().use { pick(id3(it)) }
            "ogg", "oga", "opus" -> file.inputStream().buffered().use { pick(ogg(it)) }
            else -> null
        }?.data
    }.getOrNull()

    /** Whether [read] understands this file type (otherwise use the platform retriever). */
    fun handles(file: File): Boolean = file.extension.lowercase() in setOf("flac", "mp3", "ogg", "oga", "opus")

    // ------------------------------------------------------------------ FLAC (RFC 9639 §8.8)

    fun flac(input: InputStream): List<Picture> {
        val d = DataInputStream(input)
        val magic = ByteArray(4).also(d::readFully)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return emptyList()
        val out = ArrayList<Picture>()
        while (true) {
            val header = d.readUnsignedByte()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            if (type == 6) {
                out += pictureBlock(ByteArray(length).also(d::readFully))
            } else {
                d.skipFully(length)
            }
            if (last) break
        }
        return out
    }

    /** The FLAC picture structure (also base64 inside Vorbis comments). Big-endian. */
    fun pictureBlock(b: ByteArray): Picture {
        val d = DataInputStream(b.inputStream())
        val type = d.readInt()
        val mime = String(ByteArray(d.readInt().checkLen()).also(d::readFully), Charsets.US_ASCII)
        d.skipFully(d.readInt().checkLen()) // description
        d.skipFully(16) // width, height, depth, colours
        val data = ByteArray(d.readInt().checkLen()).also(d::readFully)
        return Picture(type, mime, data)
    }

    // ------------------------------------------------------------------ ID3v2.3 / 2.4 (APIC)

    fun id3(input: InputStream): List<Picture> {
        val d = DataInputStream(input)
        val header = ByteArray(10).also(d::readFully)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return emptyList()
        val major = header[3].toInt()
        val flags = header[5].toInt()
        if (major !in 3..4 || flags and 0x80 != 0) return emptyList() // v2.2 or unsynchronised: use the retriever
        val tagSize = synchsafe(header, 6)
        val tag = ByteArray(tagSize).also(d::readFully)
        var pos = 0
        if (flags and 0x40 != 0) { // extended header
            val ext = if (major == 4) synchsafe(tag, 0) else (int32(tag, 0) + 4)
            pos += ext
        }
        val out = ArrayList<Picture>()
        while (pos + 10 <= tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) synchsafe(tag, pos + 4) else int32(tag, pos + 4)
            val body = pos + 10
            if (size <= 0 || body + size > tag.size) break
            if (id == "APIC") apic(tag, body, size)?.let(out::add)
            pos = body + size
        }
        return out
    }

    private fun apic(b: ByteArray, start: Int, size: Int): Picture? {
        val end = start + size
        val encoding = b[start].toInt()
        var p = start + 1
        val mimeEnd = indexOf(b, p, end, 1) ?: return null
        val mime = String(b, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1
        if (p >= end) return null
        val type = b[p].toInt() and 0xFF
        p++
        val wide = encoding == 1 || encoding == 2
        val descEnd = indexOf(b, p, end, if (wide) 2 else 1) ?: return null
        p = descEnd + if (wide) 2 else 1
        if (mime == "-->" || p >= end) return null // a URL, not image data
        return Picture(type, mime, b.copyOfRange(p, end))
    }

    // ------------------------------------------------------------------ Ogg Vorbis / Opus comments

    fun ogg(input: InputStream): List<Picture> {
        val d = DataInputStream(input)
        val packet = java.io.ByteArrayOutputStream()
        var packetIndex = 0
        while (packetIndex < 2) {
            val magic = ByteArray(4)
            try {
                d.readFully(magic)
            } catch (_: EOFException) {
                return emptyList()
            }
            if (String(magic, Charsets.ISO_8859_1) != "OggS") return emptyList()
            d.skipFully(22) // version, type, granule, serial, sequence, CRC
            val segments = d.readUnsignedByte()
            val lacing = IntArray(segments) { d.readUnsignedByte() }
            for (len in lacing) {
                if (packetIndex == 1) {
                    packet.write(ByteArray(len).also(d::readFully))
                    if (packet.size() > MAX_PICTURE) return emptyList()
                } else {
                    d.skipFully(len)
                }
                if (len < 255) {
                    packetIndex++
                    if (packetIndex == 2) break
                }
            }
        }
        return vorbisComments(packet.toByteArray())
    }

    /** A Vorbis comment packet ("\u0003vorbis" or "OpusTags" header) -> its METADATA_BLOCK_PICTURE pictures. */
    fun vorbisComments(p: ByteArray): List<Picture> {
        val skip =
            when {
                p.size > 7 && p[0].toInt() == 3 && String(p, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
                p.size > 8 && String(p, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
                else -> return emptyList()
            }
        var pos = skip
        val vendor = le32(p, pos)
        pos += 4 + vendor
        val count = le32(p, pos)
        pos += 4
        val out = ArrayList<Picture>()
        repeat(count) {
            if (pos + 4 > p.size) return out
            val len = le32(p, pos)
            pos += 4
            if (len < 0 || pos + len > p.size) return out
            val eq = indexOf(p, pos, pos + len, 1, '='.code.toByte())
            if (eq != null && String(p, pos, eq - pos, Charsets.US_ASCII).equals("METADATA_BLOCK_PICTURE", ignoreCase = true)) {
                runCatching { pictureBlock(Base64.getDecoder().decode(p.copyOfRange(eq + 1, pos + len))) }.getOrNull()?.let(out::add)
            }
            pos += len
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun Int.checkLen(): Int = if (this < 0 || this > MAX_PICTURE) throw IOException("bad length") else this

    private fun DataInputStream.skipFully(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= s
            }
        }
    }

    private fun synchsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
        ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Index of the first terminator (one zero byte, or two aligned zero bytes when [width] is 2), or [value]. */
    private fun indexOf(b: ByteArray, from: Int, to: Int, width: Int, value: Byte = 0): Int? {
        var i = from
        while (i + width - 1 < to) {
            if (width == 1 && b[i] == value) return i
            if (width == 2 && b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
            i += width
        }
        return null
    }
}
