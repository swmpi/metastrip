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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds everything the main screen shows and runs the strip.
 *
 * A ViewModel outlives the activity across rotation, so a strip started in
 * portrait finishes and reports back in landscape instead of being cancelled
 * with the old activity. The chosen image and last result are also written to
 * [SavedStateHandle], which survives the process being killed in the
 * background.
 *
 * The activity only renders [state] and forwards user actions. Anything that
 * needs an Activity, such as launching the system delete dialog, is signalled
 * through [UiState.pendingDelete] and cleared once the activity has taken it.
 */
class MainViewModel(private val saved: SavedStateHandle) : ViewModel() {

    sealed interface Status {
        data object Empty : Status
        data object Ready : Status
        data object Working : Status
        data class Done(val fileName: String, val method: MetadataStripper.Method) : Status
        data class Error(val message: String) : Status
        data object BadShare : Status
    }

    sealed interface DeleteNote {
        data object Deleted : DeleteNote
        data object Kept : DeleteNote
        data class Failed(val reason: String) : DeleteNote
    }

    data class UiState(
        val sourceUri: Uri? = null,
        val result: MetadataStripper.Result? = null,
        val busy: Boolean = false,
        val status: Status = Status.Empty,
        val deleteNote: DeleteNote? = null,
        /** Set when a strip succeeds with delete-original on. The activity acts on it once, then clears it. */
        val pendingDelete: Uri? = null
    )

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---------- Actions from the activity ----------

    fun onImageChosen(uri: Uri) = update { UiState(sourceUri = uri, status = Status.Ready) }

    fun onBadShare() = update { it.copy(status = Status.BadShare) }

    fun strip(appContext: Context, settings: Settings) {
        val current = _state.value
        val uri = current.sourceUri ?: return
        if (current.busy) return
        val deleteAfter = settings.deleteOriginal   // read once, at the start

        update { it.copy(busy = true, status = Status.Working, deleteNote = null, pendingDelete = null) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { MetadataStripper.strip(appContext, uri, settings) }
            }
            outcome.onSuccess { res ->
                update {
                    it.copy(
                        busy = false,
                        result = res,
                        status = Status.Done(res.fileName, res.method),
                        pendingDelete = if (deleteAfter) uri else null
                    )
                }
            }.onFailure { err ->
                update {
                    it.copy(busy = false, status = Status.Error(err.localizedMessage ?: err.javaClass.simpleName))
                }
            }
        }
    }

    /** The activity has launched or resolved the delete; do not offer it again after rotation. */
    fun onDeleteHandled() = update { it.copy(pendingDelete = null) }

    fun onDeleteNote(note: DeleteNote) = update { it.copy(deleteNote = note) }

    // ---------- Persistence ----------

    private fun update(transform: (UiState) -> UiState) {
        val next = transform(_state.value)
        _state.value = next
        saved[KEY_SOURCE] = next.sourceUri
        saved[KEY_RESULT_URI] = next.result?.uri
        saved[KEY_RESULT_MIME] = next.result?.mimeType
        saved[KEY_RESULT_NAME] = next.result?.fileName
        saved[KEY_RESULT_METHOD] = next.result?.method?.name
    }

    private fun restore(): UiState {
        val source: Uri? = saved[KEY_SOURCE]
        val resultUri: Uri? = saved[KEY_RESULT_URI]
        val result = resultUri?.let {
            MetadataStripper.Result(
                uri = it,
                mimeType = saved[KEY_RESULT_MIME] ?: "",
                fileName = saved[KEY_RESULT_NAME] ?: "",
                method = runCatching {
                    MetadataStripper.Method.valueOf(saved[KEY_RESULT_METHOD] ?: "")
                }.getOrDefault(MetadataStripper.Method.REENCODED)
            )
        }
        val status = when {
            result != null -> Status.Done(result.fileName, result.method)
            source != null -> Status.Ready
            else -> Status.Empty
        }
        return UiState(sourceUri = source, result = result, status = status)
    }

    private companion object {
        const val KEY_SOURCE = "source_uri"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_MIME = "result_mime"
        const val KEY_RESULT_NAME = "result_name"
        const val KEY_RESULT_METHOD = "result_method"
    }
}
