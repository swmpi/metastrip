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
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** Thin wrapper over the default SharedPreferences used by the settings screen. */
class Settings(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val randomFileName: Boolean
        get() = prefs.getBoolean(KEY_RANDOM_NAME, true)

    val reencode: Boolean
        get() = prefs.getBoolean(KEY_REENCODE, true)

    /** Off by default. Deleting is destructive and always needs the user to confirm. */
    val deleteOriginal: Boolean
        get() = prefs.getBoolean(KEY_DELETE_ORIGINAL, false)

    /** A tree URI from the system folder picker, or null for the default folder. */
    var outputTreeUri: Uri?
        get() = prefs.getString(KEY_OUTPUT_DIR, null)?.let(Uri::parse)
        set(value) = prefs.edit { putString(KEY_OUTPUT_DIR, value?.toString()) }

    /** Human readable label of where files are saved. */
    fun outputDirLabel(): String {
        val uri = outputTreeUri ?: return DEFAULT_DIR_LABEL
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val volume = if (parts[0] == "primary") "Internal storage" else "Storage ${parts[0]}"
            val path = parts.getOrNull(1).orEmpty()
            if (path.isEmpty()) volume else "$volume/$path"
        }.getOrDefault(uri.toString())
    }

    companion object {
        const val KEY_RANDOM_NAME = "random_file_name"
        const val KEY_REENCODE = "reencode"
        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_DELETE_ORIGINAL = "delete_original"

        /** Relative path used with MediaStore when no custom folder is set. */
        val DEFAULT_RELATIVE_PATH: String = Environment.DIRECTORY_PICTURES + "/MetaStrip"
        const val DEFAULT_DIR_LABEL = "Internal storage/Pictures/MetaStrip"
    }
}
