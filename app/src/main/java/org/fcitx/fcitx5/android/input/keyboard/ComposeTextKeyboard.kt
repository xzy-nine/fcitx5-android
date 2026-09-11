/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import androidx.annotation.Keep
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import kotlin.math.absoluteValue

/**
 * 文本键盘的状态层（`Docs/KeyboardComposePlan.md` 批次 C2）。
 *
 * View 侧 `TextKeyboard` 是「布局是死的、键面靠 setText/imageResource 改」；Compose 侧改成
 * **状态 → 键面（`KeyDef` 变换）** 的纯映射：`layout()` 每次重组按当前状态生成一份新的
 * `KeyDef` 列表（键数远小于每帧预算，成本可忽略），按键原语 [ComposeKey] 不需要知道任何状态。
 *
 * 逐项对应 View 侧：
 *
 * | 本类 | View 出处 |
 * |---|---|
 * | [capsState] / [switchCaps] | `TextKeyboard.capsState` / `switchCapsState` |
 * | [transformAction] | `TextKeyboard.onAction`（大小写与 KeyStates 变换） |
 * | [transformPopup] | `TextKeyboard.onPopupAction`（预览与弹层小键盘的字符变换） |
 * | [onInputMethodUpdate] | `TextKeyboard.onInputMethodUpdate`（空格键显示输入法名、重置 caps） |
 * | [onPunctuationUpdate] | `TextKeyboard.onPunctuationUpdate` |
 * | [onReturnDrawableUpdate] | `TextKeyboard.onReturnDrawableUpdate` |
 * | [onAttach] | `TextKeyboard.onAttach`（caps 归零） |
 * | [layout] | `updateAlphabetKeys` / `updatePunctuationKeys` / `updateCapsButtonIcon` / `updateLangSwitchKey` / `updateSpaceVoiceIcon` |
 */
class TextKeyboardState(initialReturnDrawable: Int) {

    var capsState: TextKeyboard.CapsState by mutableStateOf(TextKeyboard.CapsState.None)
        private set

    var punctuationMapping: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** 空格键主文本：`ime.displayName` +（有子模式时）` (label|name)`。 */
    var spaceLabel: String by mutableStateOf("")
        private set

    var showLangSwitchKey: Boolean by mutableStateOf(
        AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
    )
        private set

    /** 空格键的语音图标是否显示（仅 `SpaceLongPressBehavior.VoiceInput` 时为真）。 */
    var spaceVoiceIconVisible: Boolean by mutableStateOf(
        AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior.getValue() ==
                SpaceLongPressBehavior.VoiceInput
    )
        private set

    var returnDrawable: Int by mutableStateOf(initialReturnDrawable)
        private set

    /**
     * 字母键副文本是否保持大写（View: `updateAlphabetKeys` 读 keepLettersUppercase）。
     *
     * `KeyboardWindow` 是 essential 窗口、`TextKeyboardState` 长期复用，偏好变更必须
     * 走监听即时写入 Compose 状态，否则 `layout()` 重算仍读到旧值。
     */
    var keepLettersUppercase: Boolean by mutableStateOf(
        AppPrefs.getInstance().keyboard.keepLettersUppercase.getValue()
    )
        private set

    @Keep
    private val keepLettersUppercaseListener =
        ManagedPreference.OnChangeListener<Boolean> { _, newValue ->
            keepLettersUppercase = newValue
        }

    init {
        AppPrefs.getInstance().keyboard.keepLettersUppercase
            .registerOnChangeListener(keepLettersUppercaseListener)
    }

    // -----------------------------------------------------------------------
    // 状态变更（对应 View 侧各回调）
    // -----------------------------------------------------------------------

    fun onAttach() {
        capsState = TextKeyboard.CapsState.None
    }

    fun onInputMethodUpdate(ime: InputMethodEntry) {
        spaceLabel = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        // View: onInputMethodUpdate 里 capsState != None 时 switchCapsState()（回到 None）
        if (capsState != TextKeyboard.CapsState.None) {
            capsState = TextKeyboard.CapsState.None
        }
    }

    fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
    }

    fun onReturnDrawableUpdate(resourceId: Int) {
        returnDrawable = resourceId
    }

    fun refreshLangSwitchKeyVisibility() {
        showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
    }

    fun refreshSpaceVoiceIcon() {
        spaceVoiceIconVisible =
            AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior.getValue() ==
                    SpaceLongPressBehavior.VoiceInput
    }

    /** 对应 `TextKeyboard.switchCapsState`。 */
    fun switchCaps(lock: Boolean = false) {
        capsState = if (lock) {
            if (capsState == TextKeyboard.CapsState.Lock) {
                TextKeyboard.CapsState.None
            } else {
                TextKeyboard.CapsState.Lock
            }
        } else {
            if (capsState == TextKeyboard.CapsState.None) {
                TextKeyboard.CapsState.Once
            } else {
                TextKeyboard.CapsState.None
            }
        }
    }

    // -----------------------------------------------------------------------
    // 动作 / 弹层变换（对应 TextKeyboard.onAction / onPopupAction）
    // -----------------------------------------------------------------------

    fun transformAction(
        action: KeyAction,
        source: KeyActionListener.Source,
    ): KeyAction = when (action) {
        is KeyAction.FcitxKeyAction -> when (source) {
            KeyActionListener.Source.Keyboard -> when (capsState) {
                TextKeyboard.CapsState.None ->
                    action.copy(act = action.act.lowercase())

                TextKeyboard.CapsState.Once -> {
                    val transformed = action.copy(
                        act = action.act.uppercase(),
                        states = KeyStates(KeyState.Virtual, KeyState.Shift)
                    )
                    switchCaps()
                    transformed
                }

                TextKeyboard.CapsState.Lock -> action.copy(
                    act = action.act.uppercase(),
                    states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                )
            }

            // 弹层小键盘触发时，Once 态用掉即回落
            KeyActionListener.Source.Popup -> {
                if (capsState == TextKeyboard.CapsState.Once) switchCaps()
                action
            }
        }

        is KeyAction.CapsAction -> {
            switchCaps(action.lock)
            action
        }

        else -> action
    }

    fun transformPopup(action: PopupAction): PopupAction = when (action) {
        is PopupAction.PreviewAction ->
            action.copy(content = transformPopupPreview(action.content))

        is PopupAction.PreviewUpdateAction ->
            action.copy(content = transformPopupPreview(action.content))

        is PopupAction.ShowKeyboardAction -> when (val keyboard = action.keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val label = keyboard.label
                if (label.length == 1 && label[0].isLetter()) {
                    action.copy(keyboard = keyboard.copy(label = transformAlphabet(label)))
                } else {
                    action
                }
            }

            is KeyDef.Popup.Keyboard.Explicit -> action
        }

        else -> action
    }

    private fun transformPopupPreview(content: String): String {
        if (content.length != 1) return content
        return if (content[0].isLetter()) {
            transformAlphabet(content)
        } else {
            transformPunctuation(content)
        }
    }

    private fun transformAlphabet(c: String): String = when (capsState) {
        TextKeyboard.CapsState.None -> c.lowercase()
        else -> c.uppercase()
    }

    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    // -----------------------------------------------------------------------
    // 键面：状态 → KeyDef 列表
    // -----------------------------------------------------------------------

    /**
     * 按当前状态生成布局。
     *
     * 语言键隐藏时直接**移除**该键（对应 View 的 `visibility = GONE`：ConstraintLayout 的
     * chain 会把该键从链里去掉，其余键宽度不变、整行因总宽变小而居中，与这里少一个 `percentWidth`
     * 的效果一致）。
     */
    fun layout(): List<List<KeyDef>> = TextKeyboard.Layout.map { row ->
        row.mapNotNull(::transformKey)
    }

    private fun transformKey(def: KeyDef): KeyDef? {
        val appearance = def.appearance
        // 语言键显隐（View: updateLangSwitchKey）
        if (appearance.viewId == R.id.button_lang && !showLangSwitchKey) return null

        // caps 键图标（View: updateCapsButtonIcon）
        if (appearance.viewId == R.id.button_caps && appearance is KeyDef.Appearance.Image) {
            val src = when (capsState) {
                TextKeyboard.CapsState.None -> R.drawable.ic_capslock_none
                TextKeyboard.CapsState.Once -> R.drawable.ic_capslock_once
                TextKeyboard.CapsState.Lock -> R.drawable.ic_capslock_lock
            }
            return def.withAppearance(appearance.copyImage(src = src))
        }

        // 回车键图标（View: onReturnDrawableUpdate）
        if (appearance.viewId == R.id.button_return && appearance is KeyDef.Appearance.Image) {
            return def.withAppearance(appearance.copyImage(src = returnDrawable))
        }

        // 空格键：主文本 = 输入法名，图标按偏好显隐（View: onInputMethodUpdate / setIconVisible）
        if (appearance.viewId == R.id.button_space && appearance is KeyDef.Appearance.ImageText) {
            return if (spaceVoiceIconVisible) {
                def.withAppearance(
                    KeyDef.Appearance.ImageText(
                        displayText = spaceLabel,
                        textSize = appearance.textSize,
                        textStyle = appearance.textStyle,
                        src = appearance.src,
                        percentWidth = appearance.percentWidth,
                        variant = appearance.variant,
                        border = appearance.border,
                        margin = appearance.margin,
                        viewId = appearance.viewId,
                        soundEffect = appearance.soundEffect,
                    )
                )
            } else {
                // 图标隐藏 = 只剩居中主文本，等价于一个 Text 键（保留 viewId 以命中特殊形状）
                def.withAppearance(
                    KeyDef.Appearance.Text(
                        displayText = spaceLabel,
                        textSize = appearance.textSize,
                        textStyle = appearance.textStyle,
                        percentWidth = appearance.percentWidth,
                        variant = appearance.variant,
                        border = appearance.border,
                        margin = appearance.margin,
                        viewId = appearance.viewId,
                        soundEffect = appearance.soundEffect,
                    )
                )
            }
        }

        return when (appearance) {
            // 字母键：主文本按 caps 变换；副文本按标点映射变换（View: updateAlphabetKeys + updatePunctuationKeys）
            is KeyDef.Appearance.AltText -> def.withAppearance(
                KeyDef.Appearance.AltText(
                    displayText = appearance.displayText.let { str ->
                        if (str.length != 1 || !str[0].isLetter()) {
                            str
                        } else if (keepLettersUppercase) {
                            str.uppercase()
                        } else {
                            transformAlphabet(str)
                        }
                    },
                    altText = transformPunctuation(appearance.altText),
                    textSize = appearance.textSize,
                    textStyle = appearance.textStyle,
                    percentWidth = appearance.percentWidth,
                    variant = appearance.variant,
                    border = appearance.border,
                    margin = appearance.margin,
                    viewId = appearance.viewId,
                )
            )

            // 其余文本键：只对非字母/非空白的首字符做标点映射（View: updatePunctuationKeys 的 else 分支）
            is KeyDef.Appearance.ImageText -> def.withAppearance(
                KeyDef.Appearance.ImageText(
                    displayText = transformPlainText(appearance.displayText),
                    textSize = appearance.textSize,
                    textStyle = appearance.textStyle,
                    src = appearance.src,
                    percentWidth = appearance.percentWidth,
                    variant = appearance.variant,
                    border = appearance.border,
                    margin = appearance.margin,
                    viewId = appearance.viewId,
                    soundEffect = appearance.soundEffect,
                )
            )

            is KeyDef.Appearance.Text -> def.withAppearance(
                KeyDef.Appearance.Text(
                    displayText = transformPlainText(appearance.displayText),
                    textSize = appearance.textSize,
                    textStyle = appearance.textStyle,
                    percentWidth = appearance.percentWidth,
                    variant = appearance.variant,
                    border = appearance.border,
                    margin = appearance.margin,
                    viewId = appearance.viewId,
                    soundEffect = appearance.soundEffect,
                )
            )

            // Image（caps/回车/退格/语言）在各自分支处理或原样保留
            is KeyDef.Appearance.Image -> def
        }
    }

    private fun transformPlainText(text: String): String {
        val first = text.firstOrNull() ?: return text
        if (first.isLetter() || first.isWhitespace()) return text
        return transformPunctuation(text)
    }
}

