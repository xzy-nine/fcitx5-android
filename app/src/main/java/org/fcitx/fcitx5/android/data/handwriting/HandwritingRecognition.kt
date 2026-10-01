/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别的**统一入口**（普通手写键盘与触控笔手写共用）。
 *
 * 两条输入路径只负责收集笔迹（键盘画布 / 系统墨迹窗口），识别后端的选择集中在这里：
 * - **系统手写引擎**（小米随手写，反射，见 [XiaomiHandwritingEngine]）优先：
 *   整段墨迹 → 单结果文本，无候选列表；
 * - 不可用（非小米设备 / 未装引擎 / 开关关闭 / 连续空结果已降级）时回落到
 *   **自带 ONNX 模型**（[HandwritingEngine]，单字分类器）取 top-k 候选。
 *
 * 「优先使用系统手写引擎」开关（`AppPrefs.handwriting.handwritingSystemEngineEnabled`）
 * 在本类里读取，故该设置对**所有路径**一致生效（过去只有触控笔路径读它）。
 *
 * ⚠️ 引擎推理（反射 + TFLite / ONNX Runtime）不能占主线程：[recognize] 与 [prepare]
 * 自身切 [Dispatchers.Default]，调用方在主线程直接调用即可（仍是挂起函数，不阻塞 UI）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

object HandwritingRecognition {

    private const val TAG = "HandwritingRecognition"

    /**
     * 系统引擎连续返回空结果多少次后放弃（改走 ONNX）。
     *
     * 非白名单包名下引擎会「构造成功但恒返回空」；连续几次即可判定不值得再试。
     */
    private const val SYSTEM_ENGINE_GIVE_UP = 3

    /** 系统引擎只给文本、不给概率，补一个固定置信度作首选排序用。 */
    const val SYSTEM_ENGINE_SCORE = 0.95f

    /** 推理串行化：两条路径可能同时在跑（同一个 ONNX 会话 / 同一个引擎实例）。 */
    private val mutex = Mutex()

    /** 系统引擎初始化锁（[ensureSystemEngine] 是阻塞式的一次性初始化）。 */
    private val initLock = Any()

    /** 系统手写引擎是否已初始化且**文字识别**可用（[ensureSystemEngine] 成功）。 */
    @Volatile
    private var systemEngineReady = false

    /** 已放弃系统引擎（连续空结果，通常是包名不在引擎白名单内）。 */
    @Volatile
    private var systemEngineGaveUp = false

    /** 系统引擎连续返回空的次数（用于自降级判定）。 */
    private var systemEmptyStreak = 0

    /** 系统手写引擎是否已就绪（状态查询，不触发初始化）。 */
    val isSystemEngineReady: Boolean get() = systemEngineReady

    /**
     * 识别后端是否已就绪：**系统手写引擎或自带 ONNX 模型任一可用即可**。
     *
     * 注意不能只看 [HandwritingEngine.isReady]：系统引擎可用时不会加载 ONNX（省内存），
     * 此时它是 false，只看它会把手写整条路径挡在门外。
     */
    val backendReady: Boolean get() = systemEngineReady || HandwritingEngine.isReady

    /** 是否优先使用系统手写引擎（设置项；所有路径共用）。 */
    fun systemEngineEnabled(): Boolean =
        AppPrefs.getInstance().handwriting.handwritingSystemEngineEnabled.getValue()

    /** 系统手写引擎是否**正在使用**（开关开启 + 已就绪）。 */
    fun systemEngineInUse(): Boolean = systemEngineReady && systemEngineEnabled()

