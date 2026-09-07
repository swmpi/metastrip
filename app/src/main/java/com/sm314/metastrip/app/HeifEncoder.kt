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
package com.sm314.metastrip.app

import android.content.Context
import android.graphics.Bitmap
import android.media.HeifWriter
import java.io.File

/**
 * Writes a bitmap out as HEIC, so a HEIC input does not have to become a JPEG
 * when it is re-encoded.
 *
 * Bitmap.compress() cannot produce HEIC; the platform only offers JPEG, PNG
 * and WebP. HeifWriter goes through the device HEVC encoder instead.
 *
 * Two things make this best effort rather than guaranteed:
 *
 *  - HeifWriter writes to a file or file descriptor, never to a stream, so the
 *    result is staged in the app cache and read back. The temp file lives in
 *    the app's private cache directory and is always deleted, including on
 *    failure.
 *  - Not every device has a usable HEVC encoder, and encoders have their own
 *    limits on image dimensions. Anything that goes wrong returns null so the
 *    caller can fall back to JPEG.
 *
 * The bitmap is already upright (ImageDecoder applies EXIF orientation while
 * decoding) and already sRGB, and a fresh encode carries no metadata.
 */
object HeifEncoder {

    private const val STOP_TIMEOUT_MS = 30_000L

    /** Returns the encoded HEIC, or null if this device cannot produce one. */
    fun encode(context: Context, bitmap: Bitmap, quality: Int): ByteArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val temp = File.createTempFile("heif", ".heic", context.cacheDir)
        try {
            var writer: HeifWriter? = null
            try {
                writer = HeifWriter.Builder(
                    temp.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP
                ).setQuality(quality).setMaxImages(1).build()

                writer.start()
                writer.addBitmap(bitmap)
                writer.stop(STOP_TIMEOUT_MS)
            } finally {
                runCatching { writer?.close() }
            }

            val bytes = temp.readBytes()
            return if (bytes.isEmpty()) null else bytes
        } catch (e: Exception) {
            // No HEVC encoder, unsupported dimensions, encoder timeout, and so
            // on. The caller falls back to JPEG.
            return null
        } finally {
            temp.delete()
        }
    }
}
