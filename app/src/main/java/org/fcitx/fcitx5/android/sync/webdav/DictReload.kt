/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.core.reloadQuickPhrase
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import timber.log.Timber

/**
 * 词库文件同步（逐文件 PUT/GET）之后的“生效”处理。
 *
 * 词库按 `data/` 下的顶层目录分三类：
 * - pinyin / quickphrase：引擎提供了热重载接口，覆盖文件后直接 reload 即可，
 *   **不需要重启引擎，更不需要重启进程**；
 * - table（五笔、郑码、双拼方案等）：上游没有重载接口，官方做法是 `restartFcitx()`，
 *   而引擎重启后 IME 侧连接不会复位，因此必须重建进程才稳定。
 *
 * 未知目录一律按 table 处理（保守），保证内容一定生效。
 */
object DictReload {

    private const val TAG = "DictReload"

    enum class Kind { PINYIN, QUICKPHRASE, TABLE }

    /** 由“相对 data/ 的路径”推断词库类型。 */
    fun kindOf(relativeUnderData: String): Kind = when (relativeUnderData.substringBefore('/')) {
        "pinyin" -> Kind.PINYIN
        "quickphrase" -> Kind.QUICKPHRASE
        else -> Kind.TABLE
    }

    /**
     * 是否必须重建进程才能让词库生效（`table/` 目录下的词库——五笔、郑码、双拼方案等被改动）。
     *
     * 触发条件：本轮同步命中的文件相对 `data/` 的路径落在未知目录或 `table/` 下（保守按 table 处理）。
     * 复现：覆盖任一 table 词库后只做热重载或 `FcitxDaemon.restartFcitx()`（引擎重启成功但
     * IME 与引擎的连接不复位），随后输入表现为“按键有反馈却无法上屏”，只有重建进程才恢复。
     *
     * 调用方差异：
     * - [AutoDictSync]：先 `stopFcitx()` 再 `AppUtil.exit()`（键盘隐藏且已排空待处理上传后）；
     * - `WebDavSyncScreen` 的 table 分支：当前仅发重启通知并调用 `AppUtil.exit()`，
     *   由进程退出连带回收引擎。
     *
     * 注意：不要为 `restartFcitx()` 失败增加回退逻辑——恢复后的生效路径只依赖进程重建。
     */
    fun needsRestart(kinds: Set<Kind>): Boolean = Kind.TABLE in kinds

    /**
     * 让引擎重新加载可热更新的词库。
     * 引擎尚未就绪时静默跳过——文件已经落盘，下次进程/引擎启动自然生效。
     */
    fun applyReloadable(kinds: Set<Kind>) {
        if (kinds.isEmpty()) return
        val connection = FcitxDaemon.getFirstConnectionOrNull()
        if (connection == null) {
            Timber.i("$TAG no fcitx connection, skip reload: $kinds")
            return
        }
        if (Kind.PINYIN in kinds) {
            connection.runIfReady {
                Timber.i("$TAG reloadPinyinDict")
                reloadPinyinDict()
            }
        }
        if (Kind.QUICKPHRASE in kinds) {
            connection.runIfReady {
                Timber.i("$TAG reloadQuickPhrase")
                reloadQuickPhrase()
            }
        }
    }
}