/** 保留 behaviors / popup，只换键面。 */
private fun KeyDef.withAppearance(appearance: KeyDef.Appearance) =
    KeyDef(appearance, behaviors, popup)

private fun KeyDef.Appearance.Image.copyImage(src: Int) = KeyDef.Appearance.Image(
    src = src,
    percentWidth = percentWidth,
    variant = variant,
    border = border,
    margin = margin,
    viewId = viewId,
    soundEffect = soundEffect,
)

/**
 * Compose 版文本键盘（批次 C2）：状态 → 键面 → [ComposeKeyRow]。
 *
 * @param split 分体键盘（C3 传 `AppPrefs.keyboard.splitKeyboard` + `isSplitAllowed`）
 * @param cancelEpoch 容器层区域手势（如浮动键盘拖动）生效时递增，见 [ComposeKey]
 */
@Composable
fun ComposeTextKeyboard(
    state: TextKeyboardState,
    keyActionListener: KeyActionListener?,
    popupActionListener: PopupActionListener?,
    modifier: Modifier = Modifier,
    split: Boolean = false,
    gapRatio: Float = 0f,
    cancelEpoch: Int = 0,
) {
    val prefs = remember { AppPrefs.getInstance().keyboard }
    // 这两项 View 侧是靠监听即时生效的（不重建），故做成响应式
    val hapticOnRepeat = prefs.hapticOnRepeat.preferenceState()
    val spaceSwipeMoveCursor = prefs.spaceSwipeMoveCursor.preferenceState()
    val view = LocalView.current

    // View: TextKeyboard.onAction / onPopupAction 的变换挂在按键动作前后
    val transformedKeyActionListener = remember(state, keyActionListener) {
        KeyActionListener { action, source ->
            keyActionListener?.onKeyAction(state.transformAction(action, source), source)
        }
    }
    val transformedPopupActionListener = remember(state, popupActionListener) {
        PopupActionListener { popupActionListener?.onPopupAction(state.transformPopup(it)) }
    }

    ComposeKeyboardRows(
        rows = state.layout(),
        modifier = modifier.fillMaxSize(),
        split = split,
        gapRatio = gapRatio,
    ) { keyId, def, insets, keyModifier ->
        val swipeSpec = def.spaceAndBackspaceSwipeSpec(spaceSwipeMoveCursor)
        val gestureListener = remember(def, hapticOnRepeat) {
            def.spaceAndBackspaceGestureListener(
                view = view,
                onAction = { action ->
                    transformedKeyActionListener.onKeyAction(
                        action, KeyActionListener.Source.Keyboard
                    )
                },
                hapticOnRepeat = hapticOnRepeat,
            )
        }
        ComposeKey(
            def = def,
            keyId = keyId,
            modifier = keyModifier,
            insets = insets,
            keyActionListener = transformedKeyActionListener,
            popupActionListener = transformedPopupActionListener,
            swipeSpec = swipeSpec,
            onSwipeGesture = gestureListener,
            cancelEpoch = cancelEpoch,
        )
    }
}

