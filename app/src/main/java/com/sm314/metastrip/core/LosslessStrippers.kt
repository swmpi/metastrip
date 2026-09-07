/*
 * MetaStrip - removes all metadata from image files.
 * Copyright (C) 2026  sm314.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.sm314.metastrip.core

/**
 * Removes metadata from a JPEG without touching the compressed pixel data.
 *
 * A JPEG is a list of segments. Every application segment (APP0 to APP15,
 * markers 0xE0 to 0xEF) and the comment segment (0xFE) is dropped. That
 * covers JFIF, EXIF, XMP, ICC profiles, IPTC/Photoshop blocks, thumbnails
 * and vendor data. Only the segments needed to decode the picture are kept.
 */
object JpegStripper {

    fun strip(data: ByteArray): ByteArray = guarded("JPEG") { stripInner(data) }

    private fun stripInner(data: ByteArray): ByteArray {
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            throw StripException("Not a JPEG file")
        }
        val out = ByteSink(data.size)
        out.write(0xFF); out.write(0xD8)

        var i = 2
        var wroteEoi = false
        while (i < data.size) {
            if (data[i] != 0xFF.toByte()) { i++; continue }
            while (i < data.size && data[i] == 0xFF.toByte()) i++   // skip fill bytes
            if (i >= data.size) break
            val marker = data[i].toInt() and 0xFF
            i++

            when {
                marker == 0xD9 -> { out.write(0xFF); out.write(0xD9); wroteEoi = true; i = data.size }
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
                    out.write(0xFF); out.write(marker)      // standalone marker, no length
                }
                else -> {
                    if (i + 2 > data.size) break
                    val len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                    val segEnd = (i + len).coerceAtMost(data.size)
                    // APP14 "Adobe" only holds the colour transform flag. Dropping it
                    // would make RGB or CMYK JPEGs decode with wrong colours.
                    val adobe = marker == 0xEE && len >= 7 &&
                        data.tag(i + 2) == "Adob" && i + 6 < data.size && data[i + 6] == 'e'.code.toByte()
                    // APP2 "ICC_PROFILE" is the colour profile. This is the
                    // lossless path, where the picture must look exactly as it
                    // did, and dropping the profile would shift the colours of
                    // a wide gamut photo. A profile describes a colour space,
                    // not a person or a place.
                    val icc = marker == 0xE2 && len >= 14 &&
                        i + 13 < data.size && data.tag(i + 2) == "ICC_"
                    val drop = (marker in 0xE0..0xEF && !adobe && !icc) || marker == 0xFE
                    if (!drop) {
                        out.write(0xFF); out.write(marker)
                        out.write(data, i, segEnd - i)
                    }
                    i = segEnd

                    if (marker == 0xDA) {
                        // Entropy coded scan: copy until the next real marker.
                        val start = i
                        while (i < data.size) {
                            if (data[i] == 0xFF.toByte() && i + 1 < data.size) {
                                val next = data[i + 1].toInt() and 0xFF
                                if (next != 0x00 && next !in 0xD0..0xD7) break
                            }
                            i++
                        }
                        out.write(data, start, i - start)
                    }
                }
            }
        }
        if (!wroteEoi) { out.write(0xFF); out.write(0xD9) }
        return out.toByteArray()
    }
}

/**
 * Removes metadata from a PNG without touching the compressed pixel data.
 *
 * Only chunks needed to decode and display the image correctly are kept.
 * Text chunks, timestamps, EXIF, ICC profiles and anything unknown are
 * dropped. APNG animation chunks are kept so animated files still work.
 */
object PngStripper {

    private val keep = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS",
        "gAMA", "cHRM", "sRGB", "sBIT", "bKGD",
        "acTL", "fcTL", "fdAT"
    )
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun strip(data: ByteArray): ByteArray = guarded("PNG") { stripInner(data) }

    private fun stripInner(data: ByteArray): ByteArray {
        if (data.size < 8 || !data.copyOf(8).contentEquals(signature)) {
            throw StripException("Not a PNG file")
        }
        val out = ByteSink(data.size)
        out.write(signature)

        var i = 8
        while (i + 8 <= data.size) {
            val len = ((data[i].toLong() and 0xFF) shl 24) or
                ((data[i + 1].toLong() and 0xFF) shl 16) or
                ((data[i + 2].toLong() and 0xFF) shl 8) or
                (data[i + 3].toLong() and 0xFF)
            val type = data.tag(i + 4)
            val total = 12L + len
            if (i + total > data.size) throw StripException("Corrupt PNG chunk")
            if (type in keep) out.write(data, i, total.toInt())
            i += total.toInt()
            if (type == "IEND") break
        }
        return out.toByteArray()
    }
}
