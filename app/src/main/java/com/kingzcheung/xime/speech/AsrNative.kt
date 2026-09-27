/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
 *
 * 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
 */
package com.kingzcheung.xime.speech

/**
 * JNI bridge to the self-implemented streaming zipformer2 ASR
 * (libasr_jni.so). Reference algorithm: sherpa-onnx (Apache-2.0);
 * feature extraction: kaldi-native-fbank (Apache-2.0).
 */
object AsrNative {
    init {
        System.loadLibrary("asr_jni")
    }

    external fun nativeCreate(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String
    ): Long

    external fun nativeReset(handle: Long)

    external fun nativeAcceptPcm(handle: Long, pcm: ByteArray)

    external fun nativeGetPartial(handle: Long): String

    external fun nativeFinalize(handle: Long): String

    external fun nativeRelease(handle: Long)
}
