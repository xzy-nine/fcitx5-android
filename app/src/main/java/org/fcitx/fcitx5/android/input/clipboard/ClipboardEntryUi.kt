/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.textView
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntryUi(override val ctx: Context, private val theme: Theme, radius: Float) : Ui {

    // 气泡按钮最多显示的字符数（超出截断加省略号，点击仍上屏完整值）
    private val CHIP_MAX_LEN = 12

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        setPaddingDp(14, 10, 14, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
    }

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            setTint(theme.altKeyTextColor)
            setAlpha(0.3f)
        }
    }

    // 提取实体（验证码/号码/姓名等）气泡行的横向滚动容器
    private val chipContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPaddingDp(14, 2, 14, 10)
    }

    val chipRow = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.TRANSPARENT)
        addView(chipContainer, LinearLayout.LayoutParams(WC, WC))
    }

    // 内容列：文本在上，气泡行在下
    private val contentLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(textView, LinearLayout.LayoutParams(MP, WC))
        addView(chipRow, LinearLayout.LayoutParams(MP, WC))
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(30)
        // 让卡片在单列 LinearLayoutManager 下左右铺满（RecyclerView 会转成 RecyclerView.LayoutParams）
        layoutParams = ViewGroup.LayoutParams(MP, WC)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.clipboardEntryColor)
        }
        // 文本 + 气泡行（占满宽度，高度按内容）
        addView(contentLayout, FrameLayout.LayoutParams(MP, WC))
        // 钉住标记叠加在右下角
        addView(
            pin,
            FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setMargins(0, 0, dp(2), dp(2))
            }
        )
    }

    /**
     * 设置条目内容，并根据需要渲染实体提取气泡。
     * @param chips 提取出的实体；为空则不显示气泡行
     * @param onChipClick 点击某个气泡时的回调，参数为气泡对应的文本
     */
    fun setEntry(
        text: String,
        pinned: Boolean,
        chips: List<ClipboardTextAnalyzer.Entity> = emptyList(),
        onChipClick: (String) -> Unit = {}
    ) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        if (chips.isEmpty()) {
            chipRow.visibility = View.GONE
            chipContainer.removeAllViews()
            return
        }
        chipRow.visibility = View.VISIBLE
        chipContainer.removeAllViews()
        chips.forEach { entity ->
            // 气泡直接显示提取出的实际内容，超长截断（保留完整值用于点击上屏）
            val display = if (entity.value.length > CHIP_MAX_LEN) {
                entity.value.take(CHIP_MAX_LEN) + "…"
            } else {
                entity.value
            }
            val chip = TextView(ctx).apply {
                setText(display)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
                setTextColor(theme.altKeyTextColor)
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(10).toFloat()
                    setColor(theme.genericActiveBackgroundColor)
                }
                isClickable = true
                isFocusable = true
            }
            chip.setOnClickListener { onChipClick(entity.value) }
            chipContainer.addView(
                chip,
                LinearLayout.LayoutParams(WC, WC).apply {
                    rightMargin = ctx.dp(6)
                }
            )
        }
    }
}
