/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.dimensions.dp

/**
 * 图标主题列表的一项：预览图标、名称、选中态与编辑入口。
 *
 * 缩略图由主题自带的 SVG 渲染；缺失时回落到通用图标。
 */
class IconThemeThumbnailUi(private val context: Context) {
    val root: FrameLayout = FrameLayout(context).apply {
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        minimumHeight = context.dp(88)
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val previewIcon = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(36))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val nameText = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        maxLines = 2
        setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
        setTextColor(settingsColor(android.R.attr.textColorPrimary))
    }

    private val activeIndicator = TextView(context).apply {
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(settingsColor(android.R.attr.textColorSecondary))
    }

    private val editButton = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.TOP or Gravity.END)
        setPadding(context.dp(10), context.dp(10), context.dp(6), context.dp(6))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_edit_24)
        imageTintList = ColorStateList.valueOf(context.settingsColor(android.R.attr.textColorSecondary))
        contentDescription = context.getString(R.string.edit_icon_theme)
    }

    private val checkMark = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(52), context.dp(52), Gravity.CENTER)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_check_24)
        visibility = android.view.View.GONE
    }

    init {
        content.addView(previewIcon)
        content.addView(nameText)
        content.addView(activeIndicator)
        root.addView(content)
        root.addView(checkMark)
        root.addView(editButton)
    }

    private fun applySelectionBackground(isActive: Boolean) {
        val accent = context.settingsColor(android.R.attr.colorAccent)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12f)
            if (isActive) {
                setColor((0x22 shl 24) or (accent and 0x00FFFFFF))
            } else {
                setColor(0x00000000)
            }
        }
        content.background = background
        checkMark.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
        checkMark.imageTintList = ColorStateList.valueOf(accent)
    }

    fun bindNew() {
        val accent = context.settingsColor(android.R.attr.colorAccent)
        previewIcon.setImageResource(android.R.drawable.ic_input_add)
        previewIcon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
        nameText.text = context.getString(R.string.icon_theme_new)
        nameText.setTextColor(accent)
        activeIndicator.visibility = android.view.View.GONE
        applySelectionBackground(isActive = false)
        setEditAction(visible = false, onClick = null)
    }

    fun bind(theme: IconTheme, isActive: Boolean, isDefault: Boolean) {
        val accent = context.settingsColor(android.R.attr.colorAccent)
        val primary = context.settingsColor(android.R.attr.textColorPrimary)
        val thumbnailSvg = theme.thumbnailSvg
            ?.takeIf { it.isNotBlank() }
            ?: IconThemeManager.buildThemeThumbnailSvg(theme.icons)
        val thumbnailDrawable = thumbnailSvg
            ?.let { SlotRowUi.renderSvgPreview(context, it, 36) }
        if (thumbnailDrawable != null) {
            previewIcon.setImageDrawable(thumbnailDrawable)
            previewIcon.imageTintList = ColorStateList.valueOf(if (isActive) accent else primary)
        } else {
            val fallback = AppCompatResources.getDrawable(context, R.drawable.ic_icon_theme_24)?.mutate()
            val fallbackSize = context.dp(36)
            fallback?.setBounds(0, 0, fallbackSize, fallbackSize)
            previewIcon.setImageDrawable(fallback)
            previewIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) accent else primary
            )
        }
        nameText.text = if (isDefault) context.getString(R.string.default_) else theme.name
        nameText.setTextColor(if (isActive) accent else primary)
        activeIndicator.visibility = android.view.View.GONE
        applySelectionBackground(isActive)
        setEditAction(visible = !isDefault, onClick = null)
    }

    fun setEditAction(visible: Boolean, onClick: (() -> Unit)?) {
        editButton.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (visible && onClick != null) {
            editButton.setOnClickListener { onClick() }
        } else {
            editButton.setOnClickListener(null)
        }
    }
}
