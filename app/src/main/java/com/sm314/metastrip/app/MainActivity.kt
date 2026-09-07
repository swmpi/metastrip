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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sm314.metastrip.R
import com.sm314.metastrip.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private var sourceUri: Uri? = null
    private var result: MetadataStripper.Result? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImageChosen(uri) }

    /** Result of the system's own "delete this photo?" dialog. */
    private val confirmDelete = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deleted = result.resultCode == RESULT_OK
        appendStatus(getString(if (deleted) R.string.delete_done else R.string.delete_declined))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            } else false
        }

        binding.pickButton.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.stripButton.setOnClickListener { stripCurrentImage() }
        binding.shareButton.setOnClickListener { shareResult() }

        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.outputDir.text = getString(R.string.saving_to, settings.outputDirLabel())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Supports "Share to MetaStrip" from a gallery or any other app. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) onImageChosen(uri)
    }

    private fun onImageChosen(uri: Uri) {
        sourceUri = uri
        result = null
        binding.preview.setImageURI(uri)
        binding.status.text = getString(R.string.status_ready)
        binding.stripButton.isEnabled = true
        binding.shareButton.visibility = View.GONE
    }

    private fun stripCurrentImage() {
        val uri = sourceUri ?: return
        setBusy(true)
        binding.status.text = getString(R.string.status_working)

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { MetadataStripper.strip(applicationContext, uri, settings) }
            }
            setBusy(false)
            outcome.onSuccess { res ->
                result = res
                binding.preview.setImageURI(res.uri)
                val methodText = when (res.method) {
                    MetadataStripper.Method.REENCODED -> getString(R.string.method_reencoded)
                    MetadataStripper.Method.LOSSLESS -> getString(R.string.method_lossless)
                }
                binding.status.text = getString(R.string.status_done, res.fileName, methodText)
                binding.shareButton.visibility = View.VISIBLE
                // Only ever delete after the clean copy is safely written.
                if (settings.deleteOriginal) deleteOriginal(uri)
            }.onFailure { err ->
                binding.status.text = getString(
                    R.string.status_error,
                    err.localizedMessage ?: err.javaClass.simpleName
                )
            }
        }
    }

    /** Runs only after a clean copy exists on disk. */
    private fun deleteOriginal(source: Uri) {
        when (val outcome = OriginalDeleter.delete(applicationContext, source)) {
            is OriginalDeleter.Outcome.Deleted ->
                appendStatus(getString(R.string.delete_done))
            is OriginalDeleter.Outcome.NeedsConsent ->
                confirmDelete.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
            is OriginalDeleter.Outcome.Unsupported ->
                appendStatus(getString(R.string.delete_failed, outcome.reason))
        }
    }

    private fun appendStatus(line: String) {
        binding.status.text = binding.status.text.toString() + "\n" + line
    }

    private fun shareResult() {
        val res = result ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = res.mimeType
            putExtra(Intent.EXTRA_STREAM, res.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_title)))
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.pickButton.isEnabled = !busy
        binding.stripButton.isEnabled = !busy && sourceUri != null
    }
}
