/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.stats

import android.view.View
import android.view.ViewGroup
/** Measure the same fixed-width tree that will be painted into the PNG. */
internal fun measureStatsSharePage(page: ViewGroup, preferredWidth: Int): Int {
    var minimumWidth = 0L
    fun visit(view: View) {
        if (view is StatsChartView && view.preferredMinimumWidth > 0) {
            var required = view.preferredMinimumWidth.toLong()
            var child: View = view
            while (child !== page) {
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { required += it.leftMargin + it.rightMargin }
                val parent = child.parent as? ViewGroup ?: break
                required += parent.paddingLeft + parent.paddingRight
                child = parent
            }
            minimumWidth = maxOf(minimumWidth, required)
        }
        if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
    }
    visit(page)
    val width = maxOf(preferredWidth.coerceAtLeast(240).toLong(), minimumWidth)
        .coerceAtMost(StatsImageRenderer.MAX_WIDTH.toLong()).toInt()
    page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    return width
}

internal object StatsPresentation {
    private val thresholds=longArrayOf(0,100,300,600,1000,2000,4000,7000,12000,20000,35000,55000,80000,120000,180000,280000,420000,620000,900000,1300000)
    data class Level(val number: Int,val progress: Float,val remaining: Long)
    fun realm(total: Long) = longArrayOf(0,10000,50000,200000,1000000,5000000,10000000).indexOfLast { total >= it }.coerceAtLeast(0)
    fun level(total: Long): Level {
        val index=thresholds.indexOfLast { total>=it }.coerceAtLeast(0)
        val next=thresholds.getOrNull(index+1)?:1_800_000L
        return Level(index+1,((total-thresholds[index]).toDouble()/(next-thresholds[index])).coerceIn(0.0,1.0).toFloat(),(next-total).coerceAtLeast(0))
    }
    fun speedRating(cpm: Double)=listOf(80.0 to "S+",60.0 to "S",45.0 to "A+",35.0 to "A",25.0 to "B+",18.0 to "B").firstOrNull { cpm>=it.first }?.second?:"C"
    fun accuracyRating(ratio: Double)=listOf(.01 to "S+",.03 to "S",.06 to "A+",.10 to "A",.15 to "B+",.22 to "B").firstOrNull { ratio.coerceAtLeast(0.0)<=it.first }?.second?:"C"
}
