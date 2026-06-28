/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.broadcast.BroadcastSecurityManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

class BroadcastSettingsFragment : PaddingPreferenceFragment() {

    private var pairingCodePref: Preference? = null
    private var pairedAppsCategory: PreferenceCategory? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // Enable switch
            addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = "broadcast_enable"
                setTitle(R.string.broadcast_enable)
                setSummary(R.string.broadcast_enable_summary)
                isChecked = BroadcastSecurityManager.isEnabled()
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    AppPrefs.getInstance().broadcast.enabled.setValue(enabled)
                    if (enabled) {
                        BroadcastSecurityManager.startListening()
                    } else {
                        BroadcastSecurityManager.stopListening()
                    }
                    true
                }
            })

            // Pairing code - standalone row, click to copy
            addPreference(Preference(requireContext()).apply {
                setTitle(R.string.pairing_code)
                val code = BroadcastSecurityManager.getPairingCode()
                    ?: BroadcastSecurityManager.generatePairingCode()
                summary = code
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    val currentCode = BroadcastSecurityManager.getPairingCode()
                        ?: BroadcastSecurityManager.generatePairingCode()
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    val clip = ClipData.newPlainText("pairing_code", currentCode)
                    clipboard.setPrimaryClip(clip)
                    summary = currentCode
                    requireContext().toast(R.string.pairing_code_copied)
                    true
                }
                pairingCodePref = this
            })

            // Paired apps category
            addPreference(PreferenceCategory(requireContext()).apply {
                setTitle(R.string.paired_apps)
                pairedAppsCategory = this
            })
        }
        loadPairedApps()
    }

    override fun onResume() {
        super.onResume()
        loadPairedApps()
    }

    private fun loadPairedApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                BroadcastSecurityManager.getAllPairedApps()
            }
            pairedAppsCategory?.let { category ->
                category.removeAll()
                if (apps.isEmpty()) {
                    category.addPreference(Preference(requireContext()).apply {
                        setTitle(R.string.no_paired_apps)
                        isSelectable = false
                    })
                } else {
                    apps.forEach { app ->
                        category.addPreference(Preference(requireContext()).apply {
                            title = app.appName.ifEmpty { app.packageName }
                            summary = app.packageName
                            setOnPreferenceClickListener {
                                AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.revoke_pairing)
                                    .setMessage(getString(R.string.revoke_pairing_confirm, app.packageName))
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        lifecycleScope.launch {
                                            withContext(Dispatchers.IO) {
                                                BroadcastSecurityManager.revokePairing(app.packageName)
                                            }
                                            requireContext().toast(getString(R.string.app_revoked, app.packageName))
                                            loadPairedApps()
                                        }
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                                true
                            }
                        })
                    }
                }
            }
        }
    }
}
