/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.core.FcitxAPI

/**
 * Clients should use [FcitxConnection] to run fcitx operations.
 */
interface FcitxConnection {

    /**
     * Run an operation immediately
     * The suspended [block] will be executed in caller's thread.
     * Use this function only for non-blocking operations like
     * accessing [FcitxAPI.eventFlow].
     */
    fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T

    /**
     * Read the cached snapshot fields of [FcitxAPI] without touching the fcitx thread.
     *
     * [runImmediately] wraps the block in `runBlocking` over the fcitx lifecycle scope, so any
     * block reaching `withFcitxContext` (i.e. a `suspend` API that needs the fcitx thread) makes
     * the **caller thread block until fcitx has run it**. The snapshot fields
     * (`inputPanelCached`, `inputMethodEntryCached`, `statusAreaActionsCached`,
     * `clientPreeditCached`) are plain `@Volatile`-ish reads maintained by the fcitx thread, and
     * [FcitxAPI.eventFlow] is a plain [SharedFlow] reference — none of them need dispatching.
     *
     * Use this for those reads on hot paths (IME `onStartInputView`, `InputView` recreation,
     * status icon, expanded-candidate tabs), so they never hand work to (or wait on) the fcitx
     * thread.
     */
    fun <T> peek(block: FcitxAPI.() -> T): T

    /**
     * Run an operation immediately if fcitx is at ready state.
     * Otherwise, caller will be suspended until fcitx is ready and operation is done.
     * The suspended [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T

    /**
     * Run an operation if fcitx is at ready state.
     * Otherwise, do nothing.
     * The suspended [block] will be executed in thread pool.
     * This function does not block or suspend the caller.
     */
    fun runIfReady(block: suspend FcitxAPI.() -> Unit)

    val lifecycleScope: CoroutineScope
}