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

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Deletes the file the user picked, after a clean copy has been saved.
 *
 * The app holds no storage permissions, so it cannot delete anything on its
 * own. Every route below either asks the system to show a confirmation
 * dialog, or relies on a write grant the user already gave:
 *
 *  - Document URIs from the folder picker: delete directly, since the user
 *    granted write access to that tree.
 *  - MediaStore URIs (usually from "share to MetaStrip"): ask the system for
 *    a delete request. Android shows its own confirmation dialog.
 *  - Photo Picker URIs: these are read-only and are not MediaStore items, so
 *    they cannot be deleted. The caller reports this rather than failing
 *    silently.
 *
 * Deletion is only ever attempted after the stripped copy is safely written.
 */
object OriginalDeleter {

    sealed interface Outcome {
        /** The file is gone. */
        object Deleted : Outcome
        /** The system needs the user to confirm; launch this, then report the result. */
        class NeedsConsent(val intentSender: IntentSender) : Outcome
        /** Deletion is not possible for this URI. [reason] is shown to the user. */
        class Unsupported(val reason: String) : Outcome
    }

    fun delete(context: Context, source: Uri): Outcome {
        val resolver = context.contentResolver

        // 1. A document the user granted write access to via the folder picker.
        if (DocumentsContract.isDocumentUri(context, source)) {
            return runCatching {
                if (DocumentsContract.deleteDocument(resolver, source)) Outcome.Deleted
                else Outcome.Unsupported("The file could not be deleted.")
            }.getOrElse { Outcome.Unsupported("No permission to delete that file.") }
        }

        // 2. Photo Picker URIs are read-only and are not MediaStore rows.
        if (isPhotoPickerUri(source)) {
            return Outcome.Unsupported(
                "Android does not allow deleting a photo chosen with the system picker. " +
                    "Share the photo into MetaStrip instead, or delete it in your gallery."
            )
        }

        // 3. A real MediaStore item. Android shows its own confirmation dialog.
        if (isMediaStoreUri(source)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return runCatching {
                    val request = MediaStore.createDeleteRequest(resolver, listOf(source))
                    Outcome.NeedsConsent(request.intentSender)
                }.getOrElse { Outcome.Unsupported("That file cannot be deleted.") }
            }
            // Android 10: try the delete, and surface the system's own consent
            // dialog if it comes back as a recoverable security failure.
            return try {
                if (resolver.delete(source, null, null) > 0) Outcome.Deleted
                else Outcome.Unsupported("The file could not be deleted.")
            } catch (e: SecurityException) {
                val recoverable = e as? RecoverableSecurityException
                    ?: return Outcome.Unsupported("No permission to delete that file.")
                Outcome.NeedsConsent(recoverable.userAction.actionIntent.intentSender)
            }
        }

        return Outcome.Unsupported("That file cannot be deleted from MetaStrip.")
    }

    private fun isPhotoPickerUri(uri: Uri): Boolean =
        uri.authority?.contains("photopicker") == true ||
            uri.pathSegments.contains("picker")

    private fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        return runCatching { ContentUris.parseId(uri) }.isSuccess
    }
}
