/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor

internal object KeyboardTestMenu {

    /** 各宿主各自持有的面板实例；宿主销毁后条目自然失效。 */
    private val shown = java.util.WeakHashMap<Activity, KeyboardTestDialog>()

    internal fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val existing = shown[activity]
        if (existing?.isShowing == true) return
        val dialog = KeyboardTestDialog(activity)
        dialog.setOnDismissListener { if (shown[activity] === dialog) shown.remove(activity) }
        shown[activity] = dialog
        dialog.show()
    }

    /** 收起该宿主的测试面板；宿主停止时调用。 */
    internal fun dismiss(activity: Activity) {
        shown.remove(activity)?.dismiss()
    }

    fun add(menu: Menu, activity: Activity) {
        if (menu.findItem(R.id.keyboard_test_entry) != null) return
        menu.add(Menu.NONE, R.id.keyboard_test_entry, 100, R.string.keyboard_test_title).apply {
            icon = activity.getDrawable(R.drawable.ic_baseline_keyboard_24)?.mutate()?.apply {
                setTint(activity.settingsColor(com.google.android.material.R.attr.colorOnSurface))
            }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            MenuItemCompat.setContentDescription(this, activity.getString(R.string.keyboard_test_title))
            MenuItemCompat.setTooltipText(this, activity.getString(R.string.keyboard_test_title))
            setOnMenuItemClickListener { show(activity); true }
        }
    }

    fun attach(toolbar: Toolbar, activity: Activity) {
        // Page-owned Toolbar providers survive another provider rebuilding its menu.
        toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = add(menu, activity)
            override fun onMenuItemSelected(menuItem: MenuItem) = false
        })
    }
}
