/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 触控笔「一笔手势」判定（纯逻辑，启发式）。
 *
 * 识别引擎（ochwpro）只有单字分类、没有手势类别，因此手势判定放在识别之前用几何启发式做，
 * 判定结果用于构造标准 `HandwritingGesture` 交给编辑器执行（`InputConnection#performHandwritingGesture`，
 * 坐标一律屏幕坐标；不支持的编辑器回落提交识别文本）。参考实现：
 * - Gboard `HandwritingEventHandler.c()`（ScribeRecognitionCandidate.gesture → DeleteGesture /
 *   SelectGesture / InsertGesture / InsertModeGesture / RemoveSpaceGesture）；
 * - 搜狗小米版 `MiuiHandWritingIMEStylus`（手势走 performHandwritingGesture，文本走提交回落）。
 *
 * **宁缺勿滥**：判不出的笔画一律按 [HandwritingStrokeKind.Character] 处理（普通字符笔画），
 * 阈值从严，避免把字符笔画误判成手势（尤其「一」这类横向短笔）。
 */
package org.fcitx.fcitx5.android.data.handwriting

import kotlin.math.hypot

/** 单笔手势判定结果（[Character] = 普通字符笔画）。 */
enum class HandwritingStrokeKind {
    /** 普通字符笔画：进识别窗口。 */
    Character,

    /** 涂改（来回涂抹）→ 删除笔迹下的文本。 */
    Delete,

    /** 圈选（闭合环）→ 选中圈住的内容。 */
    Select,

    /** 回车钩（下行后向左收，⏎ 形）→ 换行。 */
    Newline,

    /** 插入尖角（高瘦 ⋁/⋀）→ 在尖角处插入空格。 */
    Insert,
}

object HandwritingGestures {

    /**
     * 从已完成笔画判定手势类型。
     *
     * @param stroke 已完成笔画（时间序，同一坐标系即可——判定只看形状）
     * @param screenWidth 屏幕宽度（px）：横向类手势需达到屏宽一定比例，避免与「一」等字符混淆
     */
    fun detect(stroke: List<StrokePoint>, screenWidth: Int): HandwritingStrokeKind {
        if (stroke.size < MIN_POINTS) return HandwritingStrokeKind.Character
        val box = HandwritingStrokeFx.boxOf(stroke)
        val w = box.width
        val h = box.height
        val extent = maxOf(w, h)
        if (extent < screenWidth * MIN_EXTENT_RATIO) return HandwritingStrokeKind.Character

        val hReversals = directionalReversals(stroke, horizontal = true)
        val vReversals = directionalReversals(stroke, horizontal = false)

        // 涂改：宽扁 + 多次水平往返（写字时单笔极少出现 4 次以上往返）
        if (hReversals >= SCRIBBLE_MIN_REVERSALS && w > h * SCRIBBLE_ASPECT) {
            return HandwritingStrokeKind.Delete
        }

        // 圈选：首尾接近的闭合环 + 真的绕出了面积（周长足够、包围面积占比够大）
        // 面积判据用来排除「V 形」（首尾也接近，但只围出三角形一半的面积）
        val perimeter = perimeterOf(stroke)
        val closed = perimeter > 0f &&
                hypot(stroke.last().x - stroke.first().x, stroke.last().y - stroke.first().y) <=
                perimeter * CLOSED_GAP_RATIO
        if (closed && w > 0f && h > 0f) {
            val aspect = w / h
            val areaRatio = enclosedAreaRatio(stroke, w, h)
            if (aspect in SELECT_MIN_ASPECT..SELECT_MAX_ASPECT &&
                perimeter >= (w + h) * SELECT_PERIMETER_RATIO &&
                areaRatio >= SELECT_MIN_AREA_RATIO
            ) {
                return HandwritingStrokeKind.Select
            }
        }

        // 插入尖角：高瘦 V（垂直方向仅一次转向、高度 ≥ 2×宽度）
        // 必须是**真正的 V**：两臂长度相当、两臂都近乎竖直、且收笔回到起笔高度附近。
        // 只判「一次垂直反转 + 高瘦」会把汉字里的竖钩（亅：下行后向左上短收，
        // 同样只有一次反转）误判成手势并吞掉这一笔。
        if (vReversals == 1 && h >= w * INSERT_HEIGHT_RATIO && isTightV(stroke, box)) {
            return HandwritingStrokeKind.Insert
        }

        // 回车钩：有下行幅度、横向有一次转向，且收笔是一段明显向左的横向尾巴（⏎ 形）
        // （「撇」这类斜笔的尾巴是斜向的，不满足 |dx| ≫ |dy|）
        if (h >= w * NEWLINE_HEIGHT_RATIO &&
            hReversals in 1..NEWLINE_MAX_REVERSALS &&
            endsWithLeftwardHorizontal(stroke)
        ) {
            return HandwritingStrokeKind.Newline
        }

        return HandwritingStrokeKind.Character
    }

