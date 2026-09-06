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

/*
 * This package is the platform-neutral core. Rule: nothing in here may
 * import android.* or java.*. Only the Kotlin standard library. That keeps
 * the strippers ready for Kotlin Multiplatform and testable on a plain JVM.
 */

/** Thrown when a file cannot be parsed or stripped. */
class StripException(message: String) : Exception(message)

/** Minimal growable byte buffer. Replaces java.io.ByteArrayOutputStream. */
class ByteSink(initialCapacity: Int = 4096) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))
    var size = 0
        private set

    fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length <= 0) return
        ensure(length)
        src.copyInto(buf, size, offset, offset + length)
        size += length
    }

    fun writeU16(v: Long) { write((v shr 8).toInt() and 0xFF); write(v.toInt() and 0xFF) }
    fun writeU32(v: Long) { writeU16((v shr 16) and 0xFFFF); writeU16(v and 0xFFFF) }
    fun writeAscii(s: String) { for (ch in s) write(ch.code and 0xFF) }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed <= buf.size) return
        if (needed < 0) throw StripException("Output too large")
        var cap = buf.size.toLong()
        while (cap < needed) cap *= 2
        buf = buf.copyOf(cap.coerceAtMost(Int.MAX_VALUE - 8L).toInt())
    }
}

/**
 * Runs a parser over untrusted bytes. Any index or arithmetic failure on a
 * corrupt file becomes a StripException, so callers only have one error
 * type to handle and can fall back cleanly.
 */
internal inline fun <T> guarded(what: String, block: () -> T): T = try {
    block()
} catch (e: StripException) {
    throw e
} catch (e: RuntimeException) {
    throw StripException("Corrupt $what file: ${e::class.simpleName}")
}

/** Reads 4 bytes as a 4-character ASCII tag such as "ftyp" or "IHDR". */
internal fun ByteArray.tag(p: Int): String {
    val sb = StringBuilder(4)
    for (i in 0 until 4) sb.append((this[p + i].toInt() and 0xFF).toChar())
    return sb.toString()
}
