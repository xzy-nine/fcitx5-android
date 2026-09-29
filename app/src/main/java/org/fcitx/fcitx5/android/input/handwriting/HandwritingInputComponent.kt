/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入的会话组件（**第三种键盘布局**，不是覆盖层面板）。
 *
 * 结构对齐项目里其它 `UniqueComponent`（如 `VoiceInputComponent`）：由 `KeyboardWindow`
 * 在切到手写布局时持有并驱动，只负责「模型加载 / 叠写识别调度 / 文本上屏 / 候选投喂」，
 * 完全不碰 UI 布局；画布与底部键行见 `HandwritingKeyboardLayout`。
 *
 * 候选去向：**真正的候选栏**。识别结果经 [HandwritingCandidateFeed] 推给 `InputView`，
 * 由后者转成 `CandidateListEvent` 走既有广播链路（`ComposeCandidateComponent` → 候选栏），
 * 因此候选栏的样式、滑动、展开页、点选回调全部复用，不必手写自己画一份。
 *
 * 上屏走 [FcitxInputMethodService]：活动字**替换式**上屏（`replaceBeforeCursor`）。
 */
package org.fcitx.fcitx5.android.input.handwriting

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.CandidateWord
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

/**
 * 手写候选的投喂点：把手写识别候选交给**真正的候选栏**。
 *
 * 做成进程级单例而不是组件成员，是因为消费方 `InputView` 与生产方 `KeyboardWindow`
 * 分属不同组件层级，用单例避免为了传一个状态再建一条依赖边。
 * [words] 与 [candidates] 一一对应，点选时按下标取回语义候选。
 */
object HandwritingCandidateFeed {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var candidates: List<HandwritingCandidate> = emptyList()
        private set

    /** 当前应当展示给候选栏的候选词；空数组表示手写暂无候选。 */
    @Volatile
    var words: Array<CandidateWord> = emptyArray()
        private set

    /**
     * 候选栏投喂器：由 `InputView` 在初始化时挂上（把 [words] 经既有广播链推给
     * `ComposeCandidateComponent`）。**只 set 状态不会让候选栏刷新**，必须调 [publish]。
     */
    @Volatile
    var emitter: ((Array<CandidateWord>) -> Unit)? = null

    /**
     * 把手写候选推给候选栏（每次 [set]/[clear] 之后都会自动调用）。
     *
     * 识别跑在 IO 协程里，而候选栏状态更新应发生在主线程，这里统一投递到主线程。
     */
    fun publish() {
        val sink = emitter ?: return
        val payload = words
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            sink(payload)
        } else {
            mainHandler.post { sink(payload) }
        }
    }

    fun set(candidates: List<HandwritingCandidate>, active: Boolean) {
        val newWords = candidates
            .map { CandidateWord(label = it.char, text = it.char, comment = "") }
            .toTypedArray()
        if (active == isActive && newWords.contentEquals(words)) return
        isActive = active
        this.candidates = candidates
        words = newWords
        publish()
    }

    fun clear() {
        if (!isActive && words.isEmpty()) return
        isActive = false
        candidates = emptyList()
        words = emptyArray()
        publish()
    }
}

/** 手写键盘的展示态。 */
data class HandwritingUiState(
    val modelReady: Boolean = false,
    val modelMissing: Boolean = false,
    val recognizing: Boolean = false,
    val error: String? = null,
)

class HandwritingInputComponent : UniqueComponent<HandwritingInputComponent>(), Dependent,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val service by manager.inputMethodService()

    private val prefs = AppPrefs.getInstance().handwriting

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // 兜底：协程里未捕获的异常（含 native 层 Error）若逃逸会杀掉 IME 进程，
            // 手写出问题只应表现为「识别不可用」。
            Timber.e(throwable, "handwriting: uncaught coroutine exception")
        }
    )

    private val _state = MutableStateFlow(HandwritingUiState())
    val state: StateFlow<HandwritingUiState> = _state.asStateFlow()

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
    // 布局进入 / 离开
    // ------------------------------------------------------------------

    /** 切到本布局时调用：接管候选栏并确保模型已加载。 */
    fun onEnter() {
        HandwritingCandidateFeed.set(emptyList(), active = true)
        ensureModelLoaded()
    }

    /** 离开本布局（切回文本/数字键盘）时调用：交还候选栏。 */
    fun onLeave() {
        recognizeJob?.cancel()
        pending = ""
        _segmenter.reset()
        HandwritingCandidateFeed.clear()
        _state.value = _state.value.copy(recognizing = false)
    }

    private fun ensureModelLoaded() {
        if (loadJob?.isActive == true) return
        val modelId = prefs.handwritingModelId.getValue()
        if (HandwritingEngine.isReady && HandwritingEngine.loadedModel() == modelId) {
            _state.value = _state.value.copy(modelReady = true, modelMissing = false)
            return
        }
        loadJob = scope.launch {
            val ready = HandwritingMarketCategory.isReady(context, modelId)
            if (!ready) {
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
        HandwritingCandidateFeed.clear()
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
            HandwritingCandidateFeed.set(emptyList(), active = true)
            _state.value = _state.value.copy(recognizing = false)
            return
        }
        recognizeJob = scope.launch {
            _state.value = _state.value.copy(recognizing = true)
            val result = _segmenter.recognize(strokes, gaps)
            val segments = result.segments
            if (segments.isEmpty()) {
                HandwritingCandidateFeed.set(emptyList(), active = true)
                _state.value = _state.value.copy(recognizing = false)
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
            HandwritingCandidateFeed.set(active, active = true)
            _state.value = _state.value.copy(
                recognizing = false,
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
        HandwritingCandidateFeed.set(emptyList(), active = true)
    }

    /** 撤销一笔后的回滚：当前活动字整段撤销。 */
    fun undoActive() {
        val count = pending.length
        pending = ""
        HandwritingCandidateFeed.set(emptyList(), active = true)
        if (count <= 0) return
        scope.launch { withContext(Dispatchers.Main) { service.deleteBeforeCursor(count) } }
    }

    /**
     * 候选被点选：固化当前活动字。
     *
     * 候选内容的上屏由候选栏既有链路完成（`onCandidateSelect` → `commitText`），
     * 这里只负责结束「可替换」状态（`index == 0` 时首选已自动上屏，同样只需固化）。
     */
    fun onCandidatePicked() {
        pending = ""
        HandwritingCandidateFeed.set(emptyList(), active = true)
    }
}
