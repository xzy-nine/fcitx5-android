/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.absoluteValue
import org.fcitx.fcitx5.android.R

/**
 * Stateless math helpers for split keyboard layout, kept out of [BaseKeyboard] to minimize
 * merge conflicts with upstream changes in that file.
 */

/**
 * 是否是空格键。
 *
 * **按 `viewId` 判而不是按键类型**：Compose 侧的键面变换（caps / 标点 / 输入法名）会重建
 * `KeyDef`，子类类型（`SpaceKey` / `MiniSpaceKey`）会丢失；而 `viewId` 在变换中被原样保留。
 * 对 View 侧两者等价（`SpaceKey` / `MiniSpaceKey` 各自唯一占用这两个 id）。
 */
internal fun isSpaceKeyDef(def: KeyDef): Boolean =
    def.appearance.viewId == R.id.button_space ||
            def.appearance.viewId == R.id.button_mini_space

internal fun rowContainsSpaceKey(row: List<KeyDef>): Boolean {
    return row.any(::isSpaceKeyDef)
}

/**
 * Compute per-row group width percent used when laying out non-space rows in split mode.
 */
internal fun computeRowGroupPercents(keyLayout: List<List<KeyDef>>, gapRatio: Float): List<Float> {
    val ratio = (1f - gapRatio).coerceAtLeast(0f)
    val nonSpacePercents = keyLayout.map { row ->
        if (rowContainsSpaceKey(row)) 0f
        else {
            val keyCount = row.size
            if (keyCount <= 0) return@map 0f
            val oddAdjust = if (keyCount % 2 == 0 || !isStandardWidthRow(row)) {
                1f
            } else {
                keyCount.toFloat() / (keyCount + 1f)
            }
            ratio * oddAdjust
        }
    }
    return nonSpacePercents.mapIndexed { index, percent ->
        if (percent > 0f) percent
        else {
            val prev = nonSpacePercents.getOrNull(index - 1) ?: 0f
            val next = nonSpacePercents.getOrNull(index + 1) ?: 0f
            maxOf(prev, next, ratio)
        }
    }
}

internal fun splitRowWidthPercent(row: List<KeyDef>): Float {
    var total = 0f
    row.forEach { def ->
        if (isSpaceKeyDef(def)) return@forEach
        val width = def.appearance.percentWidth
        if (width > 0f) {
            total += width
        }
    }
    return total
}

internal fun isStandardWidthRow(row: List<KeyDef>): Boolean {
    val standard = 0.1f
    val eps = 1e-4f
    return row.all { def ->
        if (isSpaceKeyDef(def)) true
        else (def.appearance.percentWidth - standard).absoluteValue <= eps
    }
}

internal fun splitRowAtMiddle(row: List<KeyDef>): Pair<List<KeyDef>, List<KeyDef>> {
    if (row.isEmpty()) return emptyList<KeyDef>() to emptyList()
    val mid = row.size / 2
    return if (row.size % 2 == 0) {
        row.subList(0, mid) to row.subList(mid, row.size)
    } else {
        row.subList(0, mid + 1) to row.subList(mid, row.size)
    }
}
