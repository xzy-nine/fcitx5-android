/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime) 的
 * app/src/main/aidl/com/kingzcheung/xime/service/IInferenceAsrService.aidl，见仓库根 NOTICE.md。
 *
 * 与上游的差异：上游 startAsr(modelDir, cb) 由 `:asr` 进程自己读偏好定位模型文件；
 * 本移植的 `:asr` 进程不初始化 AppPrefs，故改为由 app 侧解析好 4 个模型文件的绝对路径后传入。
 */
package com.kingzcheung.xime.service;

import com.kingzcheung.xime.service.IInferenceAsrCallback;

interface IInferenceAsrService {
    /**
     * 加载 ASR 模型并开始识别（返回 false 表示模型文件缺失或加载失败）。
     *
     * @param encoderPath encoder 模型（如 encoder.int8.onnx）绝对路径
     * @param decoderPath decoder 模型（如 decoder.onnx）绝对路径
     * @param joinerPath  joiner 模型（如 joiner.int8.onnx）绝对路径
     * @param tokensPath  tokens.txt 绝对路径
     */
    boolean startAsr(String encoderPath, String decoderPath, String joinerPath,
                     String tokensPath, IInferenceAsrCallback callback);
    /** 推送 PCM 音频数据（16k/16bit/mono），服务端内部流式识别 */
    void pushAsrAudio(in byte[] audioData);
    /** 结束识别，返回最终识别文本 */
    String stopAsr();
    /** 取消当前识别会话 */
    void cancelAsr();
    /** 释放 ASR 模型 */
    void releaseAsr();
}
