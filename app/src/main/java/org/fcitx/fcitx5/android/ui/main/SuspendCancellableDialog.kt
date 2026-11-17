/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** 挂起直到对话框给出结果，取消时关闭对话框。 */
internal suspend inline fun <T> suspendCancellableDialog(
    crossinline block: (CancellableContinuation<T>) -> AlertDialog
): T = suspendCancellableCoroutine { cont ->
    val dialog = block(cont)
    cont.invokeOnCancellation {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (dialog.isShowing) dialog.dismiss()
        } else {
            Handler(Looper.getMainLooper()).post {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }
}
