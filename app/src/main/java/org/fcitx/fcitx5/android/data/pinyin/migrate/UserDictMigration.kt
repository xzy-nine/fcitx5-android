/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.migrate

import org.fcitx.fcitx5.android.utils.FileUtil
import timber.log.Timber
import java.io.File

/**
 * 把旧版拼音用户词库导入 Rime。
 *
 * 旧版把自学习词条存成 libime 二进制 `pinyin/user.dict`；Rime 的用户词库是
 * `<词库>.userdb`，通过 `import_user_dict` 接收 TSV 文本（`词条<TAB>编码<TAB>次数`）。
 *
 * 迁移是**单向且幂等**的：导入成功后删除旧词库，旧文件不存在即视为已迁移。
 * 导入失败时保留旧词库与中间文件，下次启动重试。
 */
object UserDictMigration {

    /** 导入 Rime 的中间文件后缀。 */
    const val INTERMEDIATE_SUFFIX = ".import.tsv"

    /** cost 为 0 时映射到的提交次数上限。 */
    private const val MAX_COMMITS = 1000

    /** 旧格式词库对应的 Rime 词库名。 */
    private const val PINYIN_DICT_NAME = "pinyin_simp"

    /** 一条词条。 */
    private class Entry(val text: String, val code: String, val weight: Int)

    /**
     * 迁移结果。
     *
     * @property imported 成功导入 userdb 的词条数
     * @property pending 已生成中间文件但导入失败（留待下次重试）
     */
    data class Result(val imported: Int, val pending: Boolean = false)

    /**
     * 迁移 [legacyDict] 到 [rimeUserDir]。
     *
     * [importer] 负责把 TSV 文件导入引擎，返回导入条数；负值表示导入失败。
     */
    fun migrate(
        legacyDict: File,
        rimeUserDir: File,
        importer: (dict: String, tsv: File) -> Int,
    ): Result {
        if (!legacyDict.isFile) return Result(0)

        val words = try {
            LibimePinyinDict.read(legacyDict)
        } catch (e: Exception) {
            Timber.w(e, "读取旧拼音词库失败: ${legacyDict.name}")
            return Result(0)
        }

        val entries = rankEntries(words)
        if (entries.isEmpty()) {
            // 旧词库没有可迁移的词条，直接移除，避免每次启动重复解析
            legacyDict.delete()
            return Result(0)
        }

        val tsv = File(rimeUserDir, legacyDict.name + INTERMEDIATE_SUFFIX)
        writeTsv(tsv, entries)
        val imported = importer(PINYIN_DICT_NAME, tsv)
        if (imported < 0) {
            Timber.w("旧拼音词库导入失败，保留旧词库与中间文件待重试: ${tsv.name}")
            return Result(0, pending = true)
        }
        tsv.delete()
        // 词条已进入 userdb，旧格式不再需要
        legacyDict.delete()
        Timber.i("旧拼音词库迁移：导入 $imported 条")
        return Result(imported)
    }

    /**
     * 把词条按 libime 的 cost 映射为 Rime 的提交次数。
     *
     * libime 用 cost 表示优先级（0 最优，越大越差），Rime 用提交次数表示权重（越大越好），
     * 两者方向相反，故按 cost 反比换算：cost 越小得到的次数越多。
     */
    private fun rankEntries(words: List<LibimePinyinDict.Word>): List<Entry> {
        val result = ArrayList<Entry>(words.size)
        words.forEach { word ->
            val code = PinyinCode.decode(word.code)
            if (code.isEmpty()) return@forEach
            val cost = LibimePinyinDict.defaultWeight(word.weight) ?: return@forEach
            result += Entry(word.hanzi, code, commitsFor(cost))
        }
        return result
    }

    /** cost 到提交次数的换算：cost 0 得到 [MAX_COMMITS]，随 cost 增大单调递减。 */
    private fun commitsFor(cost: Float): Int =
        (MAX_COMMITS / (1.0 + cost)).toInt().coerceIn(1, MAX_COMMITS)

    /** 写 TSV：`词条<TAB>编码<TAB>提交次数`，按次数降序。 */
    private fun writeTsv(target: File, entries: List<Entry>) {
        FileUtil.writeAtomically(target) { output ->
            output.bufferedWriter().use { writer ->
                entries.sortedByDescending { it.weight }.forEach { entry ->
                    writer.write(entry.text)
                    writer.write('\t'.code)
                    writer.write(entry.code)
                    writer.write('\t'.code)
                    writer.write(entry.weight.toString())
                    writer.write('\n'.code)
                }
            }
        }
    }

    /**
     * 迁移 [dataRoot] 下全部受支持的旧词库。
     *
     * 目前覆盖拼音用户词库；形码表格词库的结构与拼音不同，单独处理。
     * [rimeUserDir] 取当前生效的 Rime 用户目录（profile 根部）。
     */
    fun migrateAll(
        dataRoot: File,
        rimeUserDir: File,
        importer: (dict: String, tsv: File) -> Int,
    ): Result {
        val legacy = File(dataRoot, "pinyin/user.dict")
        return migrate(legacy, rimeUserDir, importer)
    }
}
