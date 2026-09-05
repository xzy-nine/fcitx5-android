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
    ViewModelStoreOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }
    override val viewModelStore by lazy { ViewModelStore() }

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
    }
}
