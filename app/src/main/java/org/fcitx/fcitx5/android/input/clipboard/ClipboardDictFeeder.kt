/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.util.Log
import com.huaban.analysis.jieba.WordDictionary
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Paths

/**
 * 将输入法词库（libime 拼音主词典 + 用户导入词库 + 用户自造词）运行时导出为 jieba 自定义词典，
 * 注入到共享的单例分词词典，使剪贴板中文分词结合"输入法自身词库"而非 jieba 默认词典。
 *
 * 幂等策略（避免重复生成与注入）：
 * - 进程内 [fed] 标志：本进程只执行一次注入；
 * - 磁盘指纹：词库（各词典文件 + 自造词快照）的 path/lastModified/length 哈希；与上次一致且
 *   user.dict 已存在时，完全跳过 pinyinDictConv 与词典生成，仅 loadUserDict 加载磁盘文件；
 * - 二进制词典转出的文本词库存于 cache 目录，并按源文件 mtime 复用，避免重复 native 调用。
 *
 * 应在 IO 线程调用（pinyinDictConv / 读文件 / loadUserDict 均有开销）。
 */
object ClipboardDictFeeder {

    private const val TAG = "ClipboardDictFeeder"
    private const val CACHE_SUBDIR = "clipboard_jieba"
    private const val USER_DICT_NAME = "user.dict"
    private const val FINGERPRINT_NAME = "fingerprint.txt"
    private const val MAX_WORDS = 200_000

    @Volatile
    private var fed = false

    /**
     * 幂等主入口：确保 jieba 自定义词典已注入。应在 IO 线程调用。
     * 词库未变或已注入时直接返回，不重复导出/生成/注入。
     */
    fun ensureFed() {
        if (fed) return
        synchronized(this) {
            if (fed) return
            try {
                doFeed()
            } catch (e: Throwable) {
                Log.e(TAG, "feed clipboard jieba dict failed, fallback to default dict", e)
            } finally {
                // 标记完成，避免本进程内反复重试（即便失败也回退默认词典，功能不降级）
                fed = true
            }
        }
    }

    /**
     * 词库变更（导入/删除/自定义短语改动）后调用，清除指纹，下次 ensureFed 重建。
     */
    fun invalidate() {
        synchronized(this) {
            fed = false
            val dir = File(appContext.cacheDir, CACHE_SUBDIR)
            File(dir, FINGERPRINT_NAME).delete()
        }
    }

    private fun doFeed() {
        val cacheDir = File(appContext.cacheDir, CACHE_SUBDIR).also { it.mkdirs() }
        val userDict = File(cacheDir, USER_DICT_NAME)
        val fpFile = File(cacheDir, FINGERPRINT_NAME)

        val dictionaries = PinyinDictManager.listDictionaries()
        val phrases = CustomPhraseManager.load()
        val fingerprint = computeFingerprint(dictionaries, phrases)

        if (userDict.exists() && fpFile.exists() && fpFile.readText() == fingerprint) {
            // 已生成且词库未变：直接加载磁盘词典，跳过导出与生成
            loadUserDictFile(userDict)
            return
        }

        val words = LinkedHashSet<String>()
        dictionaries.forEach { dict ->
            val txt = textCacheFor(dict, cacheDir) ?: return@forEach
            readWords(txt, words)
        }
        phrases?.forEach { collectCustomPhrase(it.value, words) }

        userDict.bufferedWriter(Charsets.UTF_8).use { w ->
            words.forEach { w.write("$it 100 n\n") }
        }
        fpFile.writeText(fingerprint)
        loadUserDictFile(userDict)
    }

    private fun computeFingerprint(
        dictionaries: List<PinyinDictionary>,
        phrases: Array<*>?
    ): String {
        val sb = StringBuilder()
        dictionaries.forEach { d ->
            val f = d.file
            sb.append(f.absolutePath).append(':').append(f.lastModified()).append(':')
                .append(f.length()).append(';')
        }
        phrases?.forEach { sb.append(it.toString()).append(',') }
        return sb.toString().hashCode().toString()
    }

    /**
     * 返回词典对应的文本词库文件：
     * - .dict（libime 二进制）：pinyinDictConv 反编译，按源 mtime 缓存；
     * - .scel（搜狗）：先 sougouDictConv 转 bin 再 pinyinDictConv 转文本；
     * - .txt（文本词库）：直接返回源文件。
     */
    private fun textCacheFor(dict: PinyinDictionary, cacheDir: File): File? {
        val src = dict.file
        return when (dict.type) {
            PinyinDictionary.Type.Text -> src
            PinyinDictionary.Type.LibIME -> {
                val cache = File(cacheDir, "${src.nameWithoutExtension}.txt")
                if (!cache.exists() || cache.lastModified() < src.lastModified()) {
                    PinyinDictManager.pinyinDictConv(
                        src.absolutePath, cache.absolutePath, PinyinDictManager.MODE_BIN_TO_TXT
                    )
                }
                cache.takeIf { it.exists() }
            }
            PinyinDictionary.Type.Sougou -> {
                val bin = File(cacheDir, "${src.nameWithoutExtension}.bin")
                val cache = File(cacheDir, "${src.nameWithoutExtension}.txt")
                if (!cache.exists() || cache.lastModified() < src.lastModified()) {
                    PinyinDictManager.sougouDictConv(src.absolutePath, bin.absolutePath)
                    PinyinDictManager.pinyinDictConv(
                        bin.absolutePath, cache.absolutePath, PinyinDictManager.MODE_BIN_TO_TXT
                    )
                }
                cache.takeIf { it.exists() }
            }
        }
    }

    private fun readWords(file: File, words: MutableSet<String>) {
        if (!file.exists()) return
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (words.size >= MAX_WORDS) break
                val word = line.substringBefore(' ').substringBefore('\t').trim()
                if (isValidWord(word)) words.add(word)
            }
        }
    }

    /**
     * 用户自造词（自定义短语）可能含较长中文短语，提取其中连续中文片段（>=2 字）作为词条，
     * 强化输入法实际用词在分词中的权重。
     */
    private fun collectCustomPhrase(value: String, words: MutableSet<String>) {
        if (value.isBlank()) return
        CN_SEGMENT.findAll(value).forEach { words.add(it.value) }
    }

    private fun isValidWord(w: String): Boolean {
        if (w.isEmpty() || w.length > 12) return false
        if (w.none { it in '\u4e00'..'\u9fa5' }) return false // 至少含一个汉字
        return true
    }

    private fun loadUserDictFile(file: File) {
        if (!file.exists()) return
        WordDictionary.getInstance().loadUserDict(Paths.get(file.absolutePath))
    }

    private val CN_SEGMENT = Regex("""[一-龥]{2,}""")
}
