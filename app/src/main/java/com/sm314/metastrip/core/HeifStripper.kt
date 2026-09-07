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
 * Removes metadata from a HEIF container (HEIC, AVIF) without touching
 * the compressed image data.
 *
 * HEIF stores each piece of a file as an "item". The picture is one item,
 * the EXIF block is another, XMP is a third. This stripper:
 *
 *  1. finds every item whose type is Exif or whose MIME type is XMP, plus
 *     every thumbnail item (a thumbnail can show what a later crop hid),
 *  2. removes those items from the item list (iinf), the location table
 *     (iloc), the reference table (iref) and the property map (ipma),
 *  3. writes a brand new mdat box that contains only the bytes of the
 *     items that survive, so the metadata bytes are physically gone,
 *  4. rewrites iloc with the new byte offsets,
 *  5. drops xml, bxml and uuid boxes inside meta and blanks any udes
 *     (user description) property, which can carry names and tags.
 *
 * Rotation in HEIF is a property (irot) on the image item, not an EXIF
 * tag, so orientation survives this process.
 *
 * Image sequences (files with a moov box) are refused; the caller should
 * fall back to re-encoding.
 */
object HeifStripper {

    private class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val payload get() = start + header
        val end get() = start + size
    }

    private class Item(val id: Long, val type: String, val contentType: String?)
    private class Extent(val index: Long, val offset: Long, val length: Long)
    private class LocEntry(
        val id: Long, val method: Int, val dataRef: Int, val base: Long, val extents: List<Extent>
    )

    fun strip(data: ByteArray): ByteArray = guarded("HEIF") { stripInner(data) }

    private fun stripInner(data: ByteArray): ByteArray {
        val top = parseBoxes(data, 0, data.size)
        val ftyp = top.firstOrNull { it.type == "ftyp" } ?: throw StripException("Not a HEIF file")
        if (top.any { it.type == "moov" }) throw StripException("HEIF image sequences are not supported")
        val meta = top.firstOrNull { it.type == "meta" } ?: throw StripException("HEIF meta box missing")

        val metaChildren = parseBoxes(data, meta.payload + 4, meta.end)   // +4 skips version/flags
        val iinf = metaChildren.firstOrNull { it.type == "iinf" } ?: throw StripException("iinf missing")
        val iloc = metaChildren.firstOrNull { it.type == "iloc" } ?: throw StripException("iloc missing")

        val items = parseIinf(data, iinf)
        val remove = HashSet<Long>()
        items.filter { isMetadata(it) }.forEach { remove.add(it.id) }
        metaChildren.firstOrNull { it.type == "iref" }?.let { remove.addAll(thumbnailIds(data, it)) }
        val kept = parseIloc(data, iloc).filter { it.id !in remove }

        // Copy surviving file-based extents into a fresh mdat payload.
        val mdat = ByteSink(data.size)
        val relocated = kept.map { e ->
            if (e.method != 0) e else {
                val extents = e.extents.map { x ->
                    if (x.length == 0L) throw StripException("Unsupported HEIF extent")
                    val abs = e.base + x.offset
                    if (abs < 0 || abs + x.length > data.size) throw StripException("Corrupt HEIF extent")
                    val newOffset = mdat.size.toLong()
                    // Overlapping extents in a hostile file could otherwise inflate the output.
                    if (newOffset + x.length > data.size) throw StripException("HEIF extents exceed file size")
                    mdat.write(data, abs.toInt(), x.length.toInt())
                    Extent(x.index, newOffset, x.length)
                }
                LocEntry(e.id, 0, e.dataRef, 0, extents)
            }
        }

        // Pass 1: build meta with placeholder offsets to learn its size.
        val metaDraft = buildMeta(data, meta, metaChildren, remove, relocated, 0)
        val mdatPayloadStart = ftyp.size.toLong() + metaDraft.size + 8
        // Pass 2: same layout, real offsets. Sizes depend only on counts, so length is identical.
        val metaFinal = buildMeta(data, meta, metaChildren, remove, relocated, mdatPayloadStart)
        if (metaFinal.size != metaDraft.size) throw StripException("HEIF rebuild size mismatch")

        val out = ByteSink(ftyp.size + metaFinal.size + 8 + mdat.size)
        out.write(data, ftyp.start, ftyp.size)
        out.write(metaFinal)
        out.writeU32(8L + mdat.size)
        out.writeAscii("mdat")
        out.write(mdat.toByteArray())
        return out.toByteArray()
    }

    /** Items that are the source of a "thmb" reference are thumbnails of another item. */
    private fun thumbnailIds(data: ByteArray, iref: Box): Set<Long> {
        val version = u8(data, iref.payload)
        val idBytes = if (version == 0) 2 else 4
        return parseBoxes(data, iref.payload + 4, iref.end)
            .filter { it.type == "thmb" }
            .map { readSized(data, it.payload, idBytes) }
            .toSet()
    }

    private fun isMetadata(item: Item): Boolean {
        if (item.type == "Exif") return true
        val ct = item.contentType?.lowercase() ?: return false
        return ct.contains("xmp") || ct.contains("rdf+xml")
    }

    // ---------- Rebuilding ----------

    private fun buildMeta(
        data: ByteArray, meta: Box, children: List<Box>, remove: Set<Long>,
        locs: List<LocEntry>, mdatPayloadStart: Long
    ): ByteArray {
        val body = ByteSink()
        body.write(data, meta.payload, 4)   // version + flags
        for (child in children) {
            when (child.type) {
                "iinf" -> body.write(buildIinf(data, child, remove))
                "iloc" -> body.write(buildIloc(locs, mdatPayloadStart))
                "iref" -> buildIref(data, child, remove)?.let { body.write(it) }
                "iprp" -> body.write(buildIprp(data, child, remove))
                "xml ", "bxml", "uuid" -> Unit   // raw XML metadata or vendor blobs: drop
                else -> body.write(data, child.start, child.size)
            }
        }
        return box("meta", body.toByteArray())
    }

    private fun buildIinf(data: ByteArray, iinf: Box, remove: Set<Long>): ByteArray {
        val version = u8(data, iinf.payload)
        val countBytes = if (version == 0) 2 else 4
        val entries = parseBoxes(data, iinf.payload + 4 + countBytes, iinf.end)
        val keptEntries = entries.filter { it.type != "infe" || infeId(data, it) !in remove }

        val body = ByteSink()
        body.write(data, iinf.payload, 4)
        if (countBytes == 2) body.writeU16(keptEntries.size.toLong()) else body.writeU32(keptEntries.size.toLong())
        for (e in keptEntries) body.write(data, e.start, e.size)
        return box("iinf", body.toByteArray())
    }

    private fun buildIloc(locs: List<LocEntry>, mdatPayloadStart: Long): ByteArray {
        val version = if (locs.any { it.id > 0xFFFF }) 2 else 1
        val indexSize = if (locs.any { e -> e.extents.any { it.index != 0L } }) 4 else 0
        val body = ByteSink()
        body.write(version); body.write(0); body.write(0); body.write(0)
        body.write((4 shl 4) or 4)              // offset_size = 4, length_size = 4
        body.write((0 shl 4) or indexSize)      // base_offset_size = 0, index_size
        if (version < 2) body.writeU16(locs.size.toLong()) else body.writeU32(locs.size.toLong())
        for (e in locs) {
            if (version < 2) body.writeU16(e.id) else body.writeU32(e.id)
            body.writeU16(e.method.toLong())   // reserved(12) + construction_method(4)
            body.writeU16(e.dataRef.toLong())
            body.writeU16(e.extents.size.toLong())
            for (x in e.extents) {
                if (indexSize == 4) body.writeU32(x.index)
                val offset = if (e.method == 0) x.offset + mdatPayloadStart else x.offset
                if (offset > 0xFFFFFFFFL || x.length > 0xFFFFFFFFL) throw StripException("HEIF too large")
                body.writeU32(offset)
                body.writeU32(x.length)
            }
        }
        return box("iloc", body.toByteArray())
    }

    private fun buildIref(data: ByteArray, iref: Box, remove: Set<Long>): ByteArray? {
        val version = u8(data, iref.payload)
        val idBytes = if (version == 0) 2 else 4
        val body = ByteSink()
        body.write(data, iref.payload, 4)
        var wrote = 0
        for (ref in parseBoxes(data, iref.payload + 4, iref.end)) {
            var p = ref.payload
            val from = readSized(data, p, idBytes); p += idBytes
            if (from in remove) continue
            val count = u16(data, p); p += 2
            val to = (0 until count).map { i -> readSized(data, p + i * idBytes, idBytes) }
                .filter { it !in remove }
            if (to.isEmpty()) continue
            val rb = ByteSink()
            if (idBytes == 2) rb.writeU16(from) else rb.writeU32(from)
            rb.writeU16(to.size.toLong())
            for (t in to) if (idBytes == 2) rb.writeU16(t) else rb.writeU32(t)
            body.write(box(ref.type, rb.toByteArray()))
            wrote++
        }
        return if (wrote == 0) null else box("iref", body.toByteArray())
    }

    private fun buildIprp(data: ByteArray, iprp: Box, remove: Set<Long>): ByteArray {
        val body = ByteSink()
        for (child in parseBoxes(data, iprp.payload, iprp.end)) {
            when (child.type) {
                "ipma" -> body.write(buildIpma(data, child, remove))
                "ipco" -> body.write(buildIpco(data, child))
                else -> body.write(data, child.start, child.size)
            }
        }
        return box("iprp", body.toByteArray())
    }

    /**
     * Properties are referenced by position, so a property cannot simply be
     * deleted. A udes (user description: name, description, tags) is replaced
     * by a free box of the same size, which keeps every index valid.
     */
    /**
     * Properties are referenced by position, so a property cannot simply be
     * deleted without breaking every index in ipma. Each one that carries
     * metadata is replaced by a free box of the same size instead, which keeps
     * all indexes valid.
     *
     * Dropped:
     *  - udes: user description (name, description, tags)
     *  - altt, kmat, mint: accessibility text, matting and integrity blocks
     *    that can carry free text or hashes of the original.
     *
     * Kept on purpose:
     *  - colr: the colour profile. This is the lossless path, where the rule
     *    is that the picture must look exactly as it did. Removing an ICC
     *    profile would make a wide gamut photo display with the wrong
     *    colours. A profile names a colour space and its vendor, not the
     *    person or the place, so this costs nothing that matters.
     *    Re-encode mode converts to sRGB and writes no profile at all.
     */
    private fun buildIpco(data: ByteArray, ipco: Box): ByteArray {
        val body = ByteSink()
        for (prop in parseBoxes(data, ipco.payload, ipco.end)) {
            if (prop.type in droppedProperties) {
                body.writeU32(prop.size.toLong())
                body.writeAscii("free")
                repeat(prop.size - 8) { body.write(0) }
            } else {
                body.write(data, prop.start, prop.size)
            }
        }
        return box("ipco", body.toByteArray())
    }

    private val droppedProperties = setOf("udes", "altt", "kmat", "mint")

    private fun buildIpma(data: ByteArray, ipma: Box, remove: Set<Long>): ByteArray {
        val version = u8(data, ipma.payload)
        val flags = u32(data, ipma.payload).toInt() and 0xFFFFFF
        val idBytes = if (version == 0) 2 else 4
        val assocBytes = if (flags and 1 != 0) 2 else 1
        var p = ipma.payload + 4
        val count = u32(data, p); p += 4

        val entries = ByteSink()
        var kept = 0L
        for (i in 0 until count) {
            val id = readSized(data, p, idBytes)
            val n = u8(data, p + idBytes)
            val len = idBytes + 1 + n * assocBytes
            if (id !in remove) { entries.write(data, p, len); kept++ }
            p += len
        }
        val body = ByteSink()
        body.write(data, ipma.payload, 4)
        body.writeU32(kept)
        body.write(entries.toByteArray())
        return box("ipma", body.toByteArray())
    }

    // ---------- Parsing ----------

    private fun parseBoxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val boxes = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(data, p)
            val type = data.tag(p + 4)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) throw StripException("Corrupt HEIF box")
                size = u64(data, p + 8); header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) throw StripException("Corrupt HEIF box: $type")
            boxes.add(Box(type, p, size.toInt(), header))
            p += size.toInt()
        }
        return boxes
    }

    private fun parseIinf(data: ByteArray, iinf: Box): List<Item> {
        val version = u8(data, iinf.payload)
        val countBytes = if (version == 0) 2 else 4
        return parseBoxes(data, iinf.payload + 4 + countBytes, iinf.end)
            .filter { it.type == "infe" }
            .map { parseInfe(data, it) }
    }

    private fun infeId(data: ByteArray, infe: Box): Long {
        val version = u8(data, infe.payload)
        return readSized(data, infe.payload + 4, if (version >= 3) 4 else 2)
    }

    private fun parseInfe(data: ByteArray, infe: Box): Item {
        val version = u8(data, infe.payload)
        var p = infe.payload + 4
        val id: Long
        if (version >= 3) { id = u32(data, p); p += 4 } else { id = u16(data, p).toLong(); p += 2 }
        p += 2   // item_protection_index
        if (version >= 2) {
            val type = data.tag(p); p += 4
            val (_, afterName) = readString(data, p, infe.end); p = afterName
            val contentType = if (type == "mime") readString(data, p, infe.end).first else null
            return Item(id, type, contentType)
        }
        // Version 0/1: no item_type, but every item carries a content_type string.
        val (_, afterName) = readString(data, p, infe.end)
        val contentType = readString(data, afterName, infe.end).first
        return Item(id, "", contentType)
    }

    private fun parseIloc(data: ByteArray, iloc: Box): List<LocEntry> {
        var p = iloc.payload
        val version = u8(data, p); p += 4
        var b = u8(data, p); p++
        val offsetSize = b shr 4; val lengthSize = b and 15
        b = u8(data, p); p++
        val baseSize = b shr 4; val indexSize = if (version >= 1) b and 15 else 0
        val count: Long
        if (version < 2) { count = u16(data, p).toLong(); p += 2 } else { count = u32(data, p); p += 4 }

        val entries = ArrayList<LocEntry>()
        for (i in 0 until count) {
            val id: Long
            if (version < 2) { id = u16(data, p).toLong(); p += 2 } else { id = u32(data, p); p += 4 }
            var method = 0
            if (version >= 1) { method = u16(data, p) and 15; p += 2 }
            val dataRef = u16(data, p); p += 2
            val base = readSized(data, p, baseSize); p += baseSize
            val extCount = u16(data, p); p += 2
            val extents = ArrayList<Extent>()
            for (j in 0 until extCount) {
                var index = 0L
                if (version >= 1 && indexSize > 0) { index = readSized(data, p, indexSize); p += indexSize }
                val off = readSized(data, p, offsetSize); p += offsetSize
                val len = readSized(data, p, lengthSize); p += lengthSize
                extents.add(Extent(index, off, len))
            }
            entries.add(LocEntry(id, method, dataRef, base, extents))
        }
        return entries
    }

    // ---------- Byte helpers ----------

    private fun u8(d: ByteArray, p: Int) = d[p].toInt() and 0xFF
    private fun u16(d: ByteArray, p: Int) = (u8(d, p) shl 8) or u8(d, p + 1)
    private fun u32(d: ByteArray, p: Int): Long =
        (u16(d, p).toLong() shl 16) or u16(d, p + 2).toLong()
    private fun u64(d: ByteArray, p: Int): Long = (u32(d, p) shl 32) or u32(d, p + 4)

    private fun readSized(d: ByteArray, p: Int, size: Int): Long = when (size) {
        0 -> 0L
        2 -> u16(d, p).toLong()
        4 -> u32(d, p)
        8 -> u64(d, p)
        else -> throw StripException("Unsupported HEIF field size $size")
    }

    /** Reads a null-terminated UTF-8 string. Returns the string and the position after the terminator. */
    private fun readString(d: ByteArray, p: Int, limit: Int): Pair<String, Int> {
        var e = p
        while (e < limit && d[e] != 0.toByte()) e++
        return d.decodeToString(p, e) to (e + 1).coerceAtMost(limit)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val o = ByteSink(payload.size + 8)
        o.writeU32(8L + payload.size)
        o.writeAscii(type)
        o.write(payload)
        return o.toByteArray()
    }
}
