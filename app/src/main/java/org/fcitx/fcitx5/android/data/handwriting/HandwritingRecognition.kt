/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别的**统一入口**（普通手写键盘与触控笔手写共用）。
 *
 * 两条输入路径只负责收集笔迹（键盘画布 / 系统墨迹窗口），识别后端的选择集中在这里：
 * 设置项 `AppPrefs.handwriting.handwritingEngine`（下拉）决定**首选引擎**，不可用时按
 * [HandwritingEngineKind.chainFrom] 的链回落（系统内置 → 谷歌数字墨水）：
 * - **系统内置**（小米随手写，反射系统 jar）：整段墨迹 → 单结果，另提供触控笔手势；
 * - **谷歌数字墨水**（ML Kit digital-ink）：整段墨迹 → 文本候选，中文模型随包内置。
 *
 * 两个引擎都是「整段墨迹 → 文本」型（自带整句/多字识别），因此**不做切分**：
 * 每次识别把当前窗口的整段墨迹交给链上第一个可用的引擎。
 *
 * 「系统引擎连续空结果」与「谷歌模型没下载」都只是**本引擎本次不可用**，链继续往下走。
 *
 * ⚠️ 引擎推理不能占主线程：[recognize] 与 [prepare] 自身切后台线程，调用方直接调用即可。
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

    /** 单次识别默认候选数（触控笔路径传自己的值）。 */
    const val DEFAULT_TOP_K = 8

    /**
     * 系统引擎连续返回空结果多少次后放弃（本进程内不再尝试）。
     *
     * 非白名单包名下引擎会「构造成功但恒返回空」；连续几次即可判定不值得再试，
     * 后续识别直接从链上的下一个引擎开始。
     */
    private const val SYSTEM_ENGINE_GIVE_UP = 3

    /** 系统引擎只给文本、不给概率，补一个固定置信度作首选排序用。 */
    const val SYSTEM_ENGINE_SCORE = 0.95f

    /** 推理串行化：两条路径可能同时在跑（同一个识别器实例 / 同一个引擎实例）。 */
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

    /** 谷歌数字墨水模型是否已下载/已内置（最近一次探测结果）。 */
    @Volatile
    private var googleModelReady = false

    /** 最近一次实际产出结果的引擎（状态行回显用；还没出过结果时为 null）。 */
    @Volatile
    var lastUsedEngine: HandwritingEngineKind? = null
        private set

    // ------------------------------------------------------------------
    // 引擎选择
    // ------------------------------------------------------------------

    /** 设置项里选中的首选引擎。 */
    fun selectedEngine(): HandwritingEngineKind =
        AppPrefs.getInstance().handwriting.handwritingEngine.getValue()

    /** 本次生效的回落链（选中项优先 + 其后按声明顺序）。 */
    fun engineChain(): List<HandwritingEngineKind> =
        HandwritingEngineKind.chainFrom(selectedEngine())

    /**
     * 系统内置引擎是否参与本次选择（引擎链里含它）。
     *
     * 触控笔手势只有系统引擎能给，故手势能力也随该判定开关（选了谷歌时手势退回本地
     * 几何启发式，见 `StylusHandwritingController`）。
     */
    fun systemEngineRequested(): Boolean = engineChain().contains(HandwritingEngineKind.System)

    /** 系统手写引擎是否已就绪（状态查询，不触发初始化）。 */
    val isSystemEngineReady: Boolean get() = systemEngineReady

    /** 系统内置引擎是否**正在使用**（在引擎链里 + 已就绪）。 */
    fun systemEngineInUse(): Boolean = systemEngineReady && systemEngineRequested()

    /** 谷歌数字墨水模型是否已就绪（最近一次探测结果）。 */
    val isGoogleModelReady: Boolean get() = googleModelReady

    /**
     * 识别后端是否已就绪：**引擎链上任一后端当前可用即可**（状态查询，不触发初始化）。
     */
    val backendReady: Boolean get() = systemEngineReady || googleModelReady

    /** 当前实际可用的引擎（链上第一个就绪的；都不可用时 null）。 */
    fun activeEngine(): HandwritingEngineKind? = engineChain().firstOrNull { isReady(it) }

    /** 某个引擎当前是否可用（不含触发初始化的动作）。 */
    private fun isReady(kind: HandwritingEngineKind): Boolean = when (kind) {
        HandwritingEngineKind.System -> systemEngineReady
        HandwritingEngineKind.GoogleDigitalInk -> googleModelReady
    }

    // ------------------------------------------------------------------
    // 后端准备
    // ------------------------------------------------------------------

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
        if (!systemEngineRequested()) return false
        return synchronized(initLock) {
            if (systemEngineReady) return@synchronized true
            if (systemEngineGaveUp) return@synchronized false
            if (!systemEngineRequested()) return@synchronized false
            val ok = XiaomiHandwritingEngine.open(context)
            systemEngineReady = ok
            if (ok) {
                Timber.i("$TAG: system handwriting engine ready")
            } else {
                Timber.i("$TAG: system handwriting engine unavailable, trying next engine")
            }
            ok
        }
    }

    /** 刷新谷歌数字墨水模型状态（设置页下载完成后调用，让识别立刻用上）。 */
    suspend fun refreshGoogleModel(context: Context) {
        googleModelReady = GoogleDigitalInkEngine.isModelDownloaded(context)
    }

    /**
     * 按引擎链把识别后端准备到**第一个可用**的（幂等）。
     *
     * @return 准备好的引擎；链上全不可用时 null（画布显示「引擎不可用」）
     */
    suspend fun prepare(context: Context): HandwritingEngineKind? = withContext(Dispatchers.IO) {
        for (kind in engineChain()) {
            when (kind) {
                HandwritingEngineKind.System ->
                    if (ensureSystemEngine(context)) return@withContext HandwritingEngineKind.System

                HandwritingEngineKind.GoogleDigitalInk -> {
                    refreshGoogleModel(context)
                    if (googleModelReady) return@withContext HandwritingEngineKind.GoogleDigitalInk
                }
            }
        }
        Timber.w("$TAG: no engine available in chain ${engineChain()}")
        null
    }

    // ------------------------------------------------------------------
    // 识别
    // ------------------------------------------------------------------

    /**
     * 统一识别入口：**整段墨迹** → 候选列表（按分数降序）。
     *
     * 沿引擎链依次尝试，第一个给出结果的引擎即返回；全都没结果时返回空表。
     *
     * @param strokes 笔画序列（画布坐标，顺序即时间序）
     */
    suspend fun recognize(
        context: Context,
        strokes: List<List<StrokePoint>>,
        topK: Int = DEFAULT_TOP_K,
    ): List<HandwritingCandidate> = withContext(Dispatchers.Default) {
        if (strokes.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            for (kind in engineChain()) {
                when (kind) {
                    HandwritingEngineKind.System -> {
                        if (systemEngineGaveUp) continue
                        if (!ensureSystemEngine(context)) continue
                        val text = XiaomiHandwritingEngine.recognizeText(strokes)
                        if (!text.isNullOrEmpty()) {
                            systemEmptyStreak = 0
                            lastUsedEngine = kind
                            return@withLock listOf(
                                HandwritingCandidate(text, SYSTEM_ENGINE_SCORE)
                            )
                        }
                        systemEmptyStreak++
                        Timber.d(
                            "$TAG: system engine returned nothing (%d/%d), trying next engine",
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

                    HandwritingEngineKind.GoogleDigitalInk -> {
                        if (!googleModelReady) refreshGoogleModel(context)
                        if (!googleModelReady) continue
                        val candidates =
                            GoogleDigitalInkEngine.recognize(context, strokes, topK)
                        if (candidates.isNotEmpty()) {
                            lastUsedEngine = kind
                            return@withLock candidates
                        }
                    }
                }
            }
            emptyList()
        }
    }

    /** 释放两个后端（IME 销毁时调用；幂等）。 */
    fun release() {
        systemEngineReady = false
        systemEngineGaveUp = false
        systemEmptyStreak = 0
        googleModelReady = false
        lastUsedEngine = null
        XiaomiHandwritingEngine.close()
        GoogleDigitalInkEngine.close()
    }
}
