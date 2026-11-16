/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.ComposeCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    // Compose 实现的候选栏组件
    // 旧 View 实现：private val horizontalCandidate: HorizontalCandidateComponent by manager.must()（已断开接线）
    private val composeCandidate: ComposeCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    // custom: 内置语音输入入口
    private val voiceInput: org.fcitx.fcitx5.android.input.voice.VoiceInputComponent by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior

    private var backspaceSwipeState = Stopped

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    // custom: 输入法选择器由 InputMethodUtil.showPicker() 直接调起，无需本类包装。
    val listener by lazy {
        // 用匿名对象而不是 SAM 简写：需要同时覆写 onKeyActionRelease（空格长按的物理松手）
        object : KeyActionListener {
            override fun onKeyActionRelease(action: KeyAction, source: KeyActionListener.Source) {
                if (action is SpaceLongPressAction &&
                    spaceKeyLongPressBehavior == SpaceLongPressBehavior.VoiceInput
                ) {
                    // 空格长按进入语音面板后，手指抬起即停止识别（"按住说话"语义）；
                    // 延时收尾由组件侧补 300ms 尾音窗口，避免句尾丢字
                    voiceInput.stopRecognitionWithTail()
                }
            }

            override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
                handleKeyAction(action)
            }
        }
    }

    private fun handleKeyAction(action: KeyAction) {
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                // custom: QuickPhrase / Unicode 是 fcitx addon，本构建只带 Rime 引擎，不提供这些动作。
                is LangSwitchAction -> {
                    // 本构建只带 Rime 引擎：语言键切换引擎的 ascii_mode，
                    // 对应「中文 / 英文」两种输入状态。
                    service.postFcitxJob { toggleIme() }
                }
                is ShowInputMethodPickerAction -> InputMethodUtil.showPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                composeCandidate.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> InputMethodUtil.showPicker()
                        SpaceLongPressBehavior.VoiceInput -> {
                            // custom: 内置语音输入优先；未启用或引擎未就绪时由
                            // VoiceInputComponent 自行回落到外部语音输入法（保持既有行为）。
                            // 空格长按进入面板：出字后自动回到主键盘。
                            voiceInput.onVoiceEntryClicked(returnToKeyboardOnCommit = true)
                        }
                    }
                }
                else -> {}
            }
    }
}
