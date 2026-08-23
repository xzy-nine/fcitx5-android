/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import com.huaban.analysis.jieba.JiebaSegmenter

/**
 * 对剪贴板文本做中文分词 + 实体提取，用于在正常文本下方展示可点击的提取气泡。
 *
 * 分词使用 jieba-analysis（com.huaban:jieba-analysis，纯 Java、离线、词典内置），
 * 相比纯正则能更准确地切分中文（例如把「张三的验证码123456」切为 [张三][的][验证码][123456]），
 * 从而让独立片段的识别更可靠。
 *
 * 实体类型：
 * - 验证码（Code）：非中文的独立片段（纯数字 / 纯字母 / 数字字母混合）
 * - 邮箱（Email）：含 @ 的 token
 * - 链接（Url）：含 http / https / www. 的 token
 *
 * 实体按在原文中出现的顺序返回，去重。
 */
object ClipboardTextAnalyzer {

    enum class EntityType {
        Code,       // 验证码
        Email,      // 邮箱
        Url         // 链接
    }

    data class Entity(val type: EntityType, val value: String)

    // 分词器单例：首次访问时加载内置词典（一次性开销），之后复用
    // 词典已注入输入法用户词库（见 ClipboardDictFeeder），故分词结果本身即「带用户词来源」的切分
    private val segmenter by lazy { JiebaSegmenter() }

    // 文件路径：仅识别「绝对路径」——Unix 以 / 开头且前面是空白或文本起点（(?<!\S) 负向后顾），
    // 或 Windows 盘符 X:\... 同样要求前面无紧跟非空字符。这样可排除 http://（/ 前是 :）、
    // 以及相对路径 a/b/c，避免普通文本被整段吞掉不显示。
    // 命中区间内的分词片段不识别为实体，避免路径被切散后误显示
    private val PATH = Regex(
        """(?<!\S)(?:/(?:[^\s/]+/)+[^\s/]+)|(?<!\S)(?:[A-Za-z]:\\[^\s\\]+(?:\\[^\s\\]+)*)"""
    )

    /**
     * 提取实体。返回按出现顺序去重后的列表（同值只保留首个）。
     *
     * 整体策略：先 jieba 分词得到「独立片段（token）」，再**在每个 token 上做局部匹配**，
     * 而不是对全文做正则。这样即便链接/邮箱被切散，只要某个 token 命中局部特征（含 http/www、含 @）
     * 也会显示；中文 token 一律不进入实体（姓名类已彻底移除，避免误判）。
     *
     * 实体类型：
     * - 链接（Url）：token 含 http / https / www. 子串
     * - 邮箱（Email）：token 含 @ 且形如 x@y
     * - 验证码（Code）：非中文的 token（纯数字 / 纯字母 / 数字字母混合），长度 2~64；
     *   手机号等数字串作为数字 token 自然落入此类（正则已去除，直接复用分词结果）
     */
    fun analyze(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val raw = text.replace('\n', ' ').replace('\r', ' ')
        val result = mutableListOf<Pair<Int, Entity>>()
        val seen = mutableSetOf<String>()
        // 已被链接/邮箱覆盖的 token 值，避免验证码再重复显示
        val covered = mutableSetOf<String>()

        // 文件路径（Unix /.../... 与 Windows X:\...）整体区间，落在其内的 token 不识别为实体，
        // 避免路径被分词切碎后误当成验证码/链接等显示
        val pathRanges = PATH.findAll(raw).map { it.range }.toList()
        fun inPath(index: Int): Boolean = pathRanges.any { index in it }

        fun add(index: Int, type: EntityType, value: String, markCovered: Boolean = false) {
            val key = "${type.name}:$value"
            if (seen.add(key)) {
                result.add(index to Entity(type, value))
            }
            if (markCovered) covered.add(value)
        }

        // 分词后在每个 token 上做局部识别
        runCatching {
            segmenter.process(raw, JiebaSegmenter.SegMode.SEARCH)
        }.getOrNull()?.forEach { token ->
            val w = token.word.trim()
            if (w.isEmpty()) return@forEach
            if (w.any { c -> c in '\u4e00'..'\u9fa5' }) return@forEach // 含中文跳过（姓名类已移除）
            if (w in covered) return@forEach
            val idx = raw.indexOf(w).takeIf { it >= 0 } ?: Int.MAX_VALUE
            if (inPath(idx)) return@forEach // 文件路径区间内的片段不显示
            if ('/' in w || '\\' in w) return@forEach // 含路径分隔符的碎片（路径被切出）不显示
            when {
                // 链接：token 含 http(s) 或 www.
                w.contains("http", ignoreCase = true) || w.contains("www.", ignoreCase = true) -> {
                    add(idx, EntityType.Url, w, markCovered = true)
                }
                // 邮箱：token 含 @ 且含 .，@ 前后均有内容（排除 @ 前后无点号的误判）
                w.contains('@') && w.contains('.') &&
                        w.indexOf('@') > 0 && w.indexOf('@') < w.length - 1 -> {
                    add(idx, EntityType.Email, w, markCovered = true)
                }
                // 验证码：非中文、长度 2~64 的独立片段（数字/字母/混合）
                w.length in 2..64 -> {
                    add(idx, EntityType.Code, w)
                }
            }
        }

        return result.sortedBy { it.first }.map { it.second }
    }

    /**
     * 全分词（用于编辑弹窗的「分词重组」）：返回 jieba 切出的全部词（按原文顺序，含标点/空白占位）。
     * 与 [analyze] 不同，这里不做实体识别、不去重、不筛除，直接给出可重组原文的完整词序列。
     */
    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
        }.getOrNull()
            ?.map { it.word }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
}
