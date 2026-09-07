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
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import java.util.Locale

/**
 * Reads whatever a content provider is willing to say about the chosen file.
 *
 * The photo picker does not carry original file names. Its _display_name is
 * built in SQL as "<media-id>.<extension>" and its _data is a synthetic path
 * ending in that same string, so neither the provider nor the file descriptor
 * can hand the real name back. Recovering it would mean holding
 * READ_MEDIA_IMAGES and looking the id up in MediaStore, which this app does
 * not do. A file shared in from a gallery arrives on an ordinary MediaStore
 * URI and does carry its real name.
 *
 * Size, MIME type and dimensions are real on both paths. The picker reports
 * dimensions from its own database rather than by decoding, so they are
 * available even for an image Android has no decoder for.
 */
internal object SourceFile {

    private data class Info(val name: String?, val size: Long, val width: Int, val height: Int)

    /** The provider's real name for the file, or null when it only offers a synthetic one. */
    fun displayName(context: Context, uri: Uri): String? = query(context, uri)?.name

    /**
     * A label for the picked file: its real name when there is one, otherwise
     * the format, dimensions and size, which is what identifies a file the
     * picker has anonymized.
     */
    fun describe(context: Context, uri: Uri): String? {
        val info = query(context, uri) ?: return null
        info.name?.let { return it }
        val format = context.contentResolver.getType(uri)
            ?.substringAfterLast('/')?.uppercase(Locale.US)
        val dimensions =
            if (info.width > 0 && info.height > 0) "${info.width} × ${info.height}" else null
        val size = if (info.size > 0) Formatter.formatShortFileSize(context, info.size) else null
        return listOfNotNull(format, dimensions, size).joinToString(" · ").ifBlank { null }
    }

    private fun query(context: Context, uri: Uri): Info? = runCatching {
        // A null projection asks for every column the provider supports, so
        // one query covers the picker, MediaStore and a plain FileProvider.
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Info(
                name = c.string(OpenableColumns.DISPLAY_NAME)
                    ?.takeIf { it.isNotBlank() }
                    // The picker builds "<id>.<ext>" from the URI's own id, so a
                    // stem equal to that id is synthetic, not a name the user knows.
                    ?.takeIf { it.substringBeforeLast('.') != uri.lastPathSegment },
                size = c.long(OpenableColumns.SIZE),
                width = c.long(MediaStore.MediaColumns.WIDTH).toInt(),
                height = c.long(MediaStore.MediaColumns.HEIGHT).toInt()
            )
        }
    }.getOrNull()

    /** Column lookups are by name: a provider may ignore the projection and return its own set. */
    private fun Cursor.string(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    private fun Cursor.long(column: String): Long {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else 0L
    }
}
