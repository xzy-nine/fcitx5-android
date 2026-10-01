/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 触控笔手写浮动工具箱（**UI 完全仿照米系 `ImeMenuViewHolder` 的浮窗卡片**）。
 *
 * 米系参考（`E:\GitHubCode\05Decompilation\decompiled\input\sogou_xiaomi`）：
 * - 宿主：`ImeMenuViewHolder.initLayoutParams()` 用 `WindowManager.addView`，`type=2038`
 *   （TYPE_APPLICATION_OVERLAY）、`flags=296`（NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN）、
 *   `format=TRANSLUCENT`、透明全屏容器 + 卡片用 `MarginLayoutParams` 定位；
 *   靠 MIUI 私有 `LayoutParamUtils.setTrustedOverLay` 才拿到 overlay 权限。
 * - 交互：拖柄按下位移 >3px 才算拖动，位置按横竖屏分别写入 SharedPreferences
 *   （`keyboard_position_x_port` 等）；`checkAndUpdateKeyboardPosition` 把位置夹取到屏幕安全区；
 *   用隐藏 API `ViewTreeObserver.OnComputeInternalInsetsListener` 只把卡片矩形声明为可触摸区域。
 * - UI（布局 `iw.xml`）：卡片 194×116dp、圆角 18dp（`tm`）、白底（`td`）+ 阴影
 *   `#191a1a1a`（半径 14dp `u5`、dy 4dp `u7`）；**顶部 22dp 拖柄条**（`tn`）；
 *   中部 38dp 行（`tt`）：撤销 / 重做 / 键盘 / 回车；底部 32dp 行（`tk`）：删除 + 空格 + 收起。
 *
 * 本实现的差异（**必需**）：米系申请了 overlay 权限（`type=2038` + 私有
 * `setTrustedOverLay`）；本项目不申请该权限，改为**把工具箱加在 IME 窗口的 decorView 上**
 * （米系 `StylusUtils.addStylusToolbox` 的宿主 `oem.util.b.f()` 也是 IME decorView），
 * 并配合 `requestShowInputView()`（`requestShowSelf(0)`）**主动保持 IME 窗口显示**，
 * 工具箱因此不会被 IME 隐藏带走。UI 尺寸、颜色、圆角、阴影、拖动与位置持久化全部照抄米系。
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlin.math.abs

/** 工具箱按钮（对齐米系菜单项语义）。 */
enum class StylusToolboxAction {
    Undo,
    Redo,
    Keyboard,
    Enter,
    Space,
    Backspace,
    Close,
}

/**
 * 手写浮动工具箱：米系同款卡片 UI + 米系同款拖动/位置持久化。
 *
 * 宿主是 IME 窗口 decorView 上的透明全屏容器（[rootView]），卡片用 margin 定位；
 * 卡片矩形经 [cardRectInWindow] 交给 IME 的 insets 声明为可触摸区域。
 */
