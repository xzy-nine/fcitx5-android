/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.databinding.ActivityClipboardEditBinding
import org.fcitx.fcitx5.android.input.clipboard.ClipboardTextAnalyzer
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str
import splitties.dimensions.dp

class ClipboardEditActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var editText: EditText
    private lateinit var binding: ActivityClipboardEditBinding

    private var entryId: Int = -1
    private var originalText: String = ""

    // 分词编辑状态：词块列表 + 各自是否选中
    private val segmentWords = mutableListOf<String>()
    private val segmentSelected = mutableListOf<Boolean>()
    // 记录词块 View 与索引映射，供横滑拖选命中检测
    private val chipViews = mutableListOf<TextView>()

    private val theme by lazy { ThemeManager.activeTheme }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardEditBinding.inflate(layoutInflater).apply {
            editText = clipboardEditText
            clipboardEditCancel.setOnClickListener { finish() }
            clipboardEditOk.setOnClickListener { finishEditing() }
            clipboardEditCopy.setOnClickListener { finishEditing(copy = true) }
            clipboardEditTextMode.setOnClickListener { switchToTextMode() }
            clipboardEditSelectAll.setOnClickListener { selectAll() }
            clipboardEditInvert.setOnClickListener { invertSelection() }
        }
        // 分词区域触摸：由 SegmentContainer 统一处理（单击切换 / 长按进入拖选 / 滑动连选）
        setupSegmentTouch()
        setContentView(binding.root)
        processIntent(intent)
    }

    // ---- 分词编辑模式 ----

    private fun enterSegmentMode(text: String) {
        originalText = text
        segmentWords.clear()
        segmentSelected.clear()
        ClipboardTextAnalyzer.segment(text).forEach {
            segmentWords.add(it)
            segmentSelected.add(true)
        }
        renderSegmentChips()
        updatePreview()
    }

    private fun renderSegmentChips() {
        binding.clipboardEditSegmentContainer.removeAllViews()
        chipViews.clear()
        if (segmentWords.isEmpty()) {
            val tip = TextView(this).apply {
                text = getString(R.string.clipboard_edit_no_segment)
                setTextColor(theme.altKeyTextColor)
                textSize = 13f
            }
            binding.clipboardEditSegmentContainer.addView(tip)
            return
        }
        segmentWords.forEachIndexed { i, word ->
            val chip = TextView(this).apply {
                text = word
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isSelected = segmentSelected[i]
                applyChipStyle(this, segmentSelected[i])
            }
            val lp = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(6), dp(6))
            }
            binding.clipboardEditSegmentContainer.addView(chip, lp)
            chipViews.add(chip)
        }
    }

    private fun applyChipStyle(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) theme.popupBackgroundColor else theme.altKeyTextColor)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) theme.genericActiveBackgroundColor else Color.TRANSPARENT)
            setStroke(dp(1), theme.altKeyTextColor)
        }
    }

    private fun toggleWord(i: Int) {
        segmentSelected[i] = !segmentSelected[i]
        chipViews.getOrNull(i)?.let { applyChipStyle(it, segmentSelected[i]) }
        updatePreview()
    }

    private fun selectAll() {
        segmentSelected.indices.forEach { segmentSelected[it] = true }
        chipViews.forEachIndexed { i, v -> applyChipStyle(v, true) }
        updatePreview()
    }

    // 反选：取消当前选中的、选中之前未选的
    private fun invertSelection() {
        segmentSelected.indices.forEach { segmentSelected[it] = !segmentSelected[it] }
        chipViews.forEachIndexed { i, v -> applyChipStyle(v, segmentSelected[i]) }
        updatePreview()
    }

    // 行选/连续词块拖选：长按进入拖选，从锚点词到手指所在词之间的「连续词块」全部选中；
    // 向上拖回则超出范围的词块撤销回进入拖选前的状态。每次扩展出新选中词块均震动反馈。
    private var dragging = false
    private var downWord = -1
    private var anchorIdx = -1
    private var lastIdx = -1
    private var restoreSelected = BooleanArray(0)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private fun setupSegmentTouch() {
        val container = binding.clipboardEditSegmentContainer
        container.onDown = { x, y ->
            downX = x
            downY = y
            dragging = false
            moved = false
            downWord = hitWordAt(x, y) ?: (nearestWord(y) ?: -1)
            anchorIdx = downWord
            lastIdx = downWord
            restoreSelected = segmentSelected.toBooleanArray()
            // 长按定时器：达到阈值进入拖选（复用按键长按反馈参数）
            longPressRunnable = Runnable {
                dragging = true
                InputFeedbacks.hapticFeedback(container, longPress = true)
                // 入场：仅选中锚点词，其余保持原状
                applyRange(anchorIdx, anchorIdx)
            }
            handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
        }
        container.onMove = { x, y ->
            if (!dragging) {
                if (Math.abs(x - downX) > 8 || Math.abs(y - downY) > 8) moved = true
            } else {
                val cur = hitWordAt(x, y) ?: nearestWord(y)
                if (cur != null && cur != lastIdx) {
                lastIdx = cur
                val lo = minOf(anchorIdx, cur)
                val hi = maxOf(anchorIdx, cur)
                var newlySelected = false
                for (i in segmentSelected.indices) {
                    val target = if (i in lo..hi) true else restoreSelected[i]
                    if (target && !segmentSelected[i]) newlySelected = true
                    setSelected(i, target)
                }
                updatePreview()
                // 本次扩展出新选中的词块才震动（短按反馈）
                if (newlySelected) InputFeedbacks.hapticFeedback(container, longPress = false)
                }
            }
        }
        container.onUp = {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
            // 未进入拖选、且基本未移动 → 视为单击，切换该词块选中状态
            if (!dragging && !moved && downWord >= 0) toggleWord(downWord)
            dragging = false
        }
    }

    // 仅在 [lo,hi] 范围内强制选中，范围外恢复进入拖选前的状态
    private fun applyRange(lo: Int, hi: Int) {
        for (i in segmentSelected.indices) {
            setSelected(i, if (i in lo..hi) true else restoreSelected[i])
        }
        updatePreview()
    }

    private fun setSelected(i: Int, selected: Boolean) {
        if (segmentSelected[i] == selected) return
        segmentSelected[i] = selected
        chipViews.getOrNull(i)?.let { applyChipStyle(it, selected) }
    }

    private fun hitWordAt(x: Float, y: Float): Int? {
        for (i in chipViews.indices) {
            val v = chipViews[i]
            if (x >= v.left && x <= v.right && y >= v.top && y <= v.bottom) return i
        }
        return null
    }

    // 手指落在词块间隙时，取垂直方向最接近的词块（用于定位行/词）
    private fun nearestWord(y: Float): Int? {
        if (chipViews.isEmpty()) return null
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in chipViews.indices) {
            val v = chipViews[i]
            val cy = (v.top + v.bottom) / 2f
            val d = Math.abs(cy - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun selectWord(i: Int, selected: Boolean) {
        if (segmentSelected[i] == selected) return
        segmentSelected[i] = selected
        chipViews.getOrNull(i)?.let { applyChipStyle(it, selected) }
        updatePreview()
    }

    private fun updatePreview() {
        val rebuilt = segmentWords.filterIndexed { i, _ -> segmentSelected[i] }.joinToString("")
        binding.clipboardEditPreview.text = rebuilt
    }

    // ---- 文本编辑模式 ----

    private fun switchToTextMode() {
        val rebuilt = segmentWords.filterIndexed { i, _ -> segmentSelected[i] }.joinToString("")
        binding.clipboardEditText.setText(rebuilt.ifBlank { originalText })
        binding.clipboardEditSegmentContainer.visibility = View.GONE
        binding.clipboardEditPreview.visibility = View.GONE
        binding.clipboardEditSelectAll.visibility = View.GONE
        binding.clipboardEditInvert.visibility = View.GONE
        binding.clipboardEditTextMode.visibility = View.GONE
        binding.clipboardEditText.visibility = View.VISIBLE
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishEditing(copy: Boolean = false) {
        val str = if (binding.clipboardEditText.visibility == View.VISIBLE) {
            editText.str
        } else {
            segmentWords.filterIndexed { i, _ -> segmentSelected[i] }.joinToString("")
        }
        scope.launch {
            ClipboardManager.updateText(entryId, str)
            if (copy) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", str))
            }
        }
        finish()
    }

    private fun setEntry(entry: ClipboardEntry) {
        entryId = entry.id
        // 默认进入分词编辑模式
        enterSegmentMode(entry.text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                if (getBooleanExtra(LAST_ENTRY, false)) {
                    ClipboardManager.lastEntry
                } else {
                    ClipboardManager.get(getIntExtra(ENTRY_ID, -1))
                }
            }?.let { setEntry(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTRY_ID = "id"
        const val LAST_ENTRY = "last_entry"
    }
}
