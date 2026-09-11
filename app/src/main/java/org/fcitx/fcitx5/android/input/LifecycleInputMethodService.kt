/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.inputmethodservice.InputMethodService
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A base class for input method services that automatically handles the lifecycle.
 *
 * This class is a drop-in replacement for [InputMethodService] that automatically handles the
 * lifecycle. It also provides access to the [SavedStateRegistry] and [ViewModelStore] for
 * subclasses.
 *
 * This class is a drop-in replacement for [InputMethodService] that automatically handles the
 * lifecycle. It also provides access to the [SavedStateRegistry] and [ViewModelStore] for
 * subclasses.
 *
 * @see InputMethodService
 * @see SavedStateRegistry
 * @see ViewModelStore
 */
open class LifecycleInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    NavigationEventDispatcherOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }
    override val viewModelStore by lazy { ViewModelStore() }

    /**
     * `androidx.navigationevent` 的宿主（miuix `Overlay*` / `Dialog*` 弹层内部用
     * `NavigationBackHandler` 做返回手势，依赖它）。
     *
     * 该库通过 `ViewTreeHostDefaultKey`（tag = `R.id.view_tree_navigation_event_dispatcher_owner`）
     * 从 `LocalView` 起沿视图树向上查找 owner。Activity 场景由 `ComponentActivity` +
     * `activity-compose` 的 `setContent` 自动提供；**IME 场景没有任何人提供**，于是
     * `NavigationBackHandler` 里的 `checkNotNull(...)` 会在 composition 阶段直接抛
     * `IllegalStateException` —— 这类异常无法被业务代码捕获，最终升级为 FATAL EXCEPTION 崩进程。
     * 这里与 Lifecycle / SavedState / ViewModelStore 三个 owner 一起挂到 `decorView` 上补全。
     *
     * 注意语义：这是一个「根 dispatcher」，既没有输入源（IME 窗口不可聚焦、收不到返回手势），
     * 也没有 fallback，因此注册进来的 handler 只是**不生效**，不会误触发任何行为。
     */
    private val _navigationEventDispatcher by lazy { NavigationEventDispatcher() }

    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = _navigationEventDispatcher

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        val decorView = window.window!!.decorView
        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)
        decorView.setViewTreeNavigationEventDispatcherOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /**
     * Promote the lifecycle to RESUMED once the IME window is actually visible.
     *
     * Without this the lifecycle would be stuck in STARTED forever, which silently breaks every
     * consumer that gates on RESUMED (`repeatOnLifecycle(RESUMED)`, `LifecycleEventEffect`,
     * `flowWithLifecycle(RESUMED)`, ...). If a device never delivers this callback the state just
     * stays STARTED, which is the pre-existing behaviour — no regression.
     */
    @CallSuper
    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @CallSuper
    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        // handleLifecycleEvent walks down through the intermediate states, so a pending
        // RESUMED is demoted to STARTED/STOPPED automatically.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        // 与 ComponentActivity 一致：销毁时释放导航事件 dispatcher。
        // 此刻所有 ComposeView 的 composition 均已 dispose（handler 由各自的
        // DisposableEffect 摘除，removeHandler 不受 disposed 状态约束），故安全。
        _navigationEventDispatcher.dispose()
    }
}
