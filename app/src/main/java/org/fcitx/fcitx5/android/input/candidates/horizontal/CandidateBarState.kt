/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * 候选栏状态定义
 * 参考 Xime 的 CandidateBarState 设计，适配 fcitx5 数据结构
 */
sealed interface CandidateBarState {

    /**
     * 空闲状态（无候选词）
     */
    data object Idle : CandidateBarState

    /**
     * 候选词激活状态
     */
    data class Active(
        val candidates: Array<CandidateWord>,
        val total: Int,
        val offset: Int = 0,
    ) : CandidateBarState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Active) return false
            return candidates.contentEquals(other.candidates) &&
                    total == other.total &&
                    offset == other.offset
        }

        override fun hashCode(): Int {
            var result = candidates.contentHashCode()
            result = 31 * result + total
            result = 31 * result + offset
            return result
        }
    }

    companion object {
        /**
         * 从 FcitxEvent 数据创建候选栏状态
         */
        fun from(
            candidates: Array<CandidateWord>,
            total: Int,
        ): CandidateBarState {
            return if (candidates.isEmpty()) {
                Idle
            } else {
                Active(
                    candidates = candidates,
                    total = total,
                    offset = 0,
                )
            }
        }
    }
}
