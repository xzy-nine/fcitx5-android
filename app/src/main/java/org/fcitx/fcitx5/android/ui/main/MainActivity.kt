/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.ui.main.settings.SearchResult
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchManager
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController

    private var pendingSearchResult: SearchResult? = null
    private var pendingHighlightKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // always show toolbar back arrow icon
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = SettingsRoute.createGraph(navController)
        viewModel.toolbarTitle.observe(this) {
            supportActionBar!!.title = it
        }
        viewModel.toolbarShadow.observe(this) {
            binding.toolbar.elevation = dp(if (it) 4f else 0f)
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            if (dest.hasRoute<SettingsRoute.Theme>()) {
                viewModel.disableToolbarShadow()
            } else {
                viewModel.enableToolbarShadow()
            }
        }
        // search menu provider (always visible)
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val iconTint = styledColor(android.R.attr.colorControlNormal)
                menu.item(R.string.search, R.drawable.ic_baseline_search_24, iconTint, true) {
                    SettingsSearchManager.showSearchDialog(this@MainActivity)
                }
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        }, this, Lifecycle.State.STARTED)
        processIntent(intent)
        checkNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        // "minimize" the activity if we can't go back
        return navController.navigateUp() || super.onSupportNavigateUp() || moveTaskToBack(false)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pinyin_dict)
                    .setMessage(R.string.whether_import_dict)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        navController.popBackStack(SettingsRoute.Index, false)
                        navController.navigateWithAnim(SettingsRoute.PinyinDict(it))
                    }
                    .show()
            }
            Intent.ACTION_RUN -> {
                val route = intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    fun navigateToSetting(result: SearchResult) {
        pendingSearchResult = result
        val popped = navController.popBackStack(SettingsRoute.Index, false)
        if (!popped) {
            onIndexShown()
        }
    }

    fun onIndexShown() {
        val result = pendingSearchResult ?: return
        pendingSearchResult = null

        Handler(Looper.getMainLooper()).postDelayed({
            pendingHighlightKey = result.highlightKey
            navController.navigateWithAnim(result.route)
        }, 500)
    }

    fun getPendingHighlightKey(): String? {
        val key = pendingHighlightKey
        pendingHighlightKey = null
        return key
    }

    private var needNotifications by AppPrefs.getInstance().internal.needNotifications

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                needNotifications = true
                return
            }
            // do not ask again if user denied the request
            if (!needNotifications) return
            // always show a dialog to explain why we need notification permission,
            // regardless of `shouldShowRequestPermissionRationale(...)`
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.i_do_not_need_it) { _, _ ->
                    // do not ask again if user denied the request
                    needNotifications = false
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 0) return
        // do not ask again if user denied the request
        needNotifications = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }

}
