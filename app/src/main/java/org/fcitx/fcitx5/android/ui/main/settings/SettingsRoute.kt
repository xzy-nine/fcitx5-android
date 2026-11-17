/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Parcelable
import androidx.navigation.NavType
import androidx.savedstate.SavedState
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.parcelable
import kotlin.reflect.KType
import kotlin.reflect.typeOf

@Parcelize
sealed class SettingsRoute : Parcelable {

    /* ========== Index ========== */

    @Serializable
    data object Index : SettingsRoute()

    /* ========== Fcitx ========== */

    @Serializable
    data object GlobalConfig : SettingsRoute()

    @Serializable
    data object InputMethodList : SettingsRoute()

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : SettingsRoute()

    @Serializable
    data object AddonList : SettingsRoute()

    /* ========== Android ========== */

    /**
     * 首页一级入口下的分组页，由 [SettingsGroupSpecs] 驱动。
     * 用 id 标识分组，避免为每个分组各建一个路由类与 Fragment。
     */
    @Serializable
    data class SettingsGroup(val id: String) : SettingsRoute()

    @Serializable
    data object Theme : SettingsRoute()

    @Serializable
    data object SettingsUiAppearance : SettingsRoute()

    @Serializable
    data object SettingsUiMotion : SettingsRoute()

    @Serializable
    data object TextKeyboardLayoutProfiles : SettingsRoute()

    @Serializable
    data object IconTheme : SettingsRoute()

    @Serializable
    data object VirtualKeyboard : SettingsRoute()

    @Serializable
    data class KeyboardGroup(val group: Int) : SettingsRoute()

    @Serializable
    data object CandidatesWindow : SettingsRoute()

    @Serializable
    data object Clipboard : SettingsRoute()

    @Serializable
    data object Broadcast : SettingsRoute()

    @Serializable
    data object Symbol : SettingsRoute()

    @Serializable
    data object TextEditing : SettingsRoute()

    /** 浮动键盘与单手键盘。原先没有任何设置入口，只能靠工具栏按钮触发。 */
    @Serializable
    data object KeyboardModes : SettingsRoute()

    /** 数据与备份：从「高级」拆出，避免与兼容性开关混在一起。 */
    @Serializable
    data object DataBackup : SettingsRoute()

    @Serializable
    data object Advanced : SettingsRoute()

    @Serializable
    data object Developer : SettingsRoute()

    @Serializable
    data object License : SettingsRoute()

    @Serializable
    data object About : SettingsRoute()

    /* ========== External ========== */

    @Serializable
    data class ListConfig(val params: Params) : SettingsRoute() {
        @Parcelize
        @Serializable
        data class Params(val cfg: RawConfig, val desc: ConfigDescriptor<*, *>) : Parcelable {
            companion object {
                // https://developer.android.com/guide/navigation/design/kotlin-dsl#custom-types
                val NavType = object : NavType<Params>(isNullableAllowed = false) {
                    override fun put(bundle: SavedState, key: String, value: Params) {
                        bundle.putParcelable(key, value)
                    }

                    override fun get(bundle: SavedState, key: String): Params? {
                        return bundle.parcelable<Params>(key)
                    }

                    override fun serializeAsValue(value: Params): String {
                        // Serialized values must always be Uri encoded
                        return Uri.encode(Json.encodeToString(value))
                    }

                    override fun parseValue(value: String): Params {
                        // Navigation decodes the string before passing it to parseValue()
                        return Json.decodeFromString(value)
                    }
                }

                val TypeMap: Map<KType, NavType<*>> =
                    mapOf(typeOf<Params>() to NavType)
            }
        }

        constructor(cfg: RawConfig, desc: ConfigDescriptor<*, *>) : this(Params(cfg, desc))

        val desc: ConfigDescriptor<*, *>
            get() = params.desc
        val cfg: RawConfig
            get() = params.cfg
    }

    @Serializable
    data class MultiSelect(
        val title: String,
        val addon: String,
        val path: String,
        val option: String,
        val min: Int = 0
    ) : SettingsRoute()

    @Serializable
    data class AddonDirProfileManager(
        val title: String,
        val addon: String,
        val path: String
    ) : SettingsRoute()

}
