/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.config.ButtonIconFile

/** 图标主题中单个槽位值的内联预览渲染。 */
internal object IconThemePreviewRender {

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun isSvgContent(value: String?): Boolean = value != null && IconThemeManager.isInlineSvg(value)

    fun isDrawableXmlContent(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (ButtonIconFile.isFileIcon(value)) return ButtonIconFile.loadDrawable(value) != null
        val raw = normalizeXmlContent(value)
        if (!raw.startsWith("<")) return false
        if (isSvgContent(raw)) return false
        return IconThemeManager.loadInlineDrawableXml(raw) != null
    }

    /** 把内联 SVG 渲染成指定位图尺寸的 Drawable。 */
    fun renderSvgPreview(context: Context, svgContent: String, previewSizeDp: Int = 24): Drawable? {
        return try {
            var clean = svgContent
                .removePrefix("\uFEFF")
                .replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>\\s*"), "")
                .replaceFirst(
                    Regex("^(\\s*<!--.*?-->\\s*)+", setOf(RegexOption.DOT_MATCHES_ALL)),
                    "",
                )
            if (clean.contains("xlink:") && !clean.contains("xmlns:xlink")) {
                clean = clean.replaceFirst(
                    Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE),
                    "<svg\$1 xmlns:xlink=\"http://www.w3.org/1999/xlink\">",
                )
            }
            val svg = com.caverock.androidsvg.SVG.getFromString(clean)
            val size = context.dp(previewSizeDp)
            val picture = svg.renderToPicture()
            val w = picture.width.coerceAtLeast(1)
            val h = picture.height.coerceAtLeast(1)
            if (w <= 1 || h <= 1) {
                val bitmap = Bitmap.createBitmap(
                    size.coerceAtLeast(1), size.coerceAtLeast(1), Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()))
                BitmapDrawable(context.resources, bitmap)
            } else {
                val scale = minOf(size.toFloat() / w, size.toFloat() / h)
                val bw = (w * scale).toInt().coerceAtLeast(1)
                val bh = (h * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.drawPicture(picture)
                BitmapDrawable(context.resources, bitmap)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 去掉 XML 声明、DOCTYPE 与注释，便于后续判定内容类型。 */
    fun normalizeXmlContent(raw: String): String {
        return raw
            .removePrefix("\uFEFF")
            .replaceFirst(
                Regex(
                    "^\\s*(?:<\\?xml\\b[^>]*\\?>|<!DOCTYPE\\b[^>]*(?:\\[[^\\]]*\\])?[^>]*>|<!--.*?-->)\\s*",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
                ),
                "",
            )
    }
}
