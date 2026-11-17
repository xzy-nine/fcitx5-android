/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar as Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrBitmapUtil
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fcitx.fcitx5.android.ui.common.withProgressLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.QrScanOptions
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor

class IconThemeListActivity : SettingsUiActivity() {
    private companion object {
        private const val MENU_IMPORT_JSON = 2
        private const val MENU_IMPORT_QR_IMAGE = 3
        private const val MENU_SHARE_ICON_THEME = 4
        private const val MENU_IMPORT_QR_SCAN = 5
        private const val MENU_IMPORT_ZIP = 6
        private const val QR_PREVIEW_ICON_SIZE = 96

        /**
         * Largest icon-theme JSON accepted on import.
         *
         * These documents hold icon slot names and inline SVG; 1MB is already generous, and an
         * unbounded read on the main thread was the original problem (see D10).
         */
        private const val MAX_IMPORT_JSON_BYTES = 1024 * 1024
    }

    private val themes get() = IconThemeManager.iconThemes
    private val builtinDefault = IconTheme.default()
    private val adapter = ThemeAdapter()
    private val qrImporter = IconThemeQrImporter()

    private val onListChangeListener = IconThemeManager.OnIconThemeListChangeListener { refreshThemes() }
    private val onThemeChangeListener = IconThemeManager.OnIconThemeChangeListener { refreshThemes() }
    private val onKeyboardThemeChangeListener = ThemeManager.OnThemeChangeListener {
        adapter.notifyDataSetChanged()
    }

