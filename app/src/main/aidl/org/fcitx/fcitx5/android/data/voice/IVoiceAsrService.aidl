/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 本地离线语音识别服务（运行在 :asr 独立进程）。
 *
 * 进程边界只传「已判定的模型家族 + 角色→文件绝对路径 + 多语参数 + 16kHz float 采样」——
 * :asr 进程不初始化 AppPrefs / 用户数据目录（见 FcitxApplication 的进程守卫），
 * 因此模型定位与**家族判定**一律由 app 侧完成后传入：家族判错会让原生构造函数直接
 * 结束 :asr 进程，所以这里不接受「让服务自己猜」的用法。
 */
package org.fcitx.fcitx5.android.data.voice;

import org.fcitx.fcitx5.android.data.voice.IVoiceAsrCallback;

interface IVoiceAsrService {
    /**
     * 创建/复用识别器并开始一次会话。
     *
     * @param family 模型家族名（[org.fcitx.fcitx5.android.data.voice.VoiceModelFamily] 的 name）
     * @param roles 文件角色名数组（与 [paths] 一一对应，含 tokens）
     * @param paths 模型文件绝对路径数组
     * @param language 多语模型（Whisper / SenseVoice）的语种提示，空 = 用默认
     * @param inverseTextNormalization SenseVoice 的逆文本正则化开关
     * @return 是否成功
     */
    boolean startAsr(
        in String family,
        in String[] roles,
        in String[] paths,
        in String language,
        in boolean inverseTextNormalization,
        in IVoiceAsrCallback callback
    );

    /**
     * 预加载模型（面板打开时预热）。
     *
     * 立即返回；加载在服务自己的后台线程上进行，完成后经
     * [IVoiceAsrCallback.onEngineReady] 回执 —— 加载不占 binder 线程，
     * 因此后续 push/finish 不会被它阻塞。
     */
    boolean prepareAsr(
        in String family,
        in String[] roles,
        in String[] paths,
        in String language,
        in boolean inverseTextNormalization,
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
