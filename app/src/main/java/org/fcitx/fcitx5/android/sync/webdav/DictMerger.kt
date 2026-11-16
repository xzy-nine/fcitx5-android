/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 词库「组合叠加」：同步时把云端内容与本地内容取并集，而不是整文件覆盖。
 *
 * 只有**存在可靠文本表示**的词库才做叠加，其余一律覆盖：
 * - 叠加：`pinyin/user.dict`、`pinyin/dictionaries` 下的 `.dict`（libime 二进制，可无损转文本）、
 *   `pinyin/customphrase`（本就是文本）、`data/quickphrase.d` 下的 `.mb`（本就是文本）。
 * - 覆盖：`pinyin/user.history`（`HistoryBigram` 只有二进制格式）、
 *   `table` 下的 `.dict`（表格词库是「基础词条 + `[Auto]` + `[Delete]`」分段结构，
 *   逐行并集会打乱分段语义，且自动短语的命中计数两端不可比）、
 *   `inputmethod` 下的 `.conf`（定义文件，必须整体替换）。
 *
 * 并集规则：远端行在前、本地行在后，按整行去重。本地在后使同键冲突时本地胜出
 * （引擎按顺序写入 trie，后者覆盖前者）。任一侧独有的条目都会被保留，因此不丢数据。
 */
object DictMerger {

    private const val TAG = "DictMerger"

    enum class Strategy { MERGE_PINYIN, MERGE_TEXT, OVERWRITE }

    /** 由“相对 data/ 的路径”决定该文件能否叠加。 */
    fun strategyFor(relativeUnderData: String): Strategy = when {
        relativeUnderData == "pinyin/user.dict" -> Strategy.MERGE_PINYIN
        relativeUnderData.startsWith("pinyin/dictionaries/") &&
            relativeUnderData.endsWith(".dict") -> Strategy.MERGE_PINYIN
        relativeUnderData == "pinyin/customphrase" -> Strategy.MERGE_TEXT
        relativeUnderData.startsWith("data/quickphrase.d/") &&
            relativeUnderData.endsWith(".mb") -> Strategy.MERGE_TEXT
        else -> Strategy.OVERWRITE
    }

    /**
     * 该文件是否由引擎在内存中持有、并在保存时整体覆写磁盘。
     *
     * `pinyin/user.dict` 在引擎构造时读入内存，之后任何保存（自动保存、退出前保存）都会
     * 用内存内容覆盖磁盘。因此它**不能在引擎运行时**就地叠加：叠加结果会在下一次保存时
     * 被抹掉，且随后的上传会把云端并集也覆盖回去。这类文件必须先暂存云端副本，
     * 等引擎停机后再叠加。
     */
    fun isEngineOwned(relativeUnderData: String): Boolean =
        relativeUnderData == "pinyin/user.dict"
    /** 暂存的云端副本文件名（相对路径不能直接当文件名，压平为单段）。 */
    fun pendingFileNameFor(relativeUnderData: String): String =
        relativeUnderData.replace('/', '_')

    /** 二进制 <-> 文本转换（libime 拼音词库）。抽成接口以便纯逻辑可单测。 */
    interface PinyinTextConverter {
        fun toText(binary: File, text: File)
        fun toBinary(text: File, binary: File)
    }

    /** 默认转换器：复用 JNI `pinyinDictConv`（二进制与文本互转，成本值随文本保留）。 */
    private val nativePinyinConverter = object : PinyinTextConverter {
        override fun toText(binary: File, text: File) {
            throw UnsupportedOperationException("libime binary dictionaries are not supported")
        }

        override fun toBinary(text: File, binary: File) {
            throw UnsupportedOperationException("libime binary dictionaries are not supported")
        }
    }

    /**
     * 纯函数：行并集。远端在前、本地在后，按整行去重，忽略空行。
     * 本地在后 → 同键不同成本时本地覆盖远端；内容完全相同的行只保留一份。
     */
    fun mergeLines(remote: List<String>, local: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        remote.forEach { if (it.isNotBlank()) merged.add(it) }
        local.forEach { if (it.isNotBlank()) merged.add(it) }
        return merged.toList()
    }

