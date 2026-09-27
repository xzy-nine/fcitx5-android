/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.quickphrase

import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.sync.webdav.DictReload
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 邮箱域名联想词库（custom 特性）。
 *
 * 域名数据为标准 fcitx QuickPhrase 词库，两个文件同机制（引擎经 `XDG_DATA_HOME`
 * / `XDG_DATA_DIRS` 加载，设置界面「快速输入」词库列表可见可编辑、随词库备份/WebDAV 同步）：
 * - **内置词库** `usr/share/fcitx5/data/quickphrase.d/email.mb`：预置常见域名，由 CMake
 *   从 `app/src/main/cpp/email/email.mb`（git 追踪源）安装，引擎加载提供默认候选。
 * - **用户词库** `getExternalFilesDir(null)/data/data/quickphrase.d/email.mb`：自学习域名。
 *   首次自学习时复制内置词库内容作为初始条目（同名用户词库在引擎侧优先于内置，故必须
 *   带上内置条目），此后用户词库独立演进（自学习添加、MRU 前移，内置被同名覆盖）。
 *
 * 词库条目 `@域名 域名`（键带 `@` 前缀）：用户在 `@` 后进入 QuickPhrase 临时模式、
 * 缓冲以 `@` 开头时，引擎按键前缀匹配提供域名候选（点选 commit 值=纯域名，`@` 已上屏）。
 */
object EmailDomainDict {

    private const val TAG = "EmailDomainDict"

    private const val DICT_NAME = "email"

    /** 内置词库目录（assets 解压到设备加密存储，只读）。 */
    private val builtinQuickPhraseDir = File(
        DataManager.dataDir, "usr/share/fcitx5/data/quickphrase.d"
    )

    /** 用户 QuickPhrase 词库目录（与 [QuickPhraseManager.customQuickPhraseDir] 一致）。 */
    private val customQuickPhraseDir = File(
        appContext.getExternalFilesDir(null)!!, "data/data/quickphrase.d"
    )

    private val builtinFile: File
        get() = File(builtinQuickPhraseDir, "$DICT_NAME.${QuickPhrase.EXT}")

    private val userFile: File
        get() = File(customQuickPhraseDir, "$DICT_NAME.${QuickPhrase.EXT}")

    /** `@domain` 模式：字母/数字/点/连字符 + 点 + 2~6 位 TLD。 */
    private val DOMAIN_REGEX = Regex("""@([A-Za-z0-9.-]+\.[A-Za-z]{2,6})\b""")

    private val FULL_DOMAIN_REGEX = Regex("""^[A-Za-z0-9.-]+\.[A-Za-z]{2,6}$""")

    fun selfLearn(text: String) {
        if (text.isEmpty()) return
        val domains = if (text.contains("@")) {
            extractDomains(text)
        } else if (FULL_DOMAIN_REGEX.matches(text.trim())) {
            listOf(text.trim().lowercase())
        } else {
            emptyList()
        }
        if (domains.isEmpty()) return
        ensureUserDictExists()
        val entries = readEntries().toMutableList()
        for (domain in domains) {
            entries.removeAll { it.keyword == "@$domain" }
            entries.add(0, QuickPhraseEntry("@$domain", domain))
        }
        saveEntries(entries)
        reload()
        Timber.tag(TAG).d("Learned domains: $domains")
    }

    /** 从文本中提取所有 `@domain`（不含 @），去重，全小写。 */
    fun extractDomains(text: String): List<String> {
        if (!text.contains("@")) return emptyList()
        val result = LinkedHashSet<String>()
        DOMAIN_REGEX.findAll(text).forEach { m ->
            result.add(m.groupValues[1].lowercase())
        }
        return result.toList()
    }

    /**
     * 确保用户词库存在：首次复制内置词库（预置域名）作为初始条目。
     * 用户词库与内置同名，引擎侧用户优先——若只写自学习域名会把内置域名挤掉，
     * 故必须带上内置条目（与 `BuiltinQuickPhrase` 的 override 机制一致）。
     */
    private fun ensureUserDictExists() {
        if (userFile.exists()) return
        customQuickPhraseDir.mkdirs()
        runCatching {
            if (builtinFile.exists()) {
                builtinFile.copyTo(userFile)
            } else {
                userFile.writeText("")
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to create email domain user dict")
        }
    }

    private fun readEntries(): List<QuickPhraseEntry> {
        if (!userFile.exists()) return emptyList()
        return runCatching {
            QuickPhraseData.fromLines(userFile.readLines())
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to read email domain dict")
        }.getOrDefault(QuickPhraseData(emptyList()))
    }

    private fun saveEntries(entries: List<QuickPhraseEntry>) {
        runCatching {
            userFile.writeText(QuickPhraseData(entries).serialize())
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to write email domain dict")
        }
    }

    /** 热重载 QuickPhrase 词库（引擎无需重启；连接未就绪时静默跳过）。 */
    private fun reload() {
        DictReload.applyReloadable(setOf(DictReload.Kind.QUICKPHRASE))
    }
}