    /**
     * 初始化系统手写引擎（幂等，**同步**）。成功时 [isSystemEngineReady] 置位。
     *
     * ⚠️ 只在**非主线程**调用：facade 构造会加载算法库与模型（`RecognizeFacade` 8.8MB
     * 算法库 + 17MB 模型），主线程调用会卡住 IME。主线程路径请用 [prepare]（挂起）。
     *
     * 引擎自带包名白名单（只服务搜狗/百度/讯飞小米版等 12 个包名），
     * 由 [XiaomiHandwritingEngine.open] 内部反射放行，这里无需额外判断。
     */
    fun ensureSystemEngine(context: Context): Boolean {
        if (systemEngineReady) return true
        if (systemEngineGaveUp) return false
        if (!systemEngineEnabled()) return false
        return synchronized(initLock) {
            if (systemEngineReady) return@synchronized true
            if (systemEngineGaveUp) return@synchronized false
            if (!systemEngineEnabled()) return@synchronized false
            val ok = XiaomiHandwritingEngine.open(context)
            systemEngineReady = ok
            if (ok) {
                Timber.i("$TAG: system handwriting engine ready")
            } else {
                Timber.i("$TAG: system handwriting engine unavailable, using ONNX")
            }
            ok
        }
    }

    /**
     * 按当前偏好把识别后端准备到可用（幂等）：**系统引擎优先，否则加载 ONNX 模型**。
     *
     * 系统引擎可用时**不加载 ONNX**（省内存与启动时间）；开关关闭或引擎不可用才回落。
     * 两条路径（键盘布局进入 / 触控笔预热）共用本方法，保证「引擎选择」口径一致。
     *
     * @return 是否至少有一个可用后端
     */
    suspend fun prepare(context: Context, modelId: String): Boolean = withContext(Dispatchers.IO) {
        if (systemEngineEnabled() && ensureSystemEngine(context)) return@withContext true
        if (HandwritingEngine.isReady && HandwritingEngine.loadedModel() == modelId) {
            return@withContext true
        }
        if (!HandwritingModelStore.isReady(context, modelId)) {
            Timber.w("$TAG: no backend available (system engine off/unavailable, model %s missing)", modelId)
            return@withContext false
        }
        HandwritingEngine.load(context, modelId)
    }

    /**
     * 统一识别入口：笔画序列 → 候选列表（按分数降序）。
     *
     * 系统引擎优先（**只给单结果**，候选表就一项）；不可用或无结果时回落自带 ONNX 模型
     * 取前 [topK] 名。ONNX 模型是**单字分类器**（无 CTC、不做多字解码），
     * 多字切分由 `HandwritingSegmenter` 在调用侧完成（每个切分段单独走本入口）。
     *
     * @param strokes 笔画序列（画布坐标，顺序即时间序）
     */
    suspend fun recognize(
        context: Context,
        strokes: List<List<StrokePoint>>,
        topK: Int = HandwritingEngine.DEFAULT_TOP_K,
    ): List<HandwritingCandidate> = withContext(Dispatchers.Default) {
        if (strokes.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            if (systemEngineEnabled() && !systemEngineGaveUp && ensureSystemEngine(context)) {
                val text = XiaomiHandwritingEngine.recognizeText(strokes)
                if (!text.isNullOrEmpty()) {
                    systemEmptyStreak = 0
                    return@withLock listOf(HandwritingCandidate(text, SYSTEM_ENGINE_SCORE))
                }
                systemEmptyStreak++
                Timber.d(
                    "$TAG: system engine returned nothing (%d/%d), falling back to ONNX",
                    systemEmptyStreak, SYSTEM_ENGINE_GIVE_UP,
                )
                if (systemEmptyStreak >= SYSTEM_ENGINE_GIVE_UP) {
                    systemEngineGaveUp = true
                    Timber.w(
                        "$TAG: system engine gave up after %d empty results",
                        SYSTEM_ENGINE_GIVE_UP,
                    )
                }
            }
            // 已降级但 ONNX 还没加载（系统引擎可用时 [prepare] 会跳过加载）：补一次，
            // 否则回落目标不存在、识别恒为空。模型缺失时 [prepare] 会快速返回 false。
            if (systemEngineGaveUp && !HandwritingEngine.isReady) {
                prepare(context, AppPrefs.getInstance().handwriting.handwritingModelId.getValue())
            }
            if (!HandwritingEngine.isReady) return@withLock emptyList()
            HandwritingEngine.predict(strokes, topK)
        }
    }

    /** 释放两个后端（IME 销毁时调用；幂等）。 */
    fun release() {
        systemEngineReady = false
        systemEngineGaveUp = false
        systemEmptyStreak = 0
        XiaomiHandwritingEngine.close()
        HandwritingEngine.release()
    }
}
