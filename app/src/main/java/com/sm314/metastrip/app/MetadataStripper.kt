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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.sm314.metastrip.core.HeifStripper
import com.sm314.metastrip.core.JpegStripper
import com.sm314.metastrip.core.PngStripper
import com.sm314.metastrip.core.StripException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Strips metadata from an image and saves a new file.
 *
 * Lossless formats (PNG, WebP, GIF, BMP) are always re-encoded to a
 * lossless output, so nothing is lost and no metadata can survive.
 *
 * Lossy formats (JPEG, HEIC, AVIF) follow the "re-encode" setting:
 *
 *  ON  (default): decode to raw pixels and write a fresh JPEG at quality 95.
 *      Only pixel data is written, so every kind of metadata is gone.
 *      EXIF orientation is applied during decoding.
 *
 *  OFF: keep the compressed pixel data byte for byte and drop only the
 *      metadata blocks. No quality loss. For JPEG the orientation tag is
 *      lost too, so sideways photos may display sideways. HEIC and AVIF
 *      store rotation as an image property, which survives.
 */
object MetadataStripper {

    enum class Method { REENCODED, LOSSLESS }
    enum class Kind { JPEG, PNG, WEBP, HEIF, AVIF, OTHER }

    data class Result(val uri: Uri, val mimeType: String, val fileName: String, val method: Method)

    private class Output(
        val mimeType: String, val extension: String, val method: Method,
        val write: (OutputStream) -> Unit
    )

    /** Refuse absurd inputs before allocating for them. */
    private const val MAX_INPUT_BYTES = 256L * 1024 * 1024

    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val RANDOM_NAME_LENGTH = 13
    private val random = SecureRandom()

    @Throws(IOException::class)
    fun strip(context: Context, source: Uri, settings: Settings): Result {
        checkSize(context, source)
        val kind = detectKind(context, source)

        if (kind == Kind.AVIF && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && settings.reencode) {
            throw IOException("AVIF re-encoding needs Android 12 or newer. Turn off re-encoding to strip AVIF losslessly.")
        }

        val lossyInput = kind == Kind.JPEG || kind == Kind.HEIF || kind == Kind.AVIF
        val output = if (lossyInput && !settings.reencode) {
            losslessOutput(context, source, kind) ?: reencodeOutput(context, source, kind)
        } else {
            reencodeOutput(context, source, kind)
        }

        val baseName = if (settings.randomFileName) randomName() else cleanName(context, source)
        val fileName = baseName + "." + output.extension

        val treeUri = settings.outputTreeUri
        val (uri, finalName) = if (treeUri != null) {
            saveToCustomFolder(context, treeUri, fileName, output)
        } else {
            saveToMediaStore(context, fileName, output)
        }
        return Result(uri, output.mimeType, finalName, output.method)
    }

    private fun checkSize(context: Context, source: Uri) {
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (length > MAX_INPUT_BYTES) throw IOException("Image is larger than 256 MB")
    }

    // ---------- Format detection ----------

    /** Sniffs the file header first, then falls back to the MIME type the provider reports. */
    private fun detectKind(context: Context, source: Uri): Kind {
        val head = ByteArray(16)
        val n = context.contentResolver.openInputStream(source)?.use { it.read(head) } ?: -1
        if (n >= 12) {
            if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return Kind.JPEG
            if (head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte()) return Kind.PNG
            if (ascii(head, 0, 4) == "RIFF" && ascii(head, 8, 4) == "WEBP") return Kind.WEBP
            if (ascii(head, 4, 4) == "ftyp") {
                val brand = ascii(head, 8, 4)
                return if (brand.startsWith("avi")) Kind.AVIF else Kind.HEIF
            }
        }
        return when (context.contentResolver.getType(source)?.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> Kind.JPEG
            "image/png" -> Kind.PNG
            "image/webp" -> Kind.WEBP
            "image/heic", "image/heif" -> Kind.HEIF
            "image/avif" -> Kind.AVIF
            else -> Kind.OTHER
        }
    }

    private fun ascii(b: ByteArray, from: Int, len: Int) = String(b, from, len, Charsets.ISO_8859_1)

    // ---------- Building the output ----------

