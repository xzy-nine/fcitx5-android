/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.font.FontProviders
import java.io.File

/** 字体集编辑的读取、保存与文件枚举，与界面无关。 */
internal object FontsetActions {

    /** 可用的字体文件扩展名。 */
    private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".otc")

    /** 字号可调范围。 */
    const val MIN_SIZE = 8f
    const val MAX_SIZE = 72f

    /** 字体集文件；尚未创建时落在应用字体目录。 */
    fun fontsetFile(context: Context): File =
        ConfigProviders.provider.fontsetFile() ?: File(File(context.filesDir, "fonts"), "fontset.json")

    /** 字体文件所在目录。 */
    fun fontsDir(context: Context): File =
        fontsetFile(context).parentFile ?: File(context.filesDir, "fonts")

    /** 目录内可选的字体文件名，按名称排序。 */
    fun availableFonts(context: Context): List<String> {
        val dir = fontsDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.filter { name -> FONT_EXTENSIONS.any { name.lowercase().endsWith(it) } }
            ?.sorted()
            .orEmpty()
    }

    /** 已保存的字体与字号映射；读取失败时返回空映射。 */
    fun load(context: Context): Map<String, List<String>> =
        ConfigProviders.readFontsetPathMapSnapshot()
            .fold(onSuccess = { it?.value ?: emptyMap() }, onFailure = { emptyMap() })

    /** 保存字体与字号；字号以 <key>_size 独立存放。 */
    fun save(
        context: Context,
        fonts: Map<String, List<String>>,
        sizes: Map<String, Float>,
        entries: List<FontEntrySpec>,
    ): Result<File> {
        val sizeMap = entries.associate { entry ->
            "${entry.key}_size" to mutableListOf(
                (sizes[entry.key] ?: entry.defaultSize).toString(),
            )
        }.filterValues { it.firstOrNull()?.toFloatOrNull() != null }
        val fontMap = entries
            .associate { entry -> entry.key to fonts[entry.key].orEmpty() }
            .filterValues { it.isNotEmpty() }
        return ConfigProviders.provider.writeFontsetPathMap(fontMap + sizeMap)
            .onSuccess { FontProviders.markNeedsRefresh() }
    }

    /** 由字体文件名构建可用的 Typeface；无可用文件时返回 null。 */
    fun buildTypeface(context: Context, fontNames: List<String>): Typeface? {
        val files = fontNames.map { File(fontsDir(context), it) }.filter { it.exists() }
        if (files.isEmpty()) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val firstFont = Font.Builder(files.first()).build()
                var builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(firstFont).build())
                files.drop(1).forEach { file ->
                    builder = builder.addCustomFallback(FontFamily.Builder(Font.Builder(file).build()).build())
                }
                builder.build()
            } else {
                Typeface.createFromFile(files.first())
            }
        }.getOrNull()
    }
}

/** 一个字体条目的静态定义。 */
internal data class FontEntrySpec(
    val key: String,
    val titleRes: Int,
    val sample: String,
    val defaultSize: Float,
)
