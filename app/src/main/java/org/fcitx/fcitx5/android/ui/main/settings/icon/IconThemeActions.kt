/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrBitmapUtil
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/** 图标主题的导入与分享操作，与界面无关。 */
object IconThemeActions {

    /** 单个 JSON 导入的体积上限；图标主题是小文档，超出即非本格式。 */
    const val MAX_IMPORT_JSON_BYTES = 1024 * 1024

    /** 二维码长图内嵌预览的边长。 */
    private const val QR_PREVIEW_ICON_SIZE = 96

    /** 从 JSON 文档导入主题。 */
    suspend fun importJson(context: Context, uri: Uri): Result<IconTheme> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                val limited = ByteArray(MAX_IMPORT_JSON_BYTES + 1)
                var read = 0
                while (read < limited.size) {
                    val n = input.read(limited, read, limited.size - read)
                    if (n <= 0) break
                    read += n
                }
                check(read <= MAX_IMPORT_JSON_BYTES) { "icon theme JSON too large" }
                String(limited, 0, read, Charsets.UTF_8)
            } ?: error("Cannot open JSON")
            IconThemeManager.importTheme(json)
        }
    }

    /** 从 ZIP 归档导入主题。 */
    suspend fun importZip(context: Context, uri: Uri): Result<IconTheme> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                IconThemeManager.importThemeFromZip(input).getOrThrow()
            } ?: error("Cannot open ZIP")
        }
    }

    /** 把主题打包成 ZIP 并通过系统分享。 */
    suspend fun shareAsZip(context: Context, theme: IconTheme) {
        try {
            val uri = withContext(Dispatchers.Default) {
                val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
                val safeName = theme.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val zipFile = File(dir, "icon_theme_$safeName.zip")
                FileOutputStream(zipFile).use { out ->
                    IconThemeManager.exportThemeToZip(theme, out).getOrThrow()
                }
                FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".share.fileprovider",
                    zipFile,
                )
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.icon_theme_share_title)),
            )
        } catch (e: Exception) {
            Timber.w(e, "图标主题打包分享失败")
            context.toast(context.getString(R.string.icon_theme_export_zip_failed, e.message.orEmpty()))
        }
    }

    /** 把主题编码成二维码长图并通过系统分享。 */
    suspend fun shareAsQrImage(context: Context, theme: IconTheme) {
        try {
            val bundle = withContext(Dispatchers.Default) {
                IconThemeQrTransferCodec.encodeIconThemeToChunks(theme)
            }
            val labels = JsonFileQrShareManager.buildChunkLabels(
                bundle,
                context.getString(R.string.qr_payload_type_icon_theme),
                theme.name,
            )
            val image = withContext(Dispatchers.Default) {
                val preview = renderThumbnailBitmap(theme, QR_PREVIEW_ICON_SIZE)?.let { icon ->
                    val strip = LayoutQrBitmapUtil.composeCenteredPreviewStrip(icon, QR_PREVIEW_ICON_SIZE)
                    icon.recycle()
                    strip
                }
                val composed = LayoutQrBitmapUtil.composeLongImageStreamingWithPreview(
                    bundle.chunks.map { it.encode() }, labels, preview,
                )
                if (preview != null && !preview.isRecycled) preview.recycle()
                composed
            }
            val uri = JsonFileQrShareManager.saveLongImageToShareCache(
                context, image, "icon_theme_${theme.name}",
            )
            if (!image.isRecycled) image.recycle()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.icon_theme_share_title)),
            )
        } catch (e: Exception) {
            Timber.w(e, "图标主题二维码分享失败")
            context.toast(context.getString(R.string.icon_theme_export_qr_failed, e.message.orEmpty()))
        }
    }

    /** 主题缩略图的位图；无法渲染时返回 null。 */
    fun renderThumbnailBitmap(theme: IconTheme, sizePx: Int): Bitmap? {
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

    /** 生成不冲突的副本名。 */
    fun generateCopyName(name: String, existing: List<IconTheme>): String {
        val base = "$name (copy)"
        if (existing.none { it.name == base }) return base
        var index = 2
        while (existing.any { it.name == "$base $index" }) index++
        return "$base $index"
    }
}