    private data class Encoding(
        val format: Bitmap.CompressFormat, val quality: Int, val mime: String, val ext: String
    )

    private fun reencodeOutput(context: Context, source: Uri, kind: Kind): Output {
        val decoderSource = ImageDecoder.createSource(context.contentResolver, source)
        val bitmap = ImageDecoder.decodeBitmap(decoderSource) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // needed for compress()
            decoder.isMutableRequired = false
            // Convert to sRGB so the encoder has no reason to embed an ICC profile.
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val enc = when (kind) {
            Kind.PNG, Kind.OTHER -> Encoding(Bitmap.CompressFormat.PNG, 100, "image/png", "png")   // GIF, BMP: lossless
            Kind.WEBP -> Encoding(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, "image/webp", "webp")
            Kind.JPEG, Kind.HEIF, Kind.AVIF -> Encoding(Bitmap.CompressFormat.JPEG, 95, "image/jpeg", "jpg")
        }
        // Encode to memory, then run the lossless stripper over the encoder's own
        // output. The platform encoder writes only JFIF/ICC/sRGB blocks, but this
        // makes the guarantee independent of encoder behaviour.
        val encoded = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(enc.format, enc.quality, encoded)) throw IOException("Encoding failed")
        } finally {
            bitmap.recycle()
        }
        val bytes = try {
            when (enc.format) {
                Bitmap.CompressFormat.JPEG -> JpegStripper.strip(encoded.toByteArray())
                Bitmap.CompressFormat.PNG -> PngStripper.strip(encoded.toByteArray())
                else -> encoded.toByteArray()
            }
        } catch (e: StripException) {
            throw IOException(e.message)
        }
        return Output(enc.mime, enc.ext, Method.REENCODED) { it.write(bytes) }
    }

    /** Returns null when the file cannot be handled losslessly (e.g. a HEIF image sequence). */
    private fun losslessOutput(context: Context, source: Uri, kind: Kind): Output? {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw IOException("Could not read image")
        return when (kind) {
            Kind.JPEG -> {
                val stripped = try { JpegStripper.strip(bytes) } catch (e: StripException) { throw IOException(e.message) }
                Output("image/jpeg", "jpg", Method.LOSSLESS) { it.write(stripped) }
            }
            Kind.HEIF, Kind.AVIF -> {
                // A container we cannot handle (image sequence, odd layout) falls back to re-encoding.
                val stripped = try { HeifStripper.strip(bytes) } catch (e: StripException) { return null }
                val mime = if (kind == Kind.AVIF) "image/avif" else "image/heic"
                val ext = if (kind == Kind.AVIF) "avif" else "heic"
                Output(mime, ext, Method.LOSSLESS) { it.write(stripped) }
            }
            else -> null
        }
    }

    // ---------- Naming ----------

    private fun randomName(): String = buildString {
        repeat(RANDOM_NAME_LENGTH) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) }
    }

    /** Original name with the extension removed, unsafe characters replaced, and "_clean" added. */
    private fun cleanName(context: Context, source: Uri): String {
        val original = context.contentResolver
            .query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val stem = original?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.trim('_', '.')
        val safeStem = if (stem.isNullOrBlank()) "image_" + timestamp() else stem
        return safeStem + "_clean"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ---------- Saving ----------

    private fun saveToMediaStore(context: Context, fileName: String, output: Output): Pair<Uri, String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, output.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Settings.DEFAULT_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create output file")
        try {
            resolver.openOutputStream(uri)?.use(output.write)
                ?: throw IOException("Could not open output stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri to fileName
    }

    private fun saveToCustomFolder(
        context: Context, treeUri: Uri, fileName: String, output: Output
    ): Pair<Uri, String> {
        val dir = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Output folder is not available")
        if (!dir.canWrite()) {
            throw IOException("No write access to the output folder. Choose it again in Settings.")
        }
        val file = dir.createFile(output.mimeType, fileName)
            ?: throw IOException("Could not create file in the output folder")
        try {
            context.contentResolver.openOutputStream(file.uri)?.use(output.write)
                ?: throw IOException("Could not open output stream")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file.uri to (file.name ?: fileName)
    }
}
