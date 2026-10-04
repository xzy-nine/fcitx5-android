/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 模型市场的分类 id 常量。
 *
 * 各分类的清单一律内置（或由分类自己从本地文件派生），不联网拉取；
 * 因此这里只是「路由参数 / 注册表键」，与任何远程字段无关。
 */
package org.fcitx.fcitx5.android.data.market

object MarketCategoryIds {

    /** 语音识别模型（sherpa-onnx 流式 zipformer2 transducer 导出包）。 */
    const val ASR = "asr"

    /** 谷歌数字墨水语言模型（ML Kit 托管，清单为官方语言表）。 */
    const val DIGITAL_INK = "digitalink"
}
