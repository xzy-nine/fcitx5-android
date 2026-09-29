/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写输入的会话组件（**第三种键盘布局**）。
 *
 * 由 `KeyboardWindow` 在切到手写布局时持有并驱动：模型加载、活动区文本上屏、
 * 候选投喂与点选替换；识别窗口与画布见 `HandwritingKeyboardLayout`。
 *
 * 候选经 [HandwritingCandidateFeed] 推给 `InputView` 的候选栏；手写候选不在 fcitx
 * 引擎候选表里，点选由 [pickCandidate] 直接上屏。
 *
 * 上屏走 [FcitxInputMethodService] 的 `replaceBeforeCursor`：活动区文本（[active]）
 * 覆盖当前识别窗口，窗口里最早的字固化出窗（[onSegmentSettled]）后退出可替换区。
 *
 * ⚠️ 本组件的公开回调约定在主线程调用。
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
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.handwriting.HandwritingCandidate
import org.fcitx.fcitx5.android.data.handwriting.HandwritingEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingMarketCategory
import org.fcitx.fcitx5.android.data.handwriting.HandwritingSegmenter
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
     * 识别跑在 Default 协程里，而候选栏状态更新应发生在主线程，这里统一投递到主线程。
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

    /**
     * 画布清空请求（递增）：布局 observe 后清笔画并重置段缓存。
     *
     * 触发点：外部动作固化（空格/回车/退格）、点选替换后、上屏校验失败（光标漂移）。
     */
    private val _clearSignal = MutableStateFlow(0)
    val clearSignal: StateFlow<Int> = _clearSignal.asStateFlow()

    private var loadJob: Job? = null

    /**
     * 屏上「活动区」文本（光标本位置紧邻其左侧）：下一次识别会用最新结果整体替换它。
     *
     * 布局在超长闲置清窗时回调 [finalizeActive] 使其退出可替换区。
     */
    private var active: String = ""

    /** 最近一次识别「最后一段」（当前正在写的字）的文本：候选点选替换它。 */
    private var lastSegText: String = ""

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
        active = ""
        lastSegText = ""
        HandwritingCandidateFeed.set(emptyList(), active = true)
        ensureModelLoaded()
    }

    /** 离开本布局（切回文本/数字键盘）时调用：交还候选栏。 */
    fun onLeave() {
        active = ""
        lastSegText = ""
        HandwritingCandidateFeed.clear()
        _state.value = _state.value.copy(recognizing = false, error = null)
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
            if (!ok) Timber.w("handwriting: model loading failed for $modelId")
        }
    }

    /** IME 销毁时释放会话。 */
    fun release() {
        loadJob?.cancel()
        active = ""
        lastSegText = ""
        HandwritingCandidateFeed.clear()
        HandwritingEngine.release()
    }

    // ------------------------------------------------------------------
    // 识别结果 → 上屏 / 候选
    // ------------------------------------------------------------------

    /** 识别忙闲（状态行刷新）。需在主线程调用。 */
    fun onRecognizing(busy: Boolean) {
        _state.value = _state.value.copy(recognizing = busy)
    }

    /**
     * 识别结果上报（时间序；最后一段=当前正在写的字）。需在主线程调用。
     *
     * 活动区整体重写为各段首选字的拼接，候选栏取最后一段的候选。
     */
    fun onRecognition(segments: List<HandwritingSegmenter.Segment>) {
        if (segments.isEmpty()) {
            HandwritingCandidateFeed.set(emptyList(), active = true)
            return
        }
        val segText = segments.joinToString("") { it.candidates.firstOrNull()?.char.orEmpty() }
        val last = segments.last().candidates.firstOrNull()?.char.orEmpty()
        HandwritingCandidateFeed.set(segments.last().candidates, active = true)
        if (prefs.handwritingAutoCommit.getValue()) {
            val applied = applyActive(segText)
            if (applied) lastSegText = last
            _state.value = _state.value.copy(error = if (applied) null else REPLACE_FAILED)
        } else {
            // 未开启边写边上屏：只在点选候选时上屏
            lastSegText = last
            _state.value = _state.value.copy(error = null)
        }
    }

    /**
     * 最早段固化出窗（滑窗）：其文本已在屏上、退出可替换区。
     * 需在主线程调用（布局侧识别循环里切主线程派发）。
     */
    fun onSegmentSettled(text: String) {
        if (text.isEmpty()) return
        active = if (active.length >= text.length) active.drop(text.length) else ""
    }

    /** 画布撤到空：撤销屏上活动区文本。需在主线程调用。 */
    fun onUndoActive() {
        val count = active.length
        active = ""
        lastSegText = ""
        HandwritingCandidateFeed.set(emptyList(), active = true)
        if (count <= 0) return
        service.deleteBeforeCursor(count)
    }

    /**
     * 布局清窗（超长闲置）后回调：活动文本退出可替换区并清空候选栏，不回传 [clearSignal]。
     * 需在主线程调用。
     */
    fun finalizeActive() {
        active = ""
        lastSegText = ""
        HandwritingCandidateFeed.set(emptyList(), active = true)
    }

    /**
     * 外部动作（空格/回车/退格/离开布局）后的固化：活动区不再可替换，并请布局清窗。
     * 需在主线程调用。
     */
    fun finalizeWindow() {
        finalizeActive()
        requestClear()
    }

    /**
     * 候选点选（下标对应 [HandwritingCandidateFeed.candidates]）。需在主线程调用。
     *
     * 手写候选不在 fcitx 引擎候选表里，`fcitx.select(index)` 选不到，必须在这里替换式上屏：
     * - 开着「边写边上屏」：`index == 0` 只固化（首选已自动上屏）；否则把最后一段的字
     *   （活动区里最后一个字，或已固化的最后一个字）换成点选字；
     * - 未开启：点选即上屏（活动区为空，直接追加）。
     *
     * 点选后**清空候选栏 + 清空识别窗口**（[finalizeWindow]）：字已按用户点选上屏，
     * 不需要继续改选，也不该让旧笔画在下一轮识别里把旧字重复上屏。
     */
    fun pickCandidate(index: Int) {
        val picked = HandwritingCandidateFeed.candidates.getOrNull(index)?.char
        if (picked.isNullOrEmpty()) {
            finalizeWindow()
            return
        }
        if (prefs.handwritingAutoCommit.getValue()) {
            when {
                index <= 0 -> Unit // 首选已自动上屏，点选只固化
                active.isNotEmpty() && lastSegText.isNotEmpty() &&
                        active.length >= lastSegText.length -> {
                    applyActive(active.dropLast(lastSegText.length) + picked)
                }

                lastSegText.isNotEmpty() -> {
                    // 活动区已固化：直接替换屏上最后一个字
                    service.replaceBeforeCursor(lastSegText, picked)
                }

                else -> applyActive(active + picked)
            }
        } else {
            // 未开启边写边上屏：点选即上屏
            service.replaceBeforeCursor(active, active + picked)
        }
        finalizeWindow()
    }

    // ------------------------------------------------------------------
    // 上屏原语
    // ------------------------------------------------------------------

    /**
     * 替换式上屏：把屏上 [active] 整体换成 [newText]。
     *
     * 校验失败（用户移动过光标/文本被外部改动）时清空活动区并把画布一并清掉——
     * 否则窗口里的旧笔画会在下一轮识别时把旧字重复上屏。
     */
    private fun applyActive(newText: String): Boolean {
        if (active == newText) return true
        val ok = service.replaceBeforeCursor(active, newText)
        active = if (ok) newText else ""
        if (!ok) {
            lastSegText = ""
            requestClear()
        }
        return ok
    }

    private fun requestClear() {
        _clearSignal.value += 1
    }

    private companion object {
        const val REPLACE_FAILED = "上屏失败：光标位置已变化"
    }
}