    /**
     * 把 [remoteFile] 与 [localFile] 叠加，结果原子写入 [localFile]。
     *
     * 先在 [workDir] 里写临时文件再整体替换，避免中途失败留下半份词库。
     * 返回 false 表示叠加失败，调用方应**保持本地文件不变**（不要退回覆盖，否则会丢本地数据）。
     */
    fun merge(
        remoteFile: File,
        localFile: File,
        strategy: Strategy,
        workDir: File,
        converter: PinyinTextConverter = nativePinyinConverter
    ): Boolean {
        return try {
            when (strategy) {
                Strategy.OVERWRITE -> false
                Strategy.MERGE_TEXT -> mergeText(remoteFile, localFile, workDir)
                Strategy.MERGE_PINYIN -> mergePinyin(remoteFile, localFile, workDir, converter)
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG merge failed for ${localFile.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun mergeText(remoteFile: File, localFile: File, workDir: File): Boolean {
        val remoteLines = remoteFile.readLines()
        val localLines = localFile.readLines()
        val merged = mergeLines(remoteLines, localLines)
        return writeAtomically(workDir, localFile, merged.joinToString("\n") + "\n")
    }

    private fun mergePinyin(
        remoteFile: File,
        localFile: File,
        workDir: File,
        converter: PinyinTextConverter
    ): Boolean {
        val remoteText = File(workDir, "merge_remote.txt")
        val localText = File(workDir, "merge_local.txt")
        val mergedText = File(workDir, "merge_out.txt")
        val mergedBin = File(workDir, "merge_out.bin")
        listOf(remoteText, localText, mergedText, mergedBin).forEach { it.delete() }

        converter.toText(remoteFile, remoteText)
        converter.toText(localFile, localText)
        if (!remoteText.isFile || !localText.isFile) {
            Timber.w("$TAG pinyin text conversion produced no output for ${localFile.name}")
            return false
        }
        val merged = mergeLines(remoteText.readLines(), localText.readLines())
        mergedText.writeText(merged.joinToString("\n") + "\n")
        converter.toBinary(mergedText, mergedBin)
        if (!mergedBin.isFile || mergedBin.length() == 0L) {
            Timber.w("$TAG pinyin binary conversion produced no output for ${localFile.name}")
            return false
        }
        Files.move(mergedBin.toPath(), localFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /** 先把内容写到 [workDir] 下的临时文件，再整体替换目标，避免半份写入。 */
    private fun writeAtomically(workDir: File, target: File, content: String): Boolean {
        val tmp = File(workDir, "merge_${target.name}.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /**
     * 执行一批**延后**的叠加（引擎已停机，可安全改写它持有的文件）。
     *
     * [pending] 为 `相对路径 -> 暂存的云端副本文件`。[dataRoot] 为 `data/` 本地根目录，
     * [stagingDir] 为暂存副本所在目录（处理成功后删除对应副本）。
     *
     * 返回成功叠加的相对路径集合；失败项保留副本，留待下次重试（绝不覆盖本地）。
     */
    fun applyPending(
        pending: Map<String, File>,
        dataRoot: File,
        stagingDir: File,
        workDir: File,
        converter: PinyinTextConverter = nativePinyinConverter
    ): Set<String> {
        if (pending.isEmpty()) return emptySet()
        if (!workDir.exists()) workDir.mkdirs()
        val done = mutableSetOf<String>()
        pending.forEach { (rel, remoteCopy) ->
            val strategy = strategyFor(rel)
            val local = File(dataRoot, rel)
            if (strategy == Strategy.OVERWRITE) {
                // 不该走到这里：调用方只登记可叠加的文件
                Timber.w("$TAG pending merge skipped, not mergeable: $rel")
                return@forEach
            }
            if (!local.isFile) {
                // 本地不存在（如引擎清空过词库）：直接采用云端副本
                runCatching {
                    local.parentFile?.mkdirs()
                    Files.copy(
                        remoteCopy.toPath(), local.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }.onSuccess { done += rel }
                    .onFailure { Timber.w(it, "$TAG pending adopt failed: $rel") }
                return@forEach
            }
            if (merge(remoteCopy, local, strategy, workDir, converter)) done += rel
        }
        // 仅清理已成功处理的副本；失败项保留以便重试
        done.forEach { rel ->
            pending[rel]?.delete()
        }
        if (stagingDir.isDirectory && stagingDir.listFiles().isNullOrEmpty()) {
            stagingDir.delete()
        }
        return done
    }
}