class StylusToolboxWindow(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 透明全屏容器（挂在 IME decorView 上，不参与 IME 布局）。 */
    private var rootView: FrameLayout? = null

    /** 卡片。 */
    private var card: ToolboxCardView? = null

    private var listener: ((StylusToolboxAction) -> Unit)? = null

    /** 卡片在当前窗口坐标中的位置（px）。 */
    private var x = 0
    private var y = 0

    /** 拖动起点（米系 FloatingWindowOnTouchListener 同款）。 */
    private var startRawX = 0f
    private var startRawY = 0f
    private var startViewX = 0f
    private var startViewY = 0f

    private val containerSize = android.graphics.Point()
    private val cardSize = android.graphics.Point()
    private val screenArea = Rect()
    private val screenSize = android.graphics.Point()
    private val screenInset = Rect()

    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    var isShown: Boolean = false
        private set

    init {
        updateScreenInfo()
    }

    fun setOnAction(listener: (StylusToolboxAction) -> Unit) {
        this.listener = listener
    }

    /**
     * 设置候选行（手写识别候选）。
     *
     * **候选挂在浮动工具箱上、而不是墨迹层**：手写会话是 500ms 短会话，系统结束会话会
     * `InkWindow.hide()`，画在墨迹层的候选随之消失；工具箱跨会话常驻，候选因此能稳定
     * 显示与点选。
     *
     * @param onPick 点选回调（下标与 [candidates] 对应）
     */
    fun setCandidates(candidates: List<String>, onPick: (Int) -> Unit) {
        card?.setCandidates(candidates, onPick)
    }

    /** 屏幕坐标是否落在卡片（含阴影外扩）内。 */
    fun hitTest(screenX: Float, screenY: Float): Boolean =
        isShown && cardRectOnScreen().contains(screenX.toInt(), screenY.toInt())

    /**
     * 把触控笔事件转成卡片本地坐标后派发给工具箱（返回是否被消费）。
     *
     * **为什么需要这一步**：手写会话期间系统把所有触控笔事件直接投递给
     * `InputMethodService.onStylusHandwritingMotionEvent`（AOSP 的 `mHandwritingEventReceiver`），
     * **不再走窗口触摸分发**，因此触控笔点工具箱不会触发按钮点击，而是被当成笔画。
     * 这里由 IME 侧按坐标判断「笔落在卡片上」，再把事件转派给工具箱视图 ——
     * 于是工具栏按钮对触控笔同样可用，且手写只发生在工具栏以外的区域。
     */
    fun dispatchStylusEvent(event: MotionEvent): Boolean {
        val cardView = card ?: return false
        val host = rootView ?: return false
        @Suppress("UNUSED_EXPRESSION") host
        val location = IntArray(2)
        cardView.getLocationOnScreen(location)
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
        val handled = runCatching { cardView.dispatchTouchEvent(local) }.getOrDefault(false)
        local.recycle()
        return handled
    }

    /**
     * 显示浮窗（幂等）：**米系 `StylusUtils.addStylusToolbox` 同款**——
     * 把透明全屏容器 `addView` 到**传入的宿主 ViewGroup**（= IME 窗口的 decorView），
     * 卡片用 margin 定位。
     *
     * 宿主必须是 IME 窗口 decorView：`TYPE_APPLICATION_ATTACHED_DIALOG` 之类的窗口
     * **依附父窗口**，IME 窗口一隐藏箱子窗口就跟着不可见。配合
     * `requestShowInputView()`（`requestShowSelf(0)`）主动保持 IME 窗口显示。
     *
     * @param host IME 窗口 decorView
     */
    fun show(host: ViewGroup) {
        if (isShown) return
        val ctx = context
        val cardView = ToolboxCardView(ctx).apply {
            buildContent { action -> listener?.invoke(action) }
            setOnHandleTouchListener { event -> onHandleTouch(event) }
        }
        card = cardView
        val container = FrameLayout(ctx).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            addView(
                cardView,
                FrameLayout.LayoutParams(cardSize.x, cardSize.y),
            )
        }
        rootView = container
        host.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        isShown = true
        // 位置必须**挂载后**再应用：margin 要减去 decor 原点，未挂载时拿不到窗口位置。
        // 布局前先设一次（decorView 的屏幕位置在 attach 后即可用），布局后再校正一次。
        applyPosition()
        container.post { applyPosition() }
    }

    /** 隐藏并移除（幂等；米系在 `onFinishInputView` / `onDestroy` 才真正移除）。 */
    fun hide() {
        if (!isShown) return
        isShown = false
        val host = rootView?.parent as? ViewGroup
        val container = rootView
        rootView = null
        card = null
        if (host != null && container != null) host.removeView(container)
    }

    /** 屏幕尺寸变化时同步并夹取位置。 */
    fun onHostSizeChanged(width: Int, height: Int) {
        containerSize.x = width
        containerSize.y = height
        clampPosition()
        applyPosition()
    }

    /**
     * 卡片在屏幕坐标中的矩形（米系 `getTouchRegion()` 同义：用于判断触摸命中）。
     *
     * **优先取卡片的真实布局位置**（`getLocationOnScreen`），而不是逻辑 `x`/`y`：
     * `x`/`y` 是**屏幕坐标**（`clampPosition()` 按 `screenArea` 夹取），却被 `applyPosition()`
     * 当作**相对 decorView 的 margin** 使用 —— IME 窗口不在屏幕原点时（例如窗口上边缘在 y=92），
     * 卡片实际画出来的位置会比 `x`/`y` 低一个 decor 原点偏移，
     * 于是命中判定与 `touchableRegion` 整体偏上 ⇒ 点在画出来的卡片上却被判成「不在工具箱里」
     * （触控笔被当成笔画、手指事件被路由给应用）。
     */
    fun cardRectOnScreen(): Rect {
        val shadow = (SHADOW_MARGIN_DP * density()).toInt()
        val cardView = card
        if (cardView != null && cardView.isAttachedToWindow) {
            val location = IntArray(2)
            cardView.getLocationOnScreen(location)
            return Rect(
                location[0] - shadow,
                location[1] - shadow,
                location[0] + cardView.width + shadow,
                location[1] + cardView.height + shadow,
            )
        }
        return Rect(x - shadow, y - shadow, x + cardSize.x + shadow, y + cardSize.y + shadow)
    }

    /** 配置变化：重算安全区、夹取位置（米系 `onNewConfiguration` 同款）。 */
    fun onConfigurationChanged() {
        val before = lastOrientation
        updateScreenInfo()
        if (before != lastOrientation) {
            // 横竖屏切换回到默认位置（米系同款）
            x = (screenSize.x - cardSize.x) / 2
            y = (screenArea.bottom - DEFAULT_BOTTOM_OFFSET_DP * density()).toInt() - cardSize.y
        }
        clampPosition()
        applyPosition()
    }

    /**
     * 卡片在 **IME 窗口坐标**中的矩形（供 `onComputeInsets` 声明可触摸区域）。
     *
     * 独立浮窗无需并入 IME insets（触摸由本窗口自己处理），保留此方法供命中测试。
     */
    fun cardRectInWindow(hostLocationOnScreen: IntArray): Rect? {
        if (!isShown) return null
        val rect = cardRectOnScreen()
        rect.offset(-hostLocationOnScreen[0], -hostLocationOnScreen[1])
        return rect
    }

    // ------------------------------------------------------------------
    // 拖动（米系 FloatingWindowOnTouchListener 同款）
    // ------------------------------------------------------------------

    private fun onHandleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                startViewX = event.x
                startViewY = event.y
                card?.setPressedHandle(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - startRawX) > DRAG_THRESHOLD_DP * density() ||
                    abs(event.rawY - startRawY) > DRAG_THRESHOLD_DP * density()
                ) {
                    x = (event.rawX - startViewX).toInt()
                    y = (event.rawY - startViewY).toInt()
                    clampPosition()
                    applyPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                card?.setPressedHandle(false)
                savePosition()
                return true
            }
        }
        return false
    }

    /**
     * 应用位置。
     *
     * **[x]/[y] 是屏幕坐标**（拖拽用 `rawX/rawY`、[clampPosition] 用屏幕坐标的 `screenArea`），
     * 而 margin 是**相对 decorView** 的 —— 必须减去 decor 原点，绘制位置才会与 [x]/[y] 一致。
     * 否则 IME 窗口不在屏幕原点时卡片会整体偏移，命中判定与 `touchableRegion` 也跟着偏。
     */
    private fun applyPosition() {
        val container = rootView ?: return
        val lp = card?.layoutParams as? FrameLayout.LayoutParams ?: return
        val origin = IntArray(2)
        container.rootView.getLocationOnScreen(origin)
        val marginX = x - origin[0]
        val marginY = y - origin[1]
        if (lp.leftMargin == marginX && lp.topMargin == marginY) return
        lp.leftMargin = marginX
        lp.topMargin = marginY
        card?.layoutParams = lp
        container.invalidate()
    }

    /** 位置夹取到安全区（米系 `checkAndUpdateKeyboardPosition` 同款）。 */
    private fun clampPosition() {
        val cardW = cardSize.x
        val cardH = cardSize.y
        if (x < screenArea.left) x = screenArea.left
        if (x + cardW > screenArea.right) x = screenArea.right - cardW
        if (y < screenArea.top) y = screenArea.top
        if (y + cardH > screenArea.bottom) y = screenArea.bottom - cardH
    }

    private fun savePosition() {
        val portrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        prefs.edit()
            .putInt(if (portrait) KEY_X_PORT else KEY_X_LAND, x)
            .putInt(if (portrait) KEY_Y_PORT else KEY_Y_LAND, y)
            .apply()
    }

    private fun updateScreenInfo() {
        lastOrientation = context.resources.configuration.orientation
        val metrics = context.resources.displayMetrics
        screenSize.x = metrics.widthPixels
        screenSize.y = metrics.heightPixels
        cardSize.x = (CARD_WIDTH_DP * density()).toInt()
        cardSize.y = (CARD_HEIGHT_DP * density()).toInt()
        screenInset.left = (SCREEN_INSET_SIDE_DP * density()).toInt()
        screenInset.right = screenInset.left
        screenInset.bottom = (SCREEN_INSET_BOTTOM_DP * density()).toInt()
        screenArea.left = screenInset.left
        screenArea.right = screenSize.x - screenInset.right
        screenArea.top = statusBarHeight()
        screenArea.bottom = screenSize.y - navigationBarHeight() - screenInset.bottom
        val portrait = lastOrientation == Configuration.ORIENTATION_PORTRAIT
        x = prefs.getInt(
            if (portrait) KEY_X_PORT else KEY_X_LAND,
            (screenSize.x - cardSize.x) / 2,
        )
        y = prefs.getInt(
            if (portrait) KEY_Y_PORT else KEY_Y_LAND,
            screenArea.bottom - (DEFAULT_BOTTOM_OFFSET_DP * density()).toInt() - cardSize.y,
        )
        clampPosition()
    }

    private fun statusBarHeight(): Int = systemDimen("status_bar_height")

    private fun navigationBarHeight(): Int = systemDimen("navigation_bar_height")

    private fun systemDimen(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun density(): Float = context.resources.displayMetrics.density

    private companion object {
        const val PREFS_NAME = "fcitx_stylus_toolbox"
        const val KEY_X_PORT = "toolbox_x_port"
        const val KEY_Y_PORT = "toolbox_y_port"
        const val KEY_X_LAND = "toolbox_x_land"
        const val KEY_Y_LAND = "toolbox_y_land"

        /** 卡片尺寸（米系 `u8` × `to`）。 */
        const val CARD_WIDTH_DP = 194f
        const val CARD_HEIGHT_DP = 116f

        /** 屏幕安全区内缩（米系 `e`/`f`/`d`）。 */
        const val SCREEN_INSET_SIDE_DP = 8f
        const val SCREEN_INSET_BOTTOM_DP = 8f

        /** 默认距底部安全区高度。 */
        const val DEFAULT_BOTTOM_OFFSET_DP = 96f

        /** 拖动判定阈值（dp，米系 MOVE_THRESHOLD = 3）。 */
        const val DRAG_THRESHOLD_DP = 3f

        /** 阴影外扩（可触摸区域需含阴影）。 */
        const val SHADOW_MARGIN_DP = 8f
    }
}