    private val jsonImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importJson(it) } }

    private val qrImageImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFromQrImage(it) } }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
        } else {
            Toast.makeText(this, getString(R.string.text_keyboard_layout_qr_camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result?.contents ?: return@registerForActivityResult
        addImportedChunkFromText(content)
    }

    private val zipImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importIconThemeZip(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconThemeManager.addOnListChangeListener(onListChangeListener)
        IconThemeManager.addOnChangedListener(onThemeChangeListener)
        ThemeManager.addOnChangedListener(onKeyboardThemeChangeListener)
        // Scanning is no longer done in the object initializer (see D6), so make sure it has
        // been kicked off; the list listener above refreshes this screen when it completes.
        IconThemeManager.initAsync()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.icon_theme)
            setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        root.addView(toolbar)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@IconThemeListActivity, 3)
            adapter = this@IconThemeListActivity.adapter
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(matchParent, 0, 1f))

        setContentView(root)

    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        IconThemeManager.removeOnListChangeListener(onListChangeListener)
        IconThemeManager.removeOnChangedListener(onThemeChangeListener)
        ThemeManager.removeOnChangedListener(onKeyboardThemeChangeListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_IMPORT_JSON, Menu.NONE, getString(R.string.icon_theme_import_json))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_IMPORT_QR_SCAN, Menu.NONE, getString(R.string.text_keyboard_layout_qr_import_scan))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_IMPORT_QR_IMAGE, Menu.NONE, getString(R.string.icon_theme_import_qr_image))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_IMPORT_ZIP, Menu.NONE, getString(R.string.icon_theme_import_zip))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_SHARE_ICON_THEME, Menu.NONE, getString(R.string.icon_theme_share_title))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_IMPORT_JSON -> { jsonImportLauncher.launch("application/json"); true }
        MENU_IMPORT_QR_SCAN -> { startCameraScanImport(); true }
        MENU_IMPORT_QR_IMAGE -> { qrImageImportLauncher.launch("image/*"); true }
        MENU_IMPORT_ZIP -> { zipImportLauncher.launch("application/zip"); true }
        MENU_SHARE_ICON_THEME -> { promptShareTheme(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Import an icon theme from a JSON document.
     *
     * The read + parse + write used to run on the main thread straight from the menu callback,
     * with no size limit at all (see D10). Now on IO, and capped: an icon theme is a small
     * document, so anything larger is not one.
     */
    private fun importJson(uri: Uri) {
        lifecycleScope.launch {
            val theme = withContext(Dispatchers.IO) {
                runCatching {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        val limited = ByteArray(MAX_IMPORT_JSON_BYTES + 1)
                        var read = 0
                        while (read < limited.size) {
                            val n = input.read(limited, read, limited.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        if (read > MAX_IMPORT_JSON_BYTES) {
                            throw IllegalArgumentException("icon theme JSON exceeds $MAX_IMPORT_JSON_BYTES bytes")
                        }
                        String(limited, 0, read, Charsets.UTF_8)
                    } ?: throw IllegalStateException("Cannot open JSON")
                    IconThemeManager.importTheme(json)
                }
            }
            theme.onSuccess {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_imported, it.name), Toast.LENGTH_SHORT).show()
                refreshThemes()
            }.onFailure {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_import_json_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFromQrImage(uri: Uri) {
        lifecycleScope.withProgressLoadingDialog(this) { updateProgress ->
            val chunks = try {
                withContext(Dispatchers.Default) {
                    JsonFileQrShareManager.decodeQrChunksFromImage(this@IconThemeListActivity, uri) { current, total ->
                        updateProgress(getString(R.string.qr_image_decode_progress, current, total))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(
                    this@IconThemeListActivity,
                    getString(R.string.icon_theme_decode_qr_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@withProgressLoadingDialog
            }
            if (chunks.isEmpty()) {
                Toast.makeText(
                    this@IconThemeListActivity,
                    getString(R.string.icon_theme_no_qr_found),
                    Toast.LENGTH_SHORT
                ).show()
                return@withProgressLoadingDialog
            }
            try {
                val theme = qrImporter.importImageChunks(chunks)
                showImportedTheme(theme)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_decode_qr_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImportedTheme(theme: IconTheme) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
        refreshThemes()
    }

    private fun startCameraScanImport() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun addImportedChunkFromText(raw: String) {
        lifecycleScope.withProgressLoadingDialog(this) {
            val result = try {
                qrImporter.addScan(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_decode_qr_failed), Toast.LENGTH_SHORT).show()
                return@withProgressLoadingDialog
            }
            if (isFinishing || isDestroyed) return@withProgressLoadingDialog
            when (result) {
                IconThemeQrImporter.ScanResult.Invalid -> Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.text_keyboard_layout_qr_invalid_payload), Toast.LENGTH_SHORT).show()
                is IconThemeQrImporter.ScanResult.WrongType -> Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.text_keyboard_layout_qr_type_mismatch,
                        getString(R.string.qr_payload_type_icon_theme), when (result.type) {
                            LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> getString(R.string.qr_payload_type_theme)
                            LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> getString(R.string.qr_payload_type_layout)
                            LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> getString(R.string.qr_payload_type_popup)
                            else -> getString(R.string.qr_payload_type_unknown)
                        }), Toast.LENGTH_SHORT).show()
                is IconThemeQrImporter.ScanResult.Progress -> {
                    val progress = result.progress
                    if (progress.duplicate) Toast.makeText(this@IconThemeListActivity,
                        getString(R.string.text_keyboard_layout_qr_duplicate_chunk), Toast.LENGTH_SHORT).show()
                    Toast.makeText(this@IconThemeListActivity,
                        getString(R.string.text_keyboard_layout_qr_scan_progress, progress.current, progress.total),
                        Toast.LENGTH_SHORT).show()
                    if (result.theme != null) showImportedTheme(result.theme)
                    else cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
                }
            }
        }
    }

    private fun refreshThemes() {
        adapter.notifyDataSetChanged()
    }

    private inner class ThemeAdapter : RecyclerView.Adapter<ThemeAdapter.VH>() {
        private val NEW_ITEM = 0
        private val THEME_OFFSET = 1

        override fun getItemCount() = themes.size + THEME_OFFSET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(IconThemeThumbnailUi(parent.context))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (position) {
                NEW_ITEM -> holder.ui.bindNew()
                else -> {
                    val theme = themes[position - THEME_OFFSET]
                    val isActive = theme.name == IconThemeManager.activeTheme.name
                    val isDefault = theme.name == builtinDefault.name
                    holder.ui.bind(theme, isActive, isDefault)
                    holder.ui.setEditAction(
                        visible = !isDefault,
                        onClick = {
                            startActivity(
                                Intent(this@IconThemeListActivity, IconThemeEditorActivity::class.java)
                                    .putExtra("icon_theme_name", theme.name)
                            )
                        }
                    )
                }
            }
            holder.ui.root.setOnClickListener {
                when (position) {
                    NEW_ITEM -> showCreateThemeMenu()
                    else -> {
                        val theme = themes[position - THEME_OFFSET]
                        IconThemeManager.activeTheme = theme
                        refreshThemes()
                    }
                }
            }
            holder.ui.root.setOnLongClickListener(null)
        }

        inner class VH(val ui: IconThemeThumbnailUi) : RecyclerView.ViewHolder(ui.root)
    }

    private fun showCreateThemeMenu() {
        val actions = arrayOf(
            getString(R.string.new_icon_theme),
            getString(R.string.icon_theme_duplicate)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.icon_theme_new))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, IconThemeEditorActivity::class.java))
                    1 -> promptDuplicateTheme()
                }
            }
            .show()
    }

    private fun promptDuplicateTheme() {
        val customThemes = themes.drop(1)
        if (customThemes.isEmpty()) {
            Toast.makeText(this, getString(R.string.icon_theme_no_custom_theme), Toast.LENGTH_SHORT).show()
            return
        }
        val names = customThemes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.icon_theme_duplicate))
            .setItems(names) { _, which ->
                val source = customThemes[which]
                val duplicated = source.copy(name = generateCopyName(source.name))
                IconThemeManager.saveTheme(duplicated)
                refreshThemes()
                Toast.makeText(this, getString(R.string.icon_theme_duplicated), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, IconThemeEditorActivity::class.java)
                        .putExtra("icon_theme_name", duplicated.name)
                )
            }
            .show()
    }

    private fun promptShareTheme() {
        val customThemes = themes.drop(1)
        if (customThemes.isEmpty()) {
            Toast.makeText(this, getString(R.string.icon_theme_no_custom_theme), Toast.LENGTH_SHORT).show()
            return
        }
        val names = customThemes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.icon_theme_share_title))
            .setItems(names) { _, which ->
                val theme = customThemes[which]
                val hasFiles = IconThemeManager.collectFileReferences(theme).isNotEmpty()
                if (hasFiles) {
                    shareThemeAsZip(theme)
                } else {
                    exportAsQrLongImage(theme)
                }
            }
            .show()
    }

    private fun shareThemeAsZip(theme: IconTheme) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.Default) {
                    val dir = java.io.File(cacheDir, "shared").also { it.mkdirs() }
                    val zipFile = java.io.File(dir, "icon_theme_${theme.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.zip")
                    java.io.FileOutputStream(zipFile).use { out ->
                        IconThemeManager.exportThemeToZip(theme, out).getOrThrow()
                    }
                    androidx.core.content.FileProvider.getUriForFile(
                        this@IconThemeListActivity,
                        "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                        zipFile
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.icon_theme_share_title)))
            } catch (e: Exception) {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_export_zip_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importIconThemeZip(uri: Uri) {
        lifecycleScope.launch {
            try {
                val theme = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        IconThemeManager.importThemeFromZip(input).getOrThrow()
                    } ?: throw IllegalStateException("Cannot open ZIP")
                }
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
                refreshThemes()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable, not Exception: a malformed archive could previously surface as an
                // OutOfMemoryError, which is an Error and escaped as a process crash (see A6).
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_import_zip_failed, t.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderThumbnailBitmap(theme: IconTheme, sizePx: Int): Bitmap? {
        val svgContent = theme.thumbnailSvg?.takeIf { it.isNotBlank() }
            ?: IconThemeManager.buildThemeThumbnailSvg(theme.icons)
            ?: return null
        return runCatching {
            val svg = com.caverock.androidsvg.SVG.getFromString(svgContent)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
            bitmap
        }.getOrNull()
    }

    private fun exportAsQrLongImage(theme: IconTheme) {
        lifecycleScope.launch {
            try {
                val bundle = withContext(Dispatchers.Default) { IconThemeQrTransferCodec.encodeIconThemeToChunks(theme) }
                val labels = JsonFileQrShareManager.buildChunkLabels(bundle, getString(R.string.qr_payload_type_icon_theme), theme.name)
                val image = withContext(Dispatchers.Default) {
                    val preview = renderThumbnailBitmap(theme, QR_PREVIEW_ICON_SIZE)?.let { icon ->
                        val strip = LayoutQrBitmapUtil.composeCenteredPreviewStrip(icon, QR_PREVIEW_ICON_SIZE)
                        icon.recycle()
                        strip
                    }
                    val composed = LayoutQrBitmapUtil.composeLongImageStreamingWithPreview(
                        bundle.chunks.map { it.encode() }, labels, preview
                    )
                    if (preview != null && !preview.isRecycled) preview.recycle()
                    composed
                }
                // saveLongImageToShareCache now suspends (PNG encode is on IO) and sanitizes
                // the prefix itself, so a theme name with path characters is safe here.
                val uri = JsonFileQrShareManager.saveLongImageToShareCache(
                    this@IconThemeListActivity, image, "icon_theme_${theme.name}")
                if (!image.isRecycled) image.recycle()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.icon_theme_share_title)))
            } catch (e: Exception) {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_export_qr_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateCopyName(name: String): String {
        val base = "$name (copy)"
        if (themes.none { it.name == base }) return base
        var i = 2
        while (themes.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
}

