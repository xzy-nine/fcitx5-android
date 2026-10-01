/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 手写识别的引擎取值与回落链（设置项「手写识别引擎」）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 手写识别引擎。**声明顺序 = 默认优先级**：系统内置 → 谷歌数字墨水 → 内置 ONNX 模型。
 *
 * 下拉里选中的引擎优先，不可用（设备不支持 / 模型没下载 / 连续无结果）时按
 * [chainFrom] 继续回落；两条输入路径（手写键盘画布 / 触控笔墨迹窗口）共用同一条链。
 */
enum class HandwritingEngineKind(override val stringRes: Int) : ManagedPreferenceEnum {

    /** 系统内置引擎（小米随手写，反射系统 jar）：整段墨迹 → 单结果；另提供触控笔手势。 */
    System(R.string.handwriting_engine_system),

    /** 谷歌数字墨水（ML Kit digital-ink）：整段墨迹 → 文本候选；语言模型需手动下载。 */
    GoogleDigitalInk(R.string.handwriting_engine_google),

    /** 内置 ONNX 模型（ochwpro 单字分类器）：可叠写切分、给 top-k 候选，走模型市场下载。 */
    Onnx(R.string.handwriting_engine_onnx);

    /**
     * 是否「整段墨迹 → 文本」型引擎（自带整句/多字识别）。
     *
     * 这类引擎按整窗送识别即可，不需要本项目的叠写切分（`HandwritingSegmenter`）；
     * 且它们是**整段出结果**的，逐段切分只会白跑很多次推理。
     */
    val wholeInk: Boolean get() = this != Onnx

    companion object {

        /**
         * 回落链：选中项在链首，其后按声明顺序补齐。
         *
         * 例：选 [System] = 系统 → 谷歌 → ONNX；选 [GoogleDigitalInk] = 谷歌 → ONNX；
         * 选 [Onnx] 只有 ONNX（它是链尾，没有更弱的回落目标）。
         */
        fun chainFrom(selected: HandwritingEngineKind): List<HandwritingEngineKind> =
            entries.drop(entries.indexOf(selected))
    }
}
