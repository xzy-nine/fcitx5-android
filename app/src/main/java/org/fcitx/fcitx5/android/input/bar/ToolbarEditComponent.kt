/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler

/**
 * 工具栏布局的编辑会话。
 *
 * 唯一持有「正在编辑的布局草稿」的地方：工具栏可停靠行与更多页按钮池是两个独立
 * ComposeView，但同处一个进程，因此共享这里的 Compose 状态即可实现跨层拖动。
 *
 * **落盘语义**：拖动期间只改 [draft]；[commit] 才写偏好。[cancel] 丢弃草稿 ——
 * 任何离开更多页的路径（返回、被其他窗口顶掉、视图树销毁）都走 [cancel]，
 * 因此「没点完成就离开」不会留下半成品布局。
 */
class ToolbarEditComponent :
    UniqueComponent<ToolbarEditComponent>(),
    Dependent,
    ManagedHandler by managedHandler() {

    private val prefs = AppPrefs.getInstance().keyboard

    private fun readPrefs(): ToolbarLayoutState = ToolbarLayout.sanitize(
        prefs.toolbarDockedButtons.getValue(),
        prefs.toolbarPoolButtons.getValue(),
    )

    /**
     * 已保存（生效中）的布局。
     *
     * 刻意保存在内存里而不是每次读偏好：偏好变更会经 [ToolbarEditComponent] 的观察者
     * 同步进来，而拖动的每一帧都要读它做落点判定，走 SharedPreferences 太贵。
     */
    private val _committed = MutableStateFlow(readPrefs())
    val committed: StateFlow<ToolbarLayoutState> = _committed.asStateFlow()

    /** 编辑中的草稿；非编辑态恒等于 [committed]。 */
    private val _draft = MutableStateFlow(_committed.value)
    val draft: StateFlow<ToolbarLayoutState> = _draft.asStateFlow()

    /**
     * 渲染用布局：编辑中为草稿，否则为已保存值。
     *
     * 不变量：非编辑态下 [_draft] 恒等于 [_committed]（[beginEdit] 同步、[cancel] 回滚、
     * [commit] 后两者相等、[refreshFromPrefs] 在非编辑态下也同步），因此渲染区只需读这一条流。
     */
    val draftOrCommitted: StateFlow<ToolbarLayoutState> = _draft.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _showUndoRedo = MutableStateFlow(prefs.toolbarShowUndoRedo.getValue())
    val showUndoRedo: StateFlow<Boolean> = _showUndoRedo.asStateFlow()

    /**
     * 拖动的瞬时状态。
     *
     * 放在会话里而不是各渲染区：工具栏行与更多页网格是两个独立 Composition，
     * 只有共享同一份状态才能跨区命中（见 [ToolbarDragState]）。
     */
    val drag = ToolbarDragState().apply {
        // 拖动回调里现取当前布局，避免闭包捕获到重排前的陈旧列表
        layoutProvider = { _draft.value }
    }

    /** 布局偏好被外部改写（如恢复备份）时重读；编辑中被改写则以已保存值覆盖草稿。 */
    private val layoutPrefListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        refreshFromPrefs()
    }
    private val undoRedoPrefListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        _showUndoRedo.value = value
    }

    fun registerPreferenceListeners() {
        prefs.toolbarDockedButtons.registerOnChangeListener(layoutPrefListener)
        prefs.toolbarPoolButtons.registerOnChangeListener(layoutPrefListener)
        prefs.toolbarShowUndoRedo.registerOnChangeListener(undoRedoPrefListener)
    }

    fun unregisterPreferenceListeners() {
        prefs.toolbarDockedButtons.unregisterOnChangeListener(layoutPrefListener)
        prefs.toolbarPoolButtons.unregisterOnChangeListener(layoutPrefListener)
        prefs.toolbarShowUndoRedo.unregisterOnChangeListener(undoRedoPrefListener)
    }

    /**
     * 拖动松手：把按钮落到目标区，并立即写入草稿（不改偏好，偏好只在 [commit] 写）。
     *
     * [drop] 为 null 表示落在无效区域，直接回弹（草稿不变）。
     */
    fun applyDrop(button: ToolbarButton, drop: ToolbarLayout.Drop?) {
        if (!_editing.value) return
        if (drop == null) return
        val current = _draft.value
        val next = ToolbarLayout.applyDrop(current, button, drop)
        if (next != current) _draft.value = next
    }

    /** 重读布局与撤销开关偏好。 */
    private fun refreshFromPrefs() {
        val state = readPrefs()
        _committed.value = state
        // 编辑中不让外部值覆盖草稿：用户手上的拖动结果更权威，cancel 时才回落到已保存值
        if (!_editing.value) _draft.value = state
    }

    fun beginEdit() {
        if (_editing.value) return
        _draft.value = _committed.value
        drag.clearGeometry()
        _editing.value = true
    }

    /** 保存草稿。未处于编辑态时是空操作。 */
    fun commit() {
        if (!_editing.value) return
        val state = _draft.value
        _committed.value = state
        _editing.value = false
        drag.cancel()
        writePrefs(state)
    }

    /** 丢弃草稿。未处于编辑态时是空操作。 */
    fun cancel() {
        if (!_editing.value) return
        _draft.value = _committed.value
        _editing.value = false
        drag.cancel()
    }

    /** 草稿回到默认布局（仍需 [commit] 才落盘）。 */
    fun resetDraft() {
        if (!_editing.value) return
        drag.cancel()
        _draft.value = ToolbarLayout.DEFAULT
    }

    /** 撤销/重做固定槽位的显隐，立即落盘（不属于布局草稿）。 */
    fun toggleUndoRedo() {
        val next = !_showUndoRedo.value
        _showUndoRedo.value = next
        prefs.toolbarShowUndoRedo.setValue(next)
    }

    private fun writePrefs(state: ToolbarLayoutState) {
        prefs.toolbarDockedButtons.setValue(ToolbarLayout.encode(state.docked))
        prefs.toolbarPoolButtons.setValue(ToolbarLayout.encode(state.pool))
    }
}
