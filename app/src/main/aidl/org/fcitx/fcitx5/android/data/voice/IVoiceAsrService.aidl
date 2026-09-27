/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地离线语音识别服务（运行在 :asr 独立进程）。
 *
 * 进程边界只传「模型文件绝对路径 + 16kHz float 采样」——:asr 进程不初始化 AppPrefs /
 * 用户数据目录（见 FcitxApplication 的进程守卫），因此模型定位一律由 app 侧解析后传入。
 */
package org.fcitx.fcitx5.android.data.voice;

import org.fcitx.fcitx5.android.data.voice.IVoiceAsrCallback;

interface IVoiceAsrService {
    /**
     * 创建/复用识别器并开始一次会话。
     *
     * @param encoder/decoder/joiner/tokens 模型文件绝对路径
     * @param useLocal true=本地离线引擎
     * @return 是否成功
     */
    boolean startAsr(
        in String encoder,
        in String decoder,
        in String joiner,
        in String tokens,
        in IVoiceAsrCallback callback
    );

    /** 送入一段 16kHz 单声道 PCM（float，[-1,1]）。返回当前部分结果（可空串）。 */
    String pushAudio(in float[] samples);

    /** 结束本次会话并返回最终文本。 */
    String finishAsr();

    /** 丢弃本次会话（迟到结果不再回传）。 */
    void cancelAsr();

    /** 释放模型句柄（关闭「本地识别」开关时调用）。 */
    void releaseAsr();
}
