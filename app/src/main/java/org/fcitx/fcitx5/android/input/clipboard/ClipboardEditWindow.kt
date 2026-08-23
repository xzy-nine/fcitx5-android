/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.ClipData
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.databinding.ClipboardEditWindowBinding
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.abs

/**
 * IME-embedded editor for a clipboard entry (or the most recent entry).
 *
 * Replaces the former standalone [org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity]:
 * word-segment (chip) editing and plain multi-line text editing are both rendered inside the IME
 * window, mirroring the "clipboard entry page" embedding style used by [ClipboardWindow].
 */
class ClipboardEditWindow(
    private val entryId: Int = -1,
    private val useLastEntry: Boolean = false
) : InputWindow.ExtendedInputWindow<ClipboardEditWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme: Theme by manager.theme()

    private lateinit var binding: ClipboardEditWindowBinding

    // 原始文本与分词结果
    private var entryText: String = ""
    private var originalText: String = ""
    private lateinit var segments: List<String>
    private val segmentWords: List<String>
        get() = if (segments.isEmpty()) listOf("") else segments
    private val chipViews = mutableListOf<TextView>()
    private val selectedSet = HashSet<Int>()

    // 触摸/拖选状态（移植自原 Activity：长按震动 + 撤回到按下前选区）
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var downWord = -1
    private var anchorIdx = -1
    private var lastIdx = -1
    private var restoreSelected = BooleanArray(0)

    // 文本 / 分词模式切换
    private var isTextMode = false

    // 主题相关
    private val strokeColor get() = theme.altKeyTextColor
    private val selectedTextColor get() = theme.popupBackgroundColor
    private val selectedBgColor get() = theme.genericActiveBackgroundColor

    override fun onCreateView(): View {
        binding = ClipboardEditWindowBinding.inflate(LayoutInflater.from(context))
        setupUi()
        return binding.root
    }

    override fun onAttached() {
        service.lifecycleScope.launch {
            val entry: ClipboardEntry? = if (useLastEntry) {
                ClipboardManager.lastEntry
            } else {
                withContext(Dispatchers.IO) { ClipboardManager.get(entryId) }
            }
            entryText = entry?.text ?: ""
            originalText = entryText
            withContext(Dispatchers.Main) {
                initData()
                renderChips()
                refreshVisible()
                updatePreview()
            }
        }
    }

    override fun onDetached() {
        handler.removeCallbacksAndMessages(null)
        chipViews.clear()
        selectedSet.clear()
    }

    private fun setupUi() {
        binding.clipboardEditText.doAfterTextChanged {
            if (isTextMode) {
                entryText = it?.toString() ?: ""
                updatePreview()
            }
        }
        binding.clipboardEditSegmentContainer.apply {
            onDown = ::onSegmentDown
            onMove = ::onSegmentMove
            onUp = ::onSegmentUp
        }
        binding.clipboardEditSelectAll.setOnClickListener {
            selectAll()
        }
        binding.clipboardEditInvert.setOnClickListener {
            invertSelection()
        }
        binding.clipboardEditTextMode.setOnClickListener {
            toggleTextMode()
        }
        binding.clipboardEditCopy.setOnClickListener {
            finishEditing(true)
        }
        binding.clipboardEditCancel.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        binding.clipboardEditOk.setOnClickListener {
            finishEditing(false)
        }
    }

    private fun initData() {
        segments = ClipboardTextAnalyzer.segment(entryText)
        if (segments.isEmpty()) {
            segments = listOf("")
        }
        selectedSet.clear()
        binding.clipboardEditText.setText(entryText)
    }

    private fun renderChips() {
        chipViews.clear()
        binding.clipboardEditSegmentContainer.removeAllViews()
        segments.forEachIndexed { index, seg ->
            val tv = createChip(seg, index)
            chipViews.add(tv)
            binding.clipboardEditSegmentContainer.addView(tv)
        }
        updateChipSelection()
    }

    private fun createChip(text: String, index: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
            layoutParams = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            }
            applyChipStyle(this, selectedSet.contains(index))
        }
    }

    private fun applyChipStyle(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) selectedTextColor else strokeColor)
        tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(6).toFloat()
            setStroke(context.dp(1), strokeColor)
            setColor(if (selected) selectedBgColor else Color.TRANSPARENT)
        }
    }

    private fun updateChipStyle(index: Int) {
        chipViews.getOrNull(index)?.let { applyChipStyle(it, selectedSet.contains(index)) }
    }

    private fun updateChipSelection() {
        chipViews.forEachIndexed { index, tv -> applyChipStyle(tv, selectedSet.contains(index)) }
    }

    private fun onSegmentDown(x: Float, y: Float) {
        downX = x
        downY = y
        moved = false
        dragging = false
        downWord = hitWordAt(x, y) ?: (nearestWord(y) ?: -1)
        anchorIdx = downWord
        lastIdx = downWord
        restoreSelected = BooleanArray(segmentWords.size) { selectedSet.contains(it) }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            dragging = true
            InputFeedbacks.hapticFeedback(binding.clipboardEditSegmentContainer, true)
            applyRange(anchorIdx, anchorIdx)
        }, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun onSegmentMove(x: Float, y: Float) {
        if (abs(x - downX) > context.dp(8) || abs(y - downY) > context.dp(8)) moved = true
        if (!dragging) return
        val cur = hitWordAt(x, y) ?: (nearestWord(y) ?: return)
        if (cur == lastIdx) return
        lastIdx = cur
        val newly = HashSet<Int>()
        val lo = minOf(anchorIdx, cur)
        val hi = maxOf(anchorIdx, cur)
        for (i in lo..hi) {
            if (!selectedSet.contains(i)) newly.add(i)
        }
        applyRange(anchorIdx, cur)
        if (newly.isNotEmpty()) {
            InputFeedbacks.hapticFeedback(binding.clipboardEditSegmentContainer, false)
        }
    }

    private fun onSegmentUp() {
        handler.removeCallbacksAndMessages(null)
        if (!dragging && !moved && downWord >= 0) {
            toggleWord(downWord)
        }
        dragging = false
        anchorIdx = -1
        lastIdx = -1
    }

    private fun hitWordAt(x: Float, y: Float): Int? {
        for (i in chipViews.indices) {
            val v = chipViews[i]
            val left = v.left.toFloat()
            val top = v.top.toFloat()
            val right = left + v.width
            val bottom = top + v.height
            if (x >= left && x <= right && y >= top && y <= bottom) {
                return i
            }
        }
        return null
    }

    private fun nearestWord(y: Float): Int? {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in chipViews.indices) {
            val v = chipViews[i]
            val center = v.top + v.height / 2f
            val d = abs(center - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun applyRange(lo: Int, hi: Int) {
        if (lo < 0 || hi < 0) return
        val from = minOf(lo, hi)
        val to = maxOf(lo, hi)
        for (i in segmentWords.indices) {
            val target = if (i in from..to) true else restoreSelected.getOrElse(i) { false }
            setSelected(i, target)
        }
        updatePreview()
    }

    private fun setSelected(i: Int, selected: Boolean) {
        val contains = selectedSet.contains(i)
        if (contains == selected) return
        if (selected) selectedSet.add(i) else selectedSet.remove(i)
        updateChipStyle(i)
    }

    private fun toggleWord(i: Int) {
        setSelected(i, !selectedSet.contains(i))
        updatePreview()
    }

    private fun selectAll() {
        selectedSet.clear()
        for (i in segmentWords.indices) selectedSet.add(i)
        updateChipSelection()
        updatePreview()
    }

    private fun invertSelection() {
        val next = HashSet<Int>()
        for (i in segmentWords.indices) {
            if (i !in selectedSet) next.add(i)
        }
        selectedSet.clear()
        selectedSet.addAll(next)
        updateChipSelection()
        updatePreview()
    }

    private fun toggleTextMode() {
        isTextMode = !isTextMode
        refreshVisible()
    }

    private fun refreshVisible() {
        if (isTextMode) {
            val rebuilt = getCurrentText()
            binding.clipboardEditText.setText(rebuilt.ifBlank { originalText })
            binding.clipboardEditText.visibility = View.VISIBLE
            binding.clipboardEditSegmentContainer.visibility = View.GONE
            binding.clipboardEditPreview.visibility = View.GONE
            binding.clipboardEditSelectAll.visibility = View.GONE
            binding.clipboardEditInvert.visibility = View.GONE
            binding.clipboardEditTextMode.setText(R.string.clipboard_edit_text_mode)
        } else {
            binding.clipboardEditText.visibility = View.GONE
            binding.clipboardEditSegmentContainer.visibility = View.VISIBLE
            binding.clipboardEditPreview.visibility = View.VISIBLE
            binding.clipboardEditSelectAll.visibility = View.VISIBLE
            binding.clipboardEditInvert.visibility = View.VISIBLE
            binding.clipboardEditTextMode.setText(R.string.clipboard_edit_text_mode)
        }
    }

    private fun getCurrentText(): String {
        return if (isTextMode) {
            binding.clipboardEditText.text.toString()
        } else {
            buildString {
                segmentWords.forEachIndexed { index, seg ->
                    if (selectedSet.contains(index)) append(seg)
                }
            }
        }
    }

    private fun updatePreview() {
        if (isTextMode) {
            binding.clipboardEditPreview.visibility = View.GONE
            return
        }
        val result = getCurrentText()
        if (result.isEmpty()) {
            binding.clipboardEditPreview.visibility = View.GONE
        } else {
            binding.clipboardEditPreview.visibility = View.VISIBLE
            binding.clipboardEditPreview.text = result
        }
    }

    private fun finishEditing(copy: Boolean) {
        val str = getCurrentText()
        service.lifecycleScope.launch {
            ClipboardManager.updateText(entryId, str)
            if (copy) {
                runCatching { context.clipboardManager.setPrimaryClip(ClipData.newPlainText("", str)) }
            }
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override val title: String by lazy { context.getString(R.string.edit_clipboard) }
}
