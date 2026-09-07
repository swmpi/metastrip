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
import android.provider.OpenableColumns
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
 * The activity only renders [state] and forwards user actions.
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

    data class UiState(
        val sourceUri: Uri? = null,
        /** Display name of [sourceUri]. Null until the provider has been queried. */
        val sourceName: String? = null,
        val result: MetadataStripper.Result? = null,
        val busy: Boolean = false,
        val status: Status = Status.Empty
    )

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---------- Actions from the activity ----------

    fun onImageChosen(appContext: Context, uri: Uri) {
        update { UiState(sourceUri = uri, status = Status.Ready) }
        // Querying the provider touches disk, so it happens off the main
        // thread and lands a moment later. A newer pick wins.
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(appContext, uri) }
            update { if (it.sourceUri == uri) it.copy(sourceName = name) else it }
        }
    }

    /** The provider's own name for the file, or null if it does not report one. */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun onBadShare() = update { it.copy(status = Status.BadShare) }

    fun strip(appContext: Context, settings: Settings) {
        val current = _state.value
        val uri = current.sourceUri ?: return
        if (current.busy) return

        update { it.copy(busy = true, status = Status.Working) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { MetadataStripper.strip(appContext, uri, settings) }
            }
            outcome.onSuccess { res ->
                update {
                    it.copy(
                        busy = false,
                        result = res,
                        status = Status.Done(res.fileName, res.method)
                    )
                }
            }.onFailure { err ->
                update {
                    it.copy(busy = false, status = Status.Error(err.localizedMessage ?: err.javaClass.simpleName))
                }
            }
        }
    }

    // ---------- Persistence ----------

    private fun update(transform: (UiState) -> UiState) {
        val next = transform(_state.value)
        _state.value = next
        saved[KEY_SOURCE] = next.sourceUri
        saved[KEY_SOURCE_NAME] = next.sourceName
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
        return UiState(
            sourceUri = source,
            sourceName = saved[KEY_SOURCE_NAME],
            result = result,
            status = status
        )
    }

    private companion object {
        const val KEY_SOURCE = "source_uri"
        const val KEY_SOURCE_NAME = "source_name"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_MIME = "result_mime"
        const val KEY_RESULT_NAME = "result_name"
        const val KEY_RESULT_METHOD = "result_method"
    }
}