/**
 * 空格 / 退格的键型滑行配置（对应 `BaseKeyboard.createKeyView` 里按键型设置的那段）：
 * 阈值 `selectionSwipeThreshold(10dp)` 用于横向，纵向禁用；`swipeRepeatEnabled = true`。
 *
 * 空格的启用条件还额外受 `spaceSwipeMoveCursor` 偏好控制。
 */
internal fun KeyDef.spaceAndBackspaceSwipeSpec(
    spaceSwipeMoveCursor: Boolean,
): ComposeKeySwipeSpec? = when (appearance.viewId) {
    // 按 viewId 判（键面变换会重建 KeyDef、丢掉子类类型）；MiniSpaceKey 不参与滑行
    R.id.button_space -> if (spaceSwipeMoveCursor) {
        ComposeKeySwipeSpec(
            thresholdX = KeySwipeThresholds.Selection,
            thresholdY = KeySwipeThresholds.Disabled,
            repeatEnabled = true,
        )
    } else {
        null
    }

    R.id.button_backspace -> ComposeKeySwipeSpec(
        thresholdX = KeySwipeThresholds.Selection,
        thresholdY = KeySwipeThresholds.Disabled,
        repeatEnabled = true,
    )

    else -> null
}

/**
 * 空格 / 退格的键型滑行处理（对应 `BaseKeyboard.createKeyView` 里的 `onGestureListener`）：
 *
 * - 空格：横向按格数依次发左右方向键（`countX > 0` 为右）；
 * - 退格：Move 发 `MoveSelectionAction(count)`，Up 发 `DeleteSelectionAction(totalX)`。
 *
 * @param view 仅用于 `InputFeedbacks.hapticFeedback`（重复振动）
 */
internal fun KeyDef.spaceAndBackspaceGestureListener(
    view: View,
    onAction: (KeyAction) -> Unit,
    hapticOnRepeat: Boolean,
): ComposeKeyGestureListener? = when (appearance.viewId) {
    R.id.button_space -> ComposeKeyGestureListener { event ->
        when (event.type) {
            ComposeKeyGestureEvent.Type.Move -> {
                val count = event.countX
                if (count == 0) {
                    false
                } else {
                    val sym = if (count > 0) {
                        FcitxKeyMapping.FcitxKey_Right
                    } else {
                        FcitxKeyMapping.FcitxKey_Left
                    }
                    val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                    repeat(count.absoluteValue) {
                        onAction(action)
                        if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                    }
                    true
                }
            }

            else -> false
        }
    }

    R.id.button_backspace -> ComposeKeyGestureListener { event ->
        when (event.type) {
            ComposeKeyGestureEvent.Type.Move -> {
                val count = event.countX
                if (count != 0) {
                    onAction(KeyAction.MoveSelectionAction(count))
                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                    true
                } else {
                    false
                }
            }

            ComposeKeyGestureEvent.Type.Up -> {
                onAction(KeyAction.DeleteSelectionAction(event.totalX))
                false
            }

            else -> false
        }
    }

    else -> null
}
