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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.sm314.metastrip.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var settings: Settings

        private val pickFolder = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            }.isSuccess
            if (!persisted) {
                showInfo(R.string.folder_error_title, R.string.folder_error_message)
                return@registerForActivityResult
            }
            releaseOldFolder()
            settings.outputTreeUri = uri
            refreshOutputDir()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            settings = Settings(requireContext())

            setupReencodeConfirmation()
            setupOutputDir()
            setupAbout()
        }

        /** Turning re-encoding OFF asks for confirmation. Turning it ON does not. */
        private fun setupReencodeConfirmation() {
            val pref = findPreference<SwitchPreferenceCompat>(Settings.KEY_REENCODE) ?: return
            pref.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) return@setOnPreferenceChangeListener true
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.reencode_confirm_title)
                    .setMessage(R.string.reencode_confirm_message)
                    .setPositiveButton(R.string.reencode_confirm_yes) { _, _ -> pref.isChecked = false }
                    .setNegativeButton(R.string.reencode_confirm_no, null)
                    .show()
                false   // do not change yet; the dialog decides
            }
        }

        private fun setupOutputDir() {
            findPreference<Preference>("output_dir_pick")?.setOnPreferenceClickListener {
                pickFolder.launch(settings.outputTreeUri)
                true
            }
            findPreference<Preference>("output_dir_reset")?.setOnPreferenceClickListener {
                releaseOldFolder()
                settings.outputTreeUri = null
                refreshOutputDir()
                true
            }
            refreshOutputDir()
        }

        private fun releaseOldFolder() {
            val old = settings.outputTreeUri ?: return
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.releasePersistableUriPermission(old, flags)
            }
        }

        private fun refreshOutputDir() {
            findPreference<Preference>("output_dir_pick")?.summary = settings.outputDirLabel()
            findPreference<Preference>("output_dir_reset")?.isVisible = settings.outputTreeUri != null
        }

        private fun setupAbout() {
            val ctx = requireContext()
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            findPreference<Preference>("about_version")?.summary =
                getString(R.string.version_format, info.versionName, info.longVersionCode)

            findPreference<Preference>("about_how")?.setOnPreferenceClickListener {
                showInfo(R.string.how_title, R.string.how_message); true
            }
            findPreference<Preference>("about_privacy")?.setOnPreferenceClickListener {
                showInfo(R.string.privacy_title, R.string.privacy_message); true
            }
            findPreference<Preference>("about_third_party")?.setOnPreferenceClickListener {
                showInfo(R.string.third_party_title, R.string.third_party_message); true
            }
            findPreference<Preference>("about_license")?.setOnPreferenceClickListener {
                openUrl(getString(R.string.license_url)); true
            }
            findPreference<Preference>("about_source")?.setOnPreferenceClickListener {
                openUrl(getString(R.string.source_url)); true
            }
        }

        private fun showInfo(title: Int, message: Int) {
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
}
