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

import android.content.ContentResolver
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sm314.metastrip.R
import com.sm314.metastrip.app.MainViewModel.Status
import com.sm314.metastrip.databinding.ActivityMainBinding

/**
 * Renders [MainViewModel.state] and forwards user actions to the ViewModel.
 * Holds no state of its own beyond what the preview is currently showing.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        /** Longest edge of the on-screen preview, in pixels. */
        const val PREVIEW_MAX_EDGE = 1280
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val vm: MainViewModel by viewModels()

    /** Which image the preview currently shows, so it is not reloaded on every state change. */
    private var previewedUri: Uri? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.onImageChosen(applicationContext, uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.root)
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
        binding.stripButton.setOnClickListener { vm.strip(applicationContext, settings) }
        binding.shareButton.setOnClickListener { shareResult() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }

        // The ViewModel already holds any image from before a rotation, so
        // only consume the share intent on a genuinely fresh start.
        if (savedInstanceState == null) handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.outputDir.text = getString(R.string.saving_to, settings.outputDirLabel())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ---------- Rendering ----------

    private fun render(s: MainViewModel.UiState) {
        binding.progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        binding.pickButton.isEnabled = !s.busy
        binding.stripButton.isEnabled = !s.busy && s.sourceUri != null
        binding.shareButton.visibility = if (s.result != null) View.VISIBLE else View.GONE
        binding.status.text = statusText(s)

        // Caption the image on screen. The preview shows the result once there
        // is one, so the name has to follow it rather than always naming the
        // source. Without this an undecodable file leaves the screen blank.
        val caption = s.result?.fileName ?: s.sourceLabel
        binding.fileName.text = caption.orEmpty()
        binding.fileName.visibility = if (caption != null) View.VISIBLE else View.GONE

        val want = s.result?.uri ?: s.sourceUri
        if (want != previewedUri) {
            previewedUri = want
            if (want != null) showPreview(want) else binding.preview.setImageDrawable(null)
        }
    }

    private fun statusText(s: MainViewModel.UiState): String = when (val st = s.status) {
        Status.Empty -> getString(R.string.status_empty)
        Status.Ready -> getString(R.string.status_ready)
        Status.Working -> getString(R.string.status_working)
        Status.BadShare -> getString(R.string.status_bad_share)
        is Status.Error -> getString(R.string.status_error, st.message)
        is Status.Done -> {
            val method = when (st.method) {
                MetadataStripper.Method.REENCODED -> getString(R.string.method_reencoded)
                MetadataStripper.Method.LOSSLESS -> getString(R.string.method_lossless)
            }
            getString(R.string.status_done, st.fileName, method)
        }
    }

    /**
     * Loads a downsampled preview on a background thread. The sample size is
     * chosen from the header before any pixels are allocated, so a hostile
     * file that declares enormous dimensions costs a small bitmap, not the
     * whole heap, and the UI thread never blocks on a decode.
     */
    private fun showPreview(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val src = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        decoder.setTargetSampleSize(maxOf(1, longest / PREVIEW_MAX_EDGE))
                    }
                }.getOrNull()
            }
            // Only apply if this is still the image we want; a newer pick may have superseded it.
            if (previewedUri == uri) {
                if (bitmap != null) binding.preview.setImageBitmap(bitmap)
                else binding.preview.setImageDrawable(null)
            }
        }
    }

    // ---------- Actions ----------

    /** Supports "Share to MetaStrip" from a gallery or any other app. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        // Other apps may only hand over content:// URIs, which go through a
        // provider and carry an explicit grant. file:// and anything else
        // would let a sender point the app at arbitrary paths.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            vm.onBadShare()
            return
        }
        vm.onImageChosen(applicationContext, uri)
    }

    private fun shareResult() {
        val res = vm.state.value.result ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = res.mimeType
            putExtra(Intent.EXTRA_STREAM, res.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_title)))
    }
}