/**
 * 米系同款阴影圆角卡片（对应 `ShadowLayout` + `iw.xml`）：
 * 白底、圆角 18dp、阴影 `#191a1a1a`（半径 14dp、dy 4dp）；
 * 顶部 22dp 拖柄条、中部 38dp 行（撤销/重做/键盘/回车）、底部 32dp 行（删除/空格/收起）。
 */
private class ToolboxCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 屏幕密度（显式取值，避开 `View.density`（API 34）带来的解析歧义）。 */
    private val selfDensity: Float = resources.displayMetrics.density

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(SHADOW_RADIUS_DP * selfDensity, 0f, SHADOW_DY_DP * selfDensity, SHADOW_COLOR)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ICON_COLOR
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ICON_COLOR
    }
    private val path = Path()

    /** 拖柄（自带绘制，外部挂触摸监听）。 */
    private val handle: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            fillPaint.color = if (pressed) HANDLE_PRESSED_COLOR else HANDLE_COLOR
            val cy = height / 2f
            val half = HANDLE_BAR_WIDTH_DP * selfDensity / 2f
            canvas.drawRoundRect(
                RectF(width / 2f - half, cy - 2f * selfDensity, width / 2f + half, cy + 2f * selfDensity),
                2f * selfDensity,
                2f * selfDensity,
                fillPaint,
            )
        }
    }

    private var pressed = false

    /** 候选行（手写候选，随识别结果刷新；空则隐藏）。 */
    private var candidateRow: LinearLayout? = null

    /**
     * 刷新候选行：候选挂在工具箱上（而非墨迹层），因此跨 500ms 短会话稳定可见。
     *
     * 候选一律用 **[android.widget.Button]**（与中部/底部行的 `ImageButton` 同一种「正经可点击控件」），
     * 并显式给 `LayoutParams`；不要用裸 `TextView` —— 它没有背景与最小尺寸，命中区域不可靠。
     */
    fun setCandidates(candidates: List<String>, onPick: (Int) -> Unit) {
        val row = candidateRow ?: return
        row.removeAllViews()
        if (candidates.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val pad = (CANDIDATE_PADDING_DP * selfDensity).toInt()
        candidates.forEachIndexed { index, text ->
            row.addView(
                android.widget.Button(context).apply {
                    this.text = text
                    textSize = CANDIDATE_TEXT_SP
                    setTextColor(CANDIDATE_TEXT_COLOR)
                    setBackgroundColor(Color.TRANSPARENT)
                    isAllCaps = false
                    // Button 默认带 48dp 最小尺寸与内边距，这里清零以贴合 24dp 候选行
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(pad, 0, pad, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                    setOnClickListener { onPick(index) }
                },
            )
        }
    }

    fun setPressedHandle(pressed: Boolean) {
        this.pressed = pressed
        handle.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setOnHandleTouchListener(l: (MotionEvent) -> Boolean) {
        handle.setOnTouchListener { _, event -> l(event) }
    }

    fun buildContent(onAction: (StylusToolboxAction) -> Unit) {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
        isClickable = false

        addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, (HANDLE_HEIGHT_DP * selfDensity).toInt()))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(StylusToolboxAction.Undo, onAction), iconParams(leading = true))
                addView(iconButton(StylusToolboxAction.Redo, onAction), iconParams())
                addView(iconButton(StylusToolboxAction.Keyboard, onAction), iconParams())
                addView(iconButton(StylusToolboxAction.Enter, onAction), iconParams())
            },
            LayoutParams(LayoutParams.MATCH_PARENT, (ROW_HEIGHT_DP * selfDensity).toInt()),
        )
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(StylusToolboxAction.Backspace, onAction), iconParams(leading = true))
                addView(iconButton(StylusToolboxAction.Space, onAction), iconParams())
                addView(iconButton(StylusToolboxAction.Close, onAction), iconParams())
            },
            LayoutParams(LayoutParams.MATCH_PARENT, (BOTTOM_ROW_HEIGHT_DP * selfDensity).toInt()),
        )
        // 候选行（米系工具箱没有，因为米系候选由系统手写面板承载；
        // 我们把 IME 候选栏撤下后，候选挂在这里，避免随墨迹层一起消失）
        candidateRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(
            candidateRow,
            LayoutParams(LayoutParams.MATCH_PARENT, (CANDIDATE_ROW_HEIGHT_DP * selfDensity).toInt()),
        )
    }

    private fun iconParams(leading: Boolean = false) = LayoutParams(
        (ICON_SIZE_DP * selfDensity).toInt(),
        (ICON_SIZE_DP * selfDensity).toInt(),
    ).apply {
        marginStart = ((if (leading) LEADING_MARGIN_DP else ICON_GAP_DP) * selfDensity).toInt()
    }

    private fun iconButton(
        action: StylusToolboxAction,
        onAction: (StylusToolboxAction) -> Unit,
    ): View = object : ImageButton(context) {
        init {
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ScaleType.CENTER
            setOnClickListener { onAction(action) }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f * 0.62f
            iconPaint.strokeWidth = 2f * selfDensity
            drawIcon(canvas, action, cx, cy, r)
        }
    }

    private fun drawIcon(canvas: Canvas, action: StylusToolboxAction, cx: Float, cy: Float, r: Float) {
        when (action) {
            StylusToolboxAction.Undo -> {
                path.reset()
                path.moveTo(cx + r * 0.5f, cy + r * 0.45f)
                path.lineTo(cx + r * 0.5f, cy - r * 0.1f)
                path.lineTo(cx - r * 0.5f, cy - r * 0.1f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.1f, cx - r * 0.1f, cy - r * 0.5f, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.1f, cx - r * 0.1f, cy + r * 0.3f, iconPaint)
            }

            StylusToolboxAction.Redo -> {
                path.reset()
                path.moveTo(cx - r * 0.5f, cy + r * 0.45f)
                path.lineTo(cx - r * 0.5f, cy - r * 0.1f)
                path.lineTo(cx + r * 0.5f, cy - r * 0.1f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx + r * 0.5f, cy - r * 0.1f, cx + r * 0.1f, cy - r * 0.5f, iconPaint)
                canvas.drawLine(cx + r * 0.5f, cy - r * 0.1f, cx + r * 0.1f, cy + r * 0.3f, iconPaint)
            }

            StylusToolboxAction.Keyboard -> {
                canvas.drawRoundRect(
                    RectF(cx - r * 0.6f, cy - r * 0.4f, cx + r * 0.6f, cy + r * 0.4f),
                    r * 0.15f, r * 0.15f, iconPaint,
                )
                fillPaint.color = ICON_COLOR
                for (i in 0..2) {
                    canvas.drawCircle(cx - r * 0.34f + i * r * 0.34f, cy - r * 0.15f, r * 0.08f, fillPaint)
                    canvas.drawCircle(cx - r * 0.34f + i * r * 0.34f, cy + r * 0.15f, r * 0.08f, fillPaint)
                }
            }

            StylusToolboxAction.Enter -> {
                path.reset()
                path.moveTo(cx + r * 0.45f, cy - r * 0.5f)
                path.lineTo(cx + r * 0.45f, cy + r * 0.15f)
                path.lineTo(cx - r * 0.5f, cy + r * 0.15f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy + r * 0.15f, cx - r * 0.15f, cy - r * 0.2f, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy + r * 0.15f, cx - r * 0.15f, cy + r * 0.5f, iconPaint)
            }

            StylusToolboxAction.Space -> {
                canvas.drawLine(cx - r * 0.5f, cy + r * 0.25f, cx + r * 0.5f, cy + r * 0.25f, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy + r * 0.25f, cx - r * 0.5f, cy - r * 0.05f, iconPaint)
                canvas.drawLine(cx + r * 0.5f, cy + r * 0.25f, cx + r * 0.5f, cy - r * 0.05f, iconPaint)
            }

            StylusToolboxAction.Backspace -> {
                val left = cx - r * 0.6f
                val right = cx + r * 0.55f
                val top = cy - r * 0.4f
                val bottom = cy + r * 0.4f
                path.reset()
                path.moveTo(right, top)
                path.lineTo(left + r * 0.3f, top)
                path.lineTo(left, cy)
                path.lineTo(left + r * 0.3f, bottom)
                path.lineTo(right, bottom)
                path.close()
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - r * 0.02f, cy - r * 0.18f, cx + r * 0.3f, cy + r * 0.18f, iconPaint)
                canvas.drawLine(cx - r * 0.02f, cy + r * 0.18f, cx + r * 0.3f, cy - r * 0.18f, iconPaint)
            }

            StylusToolboxAction.Close -> {
                canvas.drawLine(cx - r * 0.35f, cy - r * 0.35f, cx + r * 0.35f, cy + r * 0.35f, iconPaint)
                canvas.drawLine(cx - r * 0.35f, cy + r * 0.35f, cx + r * 0.35f, cy - r * 0.35f, iconPaint)
            }
        }
    }

    /** 白底圆角 + 阴影（米系 `ShadowLayout.onDraw` 同款）。 */
    override fun onDraw(canvas: Canvas) {
        val r = CORNER_RADIUS_DP * selfDensity
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, shadowPaint)
        super.onDraw(canvas)
    }

    private companion object {
        const val CORNER_RADIUS_DP = 18f
        const val SHADOW_RADIUS_DP = 14f
        const val SHADOW_DY_DP = 4f
        const val SHADOW_COLOR = 0x191a1a1a

        const val HANDLE_HEIGHT_DP = 22f
        const val HANDLE_BAR_WIDTH_DP = 28f
        const val ROW_HEIGHT_DP = 38f
        const val BOTTOM_ROW_HEIGHT_DP = 32f

        /**
         * 候选行高度：**必须正好填满米系卡片的剩余空间**。
         *
         * 卡片 116dp = 拖柄 22 + 中部行 38 + 底部行 32 + **候选行 24**；
         * 若候选行高于 24dp 会越过卡片下沿被裁掉，越界部分既看不全也点不到。
         */
        const val CANDIDATE_ROW_HEIGHT_DP = 24f
        const val CANDIDATE_TEXT_SP = 15f
        const val CANDIDATE_PADDING_DP = 8f
        const val CANDIDATE_TEXT_COLOR = 0xFF1A1A1A.toInt()

        const val ICON_SIZE_DP = 32f
        const val LEADING_MARGIN_DP = 12f
        const val ICON_GAP_DP = 6f

        const val ICON_COLOR = 0xFF1A1A1A.toInt()
        const val HANDLE_COLOR = 0x33000000
        const val HANDLE_PRESSED_COLOR = 0x66000000
    }
}
