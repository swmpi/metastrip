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
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
 * lossless output, so nothing is lost and no metadata can survive. On
 * Android 10 a WebP input comes back as a PNG, because the lossless WebP
 * encoder only exists from Android 11 on.
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

    enum class Kind(val label: String) {
        JPEG("JPEG"), PNG("PNG"), WEBP("WebP"), HEIF("HEIC"), AVIF("AVIF"), OTHER("this format")
    }

    data class Result(val uri: Uri, val mimeType: String, val fileName: String, val method: Method)

    private class Output(
        val mimeType: String, val extension: String, val method: Method,
        val write: (OutputStream) -> Unit
    )

    /**
     * HEIC quality for re-encoded HEIC output. Matched to the JPEG setting so
     * the two paths are consistent; HEIC at this quality is still much smaller
     * than the equivalent JPEG.
     */
    private const val HEIC_QUALITY = 95

    /** Refuse absurd inputs before allocating for them. */
    private const val MAX_INPUT_BYTES = 256L * 1024 * 1024

    /**
     * Cap on decoded pixels for the re-encode path. A decoded ARGB bitmap
     * costs 4 bytes per pixel, so 64 MP is 256 MB, the practical ceiling even
     * with largeHeap. Lossless mode never decodes and has no such limit. A
     * tiny file can still declare huge dimensions (a decompression bomb), so
     * this is checked from the header before any pixel memory is allocated.
     */
    private const val MAX_PIXELS = 64L * 1_000_000

    private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789"
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
            losslessOutput(context, source, kind)
                ?: reencodeOutput(context, source, kind, afterLosslessFailed = true)
        } else {
            reencodeOutput(context, source, kind, afterLosslessFailed = false)
        }

        val baseName = if (settings.randomFileName) randomName() else cleanName(context, source)
        val fileName = baseName + "." + output.extension

        val treeUri = settings.outputTreeUri
        val (uri, finalName) = if (treeUri != null) {
            saveToCustomFolder(context, treeUri, fileName, output)
        } else {
            saveToMediaStore(context, fileName, output)
        }
        verifySaved(context, uri, output.mimeType)
        return Result(uri, output.mimeType, finalName, output.method)
    }

    /**
     * Re-opens the saved file and confirms it is non-empty and starts with the
     * right magic bytes for its type, so a silently truncated or empty write
     * is caught before the result is reported as a success.
     */
    @Throws(IOException::class)
    private fun verifySaved(context: Context, uri: Uri, mime: String) {
        val head = ByteArray(16)
        val n = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        val ok = n >= 12 && when (mime) {
            "image/jpeg" -> head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            "image/png" -> head[0] == 0x89.toByte() && ascii(head, 1, 3) == "PNG"
            "image/webp" -> ascii(head, 0, 4) == "RIFF" && ascii(head, 8, 4) == "WEBP"
            "image/heic", "image/avif" -> ascii(head, 4, 4) == "ftyp"
            else -> true
        }
        if (!ok) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw IOException("The saved file failed verification and was removed")
        }
    }

    private fun checkSize(context: Context, source: Uri) {
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (length > MAX_INPUT_BYTES) throw IOException("Image is larger than 256 MB")
    }

    /** Reads only the header, so a decompression bomb is rejected before it can allocate. */
    private fun checkPixels(context: Context, source: Uri) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth.toLong(); val h = opts.outHeight.toLong()
        if (w <= 0 || h <= 0) return   // unknown to BitmapFactory (e.g. AVIF on API < 31); ImageDecoder decides
        if (w * h > MAX_PIXELS) {
            throw IOException(
                "This image is ${w * h / 1_000_000} megapixels, above the ${MAX_PIXELS / 1_000_000} MP limit " +
                    "for re-encoding. Turn off re-encoding in Settings to strip it losslessly at any size."
            )
        }
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

    private fun reencodeOutput(
        context: Context, source: Uri, kind: Kind, afterLosslessFailed: Boolean
    ): Output {
        checkPixels(context, source)
        val decoderSource = ImageDecoder.createSource(context.contentResolver, source)
        val bitmap = try {
            ImageDecoder.decodeBitmap(decoderSource) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // needed for compress()
                decoder.isMutableRequired = false
                // Convert to sRGB so the encoder has no reason to embed an ICC profile.
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (e: Exception) {
            throw decodeFailed(kind, afterLosslessFailed, e)
        }

        // HEIC keeps its format when the device has an HEVC encoder, so a HEIC
        // photo is not silently turned into a much larger JPEG. AVIF cannot be
        // written by any platform API, so it still falls back to JPEG.
        if (kind == Kind.HEIF) {
            val heic = HeifEncoder.encode(context, bitmap, HEIC_QUALITY)
            // The stripper must be able to parse the encoder's output; if it
            // cannot, nothing has been verified, so fall back to JPEG instead
            // of writing bytes the app does not understand.
            val clean = heic?.let { runCatching { HeifStripper.strip(it) }.getOrNull() }
            if (clean != null) {
                bitmap.recycle()
                return Output("image/heic", "heic", Method.REENCODED) { it.write(clean) }
            }
            // No usable HEVC encoder, or unparseable output: fall through to JPEG.
        }

        val enc = when (kind) {
            Kind.PNG, Kind.OTHER -> Encoding(Bitmap.CompressFormat.PNG, 100, "image/png", "png")   // GIF, BMP: lossless
            Kind.WEBP -> webpEncoding()
            Kind.JPEG, Kind.HEIF, Kind.AVIF -> Encoding(Bitmap.CompressFormat.JPEG, 95, "image/jpeg", "jpg")
        }
        // Encode to memory, then run the lossless stripper over the encoder's own
        // output. The platform encoder writes only JFIF/ICC/sRGB blocks, but this
        // makes the guarantee independent of encoder behavior.
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

    /**
     * WEBP_LOSSLESS only exists from API 30, and minSdk is 29.
     *
     * The plain WEBP format is lossy, so using it on Android 10 would quietly
     * break the rule that a lossless input stays lossless. PNG is lossless
     * everywhere, so Android 10 gets a PNG instead: the container changes,
     * the pixels do not.
     */
    private fun webpEncoding(): Encoding =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Encoding(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, "image/webp", "webp")
        } else {
            Encoding(Bitmap.CompressFormat.PNG, 100, "image/png", "png")
        }

    /**
     * Turns a decoder failure into something the user can act on.
     *
     * ImageDecoder reports these as raw Skia strings such as "getPixels failed
     * with error invalid input", which say nothing about the cause or the fix.
     *
     * For AVIF the usual cause is an AV1 profile Android does not implement.
     * Every Android AV1 decoder, hardware and software alike, is Main profile
     * only: 8- or 10-bit 4:2:0. Files using 4:4:4, 4:2:2 or 12-bit sit in the
     * High and Professional profiles and are refused on every Android version,
     * so telling the user to update is wrong. Lossless mode never decodes, so
     * it still handles these files and keeps them as AVIF.
     */
    private fun decodeFailed(kind: Kind, afterLosslessFailed: Boolean, cause: Exception): IOException {
        val advice = if (afterLosslessFailed) {
            "Its structure could not be read for lossless stripping either, so MetaStrip cannot handle this file."
        } else {
            "Turn off re-encoding in Settings to strip it losslessly and keep it as ${kind.label}."
        }
        val message = when (kind) {
            Kind.AVIF ->
                "Android cannot decode this AVIF. It supports only 8-bit and 10-bit 4:2:0 AVIF; " +
                    "4:4:4, 4:2:2 and 12-bit files are refused on every Android version. $advice"
            // JPEG and HEIC also have a lossless path, so the same advice applies.
            Kind.HEIF, Kind.JPEG -> "Android cannot decode this ${kind.label}. $advice"
            // PNG, WebP and the rest have no lossless path to fall back to.
            else -> {
                val detail = cause.message?.takeIf { it.isNotBlank() }
                if (detail != null) "This image could not be decoded ($detail)."
                else "This image could not be decoded."
            }
        }
        return IOException(message, cause)
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

    /**
     * Original name with the extension removed, unsafe characters replaced,
     * and "_clean" added. Falls back to a timestamp when the provider has no
     * real name to give, which is the case for everything the photo picker
     * hands over; naming the output after the picker's internal id would be
     * worse than not using the original name at all.
     */
    private fun cleanName(context: Context, source: Uri): String {
        val original = SourceFile.displayName(context, source)
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
