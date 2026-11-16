/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

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

    enum class Kind { PINYIN, CUSTOM_PHRASE, QUICKPHRASE, USER_DICT, TABLE }

    /**
     * 由“相对 data/ 的路径”推断词库类型。
     *
     * - `pinyin/user.dict` 单列一类：引擎在构造时把它读进内存，之后只会在自动保存/退出时
     *   用内存内容覆盖磁盘，且没有任何热重载入口（`reloadPinyinDict` 只重载
     *   `pinyin/dictionaries` 下的附加词库）。因此外部改动必须靠重建进程才不会被引擎写回覆盖。
     * - `pinyin/customphrase` 是独立的热重载入口（`reloadPinyinCustomPhrase`），
     *   与 `pinyin/dictionaries` 的 `reloadPinyinDict` 互不覆盖。
     * - 用户 QuickPhrase 词库位于 `data/quickphrase.d/`（相对 `data/` 的路径即以 `data/` 开头），
     *   与 `pinyin` 目录下可热重载的词库一样，无需重建进程。
     */
    fun kindOf(relativeUnderData: String): Kind = when {
        // Rime 用户词库文本由引擎在部署时编译，与 libime 词库同样需要重建进程才生效
        relativeUnderData.endsWith("/user.txt") -> Kind.USER_DICT
        relativeUnderData == "pinyin/user.dict" -> Kind.USER_DICT
        relativeUnderData == "pinyin/customphrase" -> Kind.CUSTOM_PHRASE
        relativeUnderData.substringBefore('/') == "pinyin" -> Kind.PINYIN
        relativeUnderData.startsWith("data/quickphrase.d/") -> Kind.QUICKPHRASE
        relativeUnderData.substringBefore('/') == "quickphrase" -> Kind.QUICKPHRASE
        else -> Kind.TABLE
    }

    /**
     * 是否必须重建进程才能让词库生效（`table/` 目录下的词库——五笔、郑码、双拼方案等被改动）。
     *
     * 触发条件：本轮同步命中的文件相对 `data/` 的路径落在未知目录或 `table/` 下（保守按 table 处理），
     * 或命中了由引擎持有在内存中的 `pinyin/user.dict`（见 [kindOf]）。
     *
     * table 类词库没有热重载入口；且引擎重启后 IME 与引擎之间的连接状态不复位，
     * 只重建引擎无法恢复输入，因此必须重建进程。
     *
     * 调用方差异：
     * - [AutoDictSync]：先 `stopFcitx()` 再 `AppUtil.exit()`（键盘隐藏且已排空待处理上传后）；
     * - `WebDavSyncScreen` 的 table 分支：发重启通知后 `AppUtil.exit()`，
     *   由进程退出连带回收引擎。
     *
     * 注意：不要为 `restartFcitx()` 失败增加回退逻辑——生效路径只依赖进程重建。
     */
    fun needsRestart(kinds: Set<Kind>): Boolean = Kind.TABLE in kinds || Kind.USER_DICT in kinds

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
        // 本构建只带 Rime 引擎：libime 系的 addon 热重载入口不存在，
        // 词库在引擎下次启动（或部署）时自然生效，这里只记录命中的类别。
        Timber.i("$TAG reload skipped for %s: engine reloads on next deploy", kinds)
    }
}