    /**
     * 是否为**真正的插入尖角 V**：顶点在中部、两臂长度相当且都近乎竖直、
     * 收笔回到起笔高度附近。
     *
     * 汉字竖钩「亅」（下行后向左上短收）与「V」的差别就在这里：它的第二臂很短、
     * 且明显斜向收笔，两个条件都不满足。
     */
    internal fun isTightV(stroke: List<StrokePoint>, box: HandwritingStrokeFx.Box): Boolean {
        if (stroke.size < MIN_POINTS) return false
        // 顶点 = 最低点（y 最大）；两臂按顶点切分
        var vertexIndex = 0
        for (i in stroke.indices) if (stroke[i].y > stroke[vertexIndex].y) vertexIndex = i
        if (vertexIndex == 0 || vertexIndex == stroke.size - 1) return false
        val legUp = run {
            var total = 0f
            for (i in 1..vertexIndex) {
                total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
            }
            total
        }
        val legDown = run {
            var total = 0f
            for (i in vertexIndex + 1 until stroke.size) {
                total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
            }
            total
        }
        if (legUp <= 0f || legDown <= 0f) return false
        // 两臂长度相当（竖钩的第二臂极短，会被排除）
        val shorter = minOf(legUp, legDown)
        val longer = maxOf(legUp, legDown)
        if (shorter < longer * V_LEG_SYMMETRY) return false
        // 收笔高度回到起笔附近（V 的两个端点等高；竖钩的收笔停在字腰）
        val topSpan = box.height
        if (topSpan <= 0f) return false
        if (kotlin.math.abs(stroke.last().y - stroke.first().y) > topSpan * V_ENDPOINT_LEVEL_RATIO) {
            return false
        }
        // 两条臂都近乎竖直：每臂的水平跨度都远小于垂直跨度
        val legUpSpanX = kotlin.math.abs(stroke[vertexIndex].x - stroke[0].x)
        val legDownSpanX = kotlin.math.abs(stroke.last().x - stroke[vertexIndex].x)
        val legUpSpanY = kotlin.math.abs(stroke[vertexIndex].y - stroke[0].y)
        val legDownSpanY = kotlin.math.abs(stroke.last().y - stroke[vertexIndex].y)
        val steepUp = legUpSpanY > 0f && legUpSpanX <= legUpSpanY * V_MAX_LEG_SLOPE
        val steepDown = legDownSpanY > 0f && legDownSpanX <= legDownSpanY * V_MAX_LEG_SLOPE
        return steepUp && steepDown
    }

    /** 收笔是否为「明显向左的横向尾巴」（尾部 |dx| ≥ 2×|dy| 且向左）。 */
    internal fun endsWithLeftwardHorizontal(stroke: List<StrokePoint>): Boolean {        val tail = maxOf(TAIL_MIN_POINTS, stroke.size / 5)
        if (stroke.size <= tail) return false
        val from = stroke[stroke.size - 1 - tail]
        val to = stroke.last()
        val dx = to.x - from.x
        val dy = to.y - from.y
        return dx <= -TAIL_LEFT_MIN_PX && -dx >= kotlin.math.abs(dy) * TAIL_LEFT_RATIO
    }

    /**
     * 笔画首尾闭合成环后的包围面积占其包围盒面积的比例。
     *
     * 闭合圆 ≈ 0.785、方框 ≈ 1.0、V/三角 ≈ 0.5 —— 用 0.6 的门槛把尖角排除出圈选。
     */
    internal fun enclosedAreaRatio(stroke: List<StrokePoint>, width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f || stroke.size < 3) return 0f
        var twiceArea = 0f
        for (i in stroke.indices) {
            val a = stroke[i]
            val b = stroke[(i + 1) % stroke.size]
            twiceArea += a.x * b.y - b.x * a.y
        }
        val area = kotlin.math.abs(twiceArea) / 2f
        return area / (width * height)
    }

    /** 采样点序列的往返次数（方向符号变化次数，含抖动过滤）。 */
    internal fun directionalReversals(stroke: List<StrokePoint>, horizontal: Boolean): Int {
        var reversals = 0
        var lastSign = 0
        for (i in 1 until stroke.size) {
            val delta = if (horizontal) {
                stroke[i].x - stroke[i - 1].x
            } else {
                stroke[i].y - stroke[i - 1].y
            }
            val sign = when {
                delta > DIRECTION_EPSILON_PX -> 1
                delta < -DIRECTION_EPSILON_PX -> -1
                else -> 0
            }
            if (sign == 0) continue
            if (lastSign != 0 && sign != lastSign) reversals++
            lastSign = sign
        }
        return reversals
    }

    /** 笔画路径长度（px）。 */
    internal fun perimeterOf(stroke: List<StrokePoint>): Float {
        var total = 0f
        for (i in 1 until stroke.size) {
            total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
        }
        return total
    }

    private const val MIN_POINTS = 6
    private const val DIRECTION_EPSILON_PX = 3f

    /** 手势最小尺寸（屏宽比例）：太小的笔画一律按字符处理。 */
    private const val MIN_EXTENT_RATIO = 0.08f

    private const val SCRIBBLE_MIN_REVERSALS = 4
    private const val SCRIBBLE_ASPECT = 1.5f

    private const val CLOSED_GAP_RATIO = 0.3f
    private const val SELECT_MIN_ASPECT = 0.2f
    private const val SELECT_MAX_ASPECT = 5f
    private const val SELECT_PERIMETER_RATIO = 1.5f
    private const val SELECT_MIN_AREA_RATIO = 0.6f

    private const val INSERT_HEIGHT_RATIO = 2f

    /** V 两臂长度比下限（短臂 ≥ 长臂 × 此值），排除「竖钩」这类一臂极短的笔画。 */
    private const val V_LEG_SYMMETRY = 0.55f

    /** V 两端高度差上限（占笔画高度的比例）：真正的 V 两端等高。 */
    private const val V_ENDPOINT_LEVEL_RATIO = 0.35f

    /** 单臂水平跨度上限（相对该臂垂直跨度）：越大越斜，V 的两臂都应近乎竖直。 */
    private const val V_MAX_LEG_SLOPE = 0.6f

    private const val NEWLINE_HEIGHT_RATIO = 0.6f
    private const val NEWLINE_MAX_REVERSALS = 2
    private const val TAIL_MIN_POINTS = 3
    private const val TAIL_LEFT_MIN_PX = 40f
    private const val TAIL_LEFT_RATIO = 2f
}
