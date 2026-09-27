/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地离线语音识别（官方 sherpa-onnx）结果回调。
 * 与 :asr 进程的通信只回传文本与就绪状态，不回传状态机——状态由 IME 进程侧维护。
 */
package org.fcitx.fcitx5.android.data.voice;

oneway interface IVoiceAsrCallback {
    /** 识别中的部分结果（可空串）。 */
    void onPartial(in String text);

    /** 引擎错误（模型加载失败等）。 */
    void onError(in String message);

    /** 预加载回执（[IVoiceAsrService.prepareAsr] 用；面板据此结束「正在加载模型…」）。 */
    void onEngineReady(boolean ok, in String message);
}
