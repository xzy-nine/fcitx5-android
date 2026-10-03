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
 * 手写识别引擎。**声明顺序 = 默认优先级**：系统内置 → 谷歌数字墨水。
 *
 * 两者都是「整段墨迹 → 文本」型引擎（自带整句/多字识别），不需要调用侧做切分；
 * 下拉里选中的引擎优先，不可用（设备不支持 / 模型没下载 / 连续无结果）时按
 * [chainFrom] 继续回落；两条输入路径（手写键盘画布 / 触控笔墨迹窗口）共用同一条链。
 */
enum class HandwritingEngineKind(override val stringRes: Int) : ManagedPreferenceEnum {

    /** 系统内置引擎（小米随手写，反射系统 jar）：整段墨迹 → 单结果；另提供触控笔手势。 */
    System(R.string.handwriting_engine_system),

    /** 谷歌数字墨水（ML Kit digital-ink）：整段墨迹 → 文本候选；语言模型需下载（中文随包内置）。 */
    GoogleDigitalInk(R.string.handwriting_engine_google);

    companion object {

        /**
         * 回落链：选中项在链首，其后按声明顺序补齐。
         *
         * 例：选 [System] = 系统 → 谷歌；选 [GoogleDigitalInk] 只有谷歌。
         */
        fun chainFrom(selected: HandwritingEngineKind): List<HandwritingEngineKind> =
            entries.drop(entries.indexOf(selected))
    }
}
