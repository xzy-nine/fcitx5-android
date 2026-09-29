/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入的会话组件（独立输入方案）。
 *
 * 结构对齐 [org.fcitx.fcitx5.android.input.voice.VoiceInputComponent]：
 * - 面板不是独立 `InputWindow`，而是 [panelVisible] 驱动的**覆盖层**（键盘窗口保持 attach，
 *   工具栏可见可用、物理手势不被窗口切换打断）；
 * - 与引擎/模型的交互全部在后台协程，UI 只读 [state]；
 * - 上屏走 [FcitxInputMethodService]：活动字**替换式**上屏（`replaceBeforeCursor`），
 *   点选候选则固化活动字并直接提交该候选。
 *
 * 笔画数据由 Compose 画布持有（逐点变化的每笔都进状态机会造成高频重组），
 * 画布每完成一笔调用 [onStrokesChanged] 触发一次叠写识别。
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.HandwritingSegmenter
import org.fcitx.fcitx5.android.data.handwriting.StrokePoint
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import timber.log.Timber

/** 手写面板的展示态。 */
data class HandwritingUiState(
    /** 模型是否已加载完成（false 时画布只显示「正在加载模型…」）。 */
    val modelReady: Boolean = false,
    /** 模型文件缺失（需要用户去模型市场下载）。 */
    val modelMissing: Boolean = false,
    /** 正在识别。 */
    val recognizing: Boolean = false,
    /** 当前活动字的候选（首选已上屏时也保留，供点选替换）。 */
    val candidates: List<HandwritingCandidate> = emptyList(),
    /** 一次性错误提示（如替换失败/模型损坏）。 */
    val error: String? = null,
)

class HandwritingInputComponent : UniqueComponent<HandwritingInputComponent>(), Dependent,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val service by manager.inputMethodService()

    private val prefs = AppPrefs.getInstance().handwriting

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()

    private val _state = MutableStateFlow(HandwritingUiState())
    val state: StateFlow<HandwritingUiState> = _state.asStateFlow()

    /** 供 `InputView` 直接翻转覆盖层宿主 View 的可见性。 */
    var panelVisibleListener: ((Boolean) -> Unit)? = null

    private val _segmenter = HandwritingSegmenter { strokes, topK ->
        HandwritingEngine.predict(strokes, topK)
    }

    private var recognizeJob: Job? = null
    private var loadJob: Job? = null

    /**
     * 已上屏但**仍可被替换**的活动文本（屏上光标本位置紧邻其左侧）。
     * 手写是持续重写：下一次识别会用新结果整体替换它。
     */
    private var pending: String = ""

    /** 手写输入总开关（工具栏按钮显示条件之一）。 */
    val isEnabled: Boolean get() = prefs.handwritingInputEnabled.getValue()

    /** 模型是否已就绪（模型市场下载完成）。 */
    fun isModelReady(): Boolean =
        HandwritingMarketCategory.isReady(context, prefs.handwritingModelId.getValue())

    // ------------------------------------------------------------------
    // 面板开关
    // ------------------------------------------------------------------

    fun openPanel() {
        _panelVisible.value = true
        panelVisibleListener?.invoke(true)
        ensureModelLoaded()
    }

    fun closePanel() {
        recognizeJob?.cancel()
        pending = ""
        _segmenter.reset()
        _panelVisible.value = false
        panelVisibleListener?.invoke(false)
        _state.value = _state.value.copy(candidates = emptyList(), recognizing = false)
    }

    fun togglePanel() {
        if (_panelVisible.value) closePanel() else openPanel()
    }

    /** 模型加载（幂等；面板打开时调用）。 */
    private fun ensureModelLoaded() {
        if (loadJob?.isActive == true) return
        val modelId = prefs.handwritingModelId.getValue()
        if (HandwritingEngine.isReady && HandwritingEngine.loadedModel() == modelId) {
            _state.value = _state.value.copy(modelReady = true, modelMissing = false)
            return
        }
        loadJob = scope.launch {
            val modelReady = HandwritingMarketCategory.isReady(context, modelId)
            if (!modelReady) {
                _state.value = _state.value.copy(modelReady = false, modelMissing = true)
                return@launch
            }
            val ok = HandwritingEngine.load(context, modelId)
            _state.value = _state.value.copy(modelReady = ok, modelMissing = !ok)
            if (!ok) Timber.w("handwriting: model load failed for $modelId")
        }
    }

    /** IME 销毁时释放会话。 */
    fun release() {
        recognizeJob?.cancel()
        loadJob?.cancel()
        pending = ""
        _segmenter.reset()
        HandwritingEngine.release()
    }

    // ------------------------------------------------------------------
    // 识别与上屏
    // ------------------------------------------------------------------

    /**
     * 画布笔画变化后的识别入口（串行化：新请求取消旧任务，旧结果作废）。
     *
     * @param strokes 当前识别窗口内的笔画（时间序）
     * @param gaps 笔间间隔（gaps[0]=0）
     */
    fun onStrokesChanged(
        strokes: List<List<StrokePoint>>,
        gaps: List<Long>,
    ) {
        recognizeJob?.cancel()
        if (strokes.isEmpty()) {
            _state.value = _state.value.copy(candidates = emptyList(), recognizing = false)
            return
        }
        recognizeJob = scope.launch {
            _state.value = _state.value.copy(recognizing = true)
            val result = _segmenter.recognize(strokes, gaps)
            val segments = result.segments
            if (segments.isEmpty()) {
                _state.value = _state.value.copy(recognizing = false, candidates = emptyList())
                return@launch
            }
            val active = segments.last().candidates
            val activeText = segments.joinToString("") { seg ->
                seg.candidates.firstOrNull()?.char.orEmpty()
            }
            val applied = if (prefs.handwritingAutoCommit.getValue()) {
                withContext(Dispatchers.Main) { applyActiveText(activeText) }
            } else {
                false
            }
            _state.value = _state.value.copy(
                recognizing = false,
                candidates = active,
                error = if (prefs.handwritingAutoCommit.getValue() && !applied) {
                    "上屏失败：光标位置已变化"
                } else {
                    null
                },
            )
        }
    }

    /**
     * 替换式上屏：把屏上 [pending] 整体换成 [newText]。
     * 校验失败（用户移动过光标）时重置活动态，后续识别以追加模式重建。
     */
    private fun applyActiveText(newText: String): Boolean {
        if (pending == newText) return true
        val ok = service.replaceBeforeCursor(pending, newText)
        pending = if (ok) newText else ""
        return ok
    }

    /** 画布清空（停顿定型/手动清空）：活动字固化，不再参与替换。 */
    fun finalizeActive() {
        pending = ""
    }

    /** 撤销一笔后的回滚：当前活动字整段撤销。 */
    fun undoActive() {
        val count = pending.length
        pending = ""
        if (count <= 0) return
        scope.launch { withContext(Dispatchers.Main) { service.deleteBeforeCursor(count) } }
    }

    /** 点选候选：固化当前活动字，并把选中候选直接上屏。 */
    fun selectCandidate(candidate: HandwritingCandidate) {
        pending = ""
        scope.launch {
            withContext(Dispatchers.Main) { service.commitText(candidate.char) }
        }
        _state.value = _state.value.copy(candidates = emptyList())
    }

    /** 清除错误提示。 */
    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    /** 面板可见性联动（供宿主 View 使用）。 */
    fun attachVisibility(listener: (Boolean) -> Unit) {
        panelVisibleListener = listener
    }

    private val serviceRef: FcitxInputMethodService get() = service
}
