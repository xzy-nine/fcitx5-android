// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
// 移植自 Xime (https://github.com/ximeiorg/xime)，见仓库根 NOTICE.md。
package com.kingzcheung.xime.service;

oneway interface IInferenceAsrCallback {
    void onPartialResult(String text);
    void onFinalResult(String text);
    void onError(String message);
}
