/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose

import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Compose navigation routes for the main window.
 *
 * Routes with parameters are intentionally kept out of [LegacyTarget] (and therefore the
 * serializable compose back stack): parameterized settings destinations are only reachable
 * from inside the legacy fragment stack, which lives in its own NavController.
 */
@Serializable
sealed interface AppRoute : NavKey {

    @Serializable
    data object Index : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data object Licenses : AppRoute

    @Serializable
    data object Developer : AppRoute

    @Serializable
    data object WebDavSync : AppRoute

    /** 数据与备份（用户数据目录、导出导入、方案与词典更新入口）。 */
    @Serializable
    data object DataBackup : AppRoute

    /** 导入 Rime 方案 ZIP。 */
    @Serializable
    data object RimeImport : AppRoute

    /** Rime 方案更新任务列表。 */
    @Serializable
    data object RimeSchemeUpdate : AppRoute

    /** 万象更新。 */
    @Serializable
    data object WanxiangUpdate : AppRoute

    /** 设置分组页，内容由规格表按 id 决定。 */
    @Serializable
    data class SettingsGroup(val id: String) : AppRoute

    /** 更新镜像规则列表。 */
    @Serializable
    data object UpdateMirrorList : AppRoute

    /** 更新加速 Hosts 设置。 */
    @Serializable
    data object UpdateHostsSettings : AppRoute

    /** 编辑单条镜像规则；为空表示新建。 */
    @Serializable
    data class UpdateMirrorEdit(val mirrorId: String? = null) : AppRoute

    /** 文本键盘布局 profile 管理。 */
    @Serializable
    data object TextKeyboardLayoutProfiles : AppRoute

    /** 图标主题列表。 */
    @Serializable
    data object IconThemeList : AppRoute

    /** 设置界面外观。 */
    @Serializable
    data object SettingsUiAppearance : AppRoute

    /** 设置界面动效。 */
    @Serializable
    data object SettingsUiMotion : AppRoute

    /** 键盘形态：分体、浮动与单手。 */
    @Serializable
    data object KeyboardModes : AppRoute

    /** 键盘设置分组页。 */
    @Serializable
    data class KeyboardGroup(val group: Int) : AppRoute

    @Serializable
    data class Legacy(val target: LegacyTarget) : AppRoute


    /** 多选列表页（如「启用的输入方案」），由参数决定打开的 addon 与选项。 */
    @Serializable
    data class MultiSelect(
        val title: String,
        val addon: String,
        val path: String,
        val option: String,
        val min: Int,
    ) : AppRoute

    /** 数据目录管理页。 */
    @Serializable
    data class AddonDirProfile(
        val title: String,
        val addon: String,
        val path: String,
    ) : AppRoute

    @Serializable
    data class Prefs(val category: PrefCategory, val highlightKey: String? = null) : AppRoute

    @Serializable
    data class RawConfigHost(
        val kind: RawConfigHostType,
        val name: String? = null,
        val uniqueName: String? = null,
    ) : AppRoute

    @Serializable
    data object InputMethodList : AppRoute

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : AppRoute

    @Serializable
    data object AddonList : AppRoute

    @Serializable
    data class AddonConfig(val name: String, val uniqueName: String) : AppRoute

    data object Theme : AppRoute

    /** custom: 语音输入设置页（内置离线/在线引擎的平台与模型配置） */
    @Serializable
    data object VoiceInputSettings : AppRoute

    /** custom: 模型市场（**公共组件**，按 category 分流：语音 `asr` / 数字墨水 `digitalink`） */
    @Serializable
    data class ModelMarket(val category: String) : AppRoute

    /** custom: 模型市场**父页**（各分类入口；语音与手写的「模型」入口仍直达各自子页） */
    @Serializable
    data object ModelMarketHome : AppRoute

    /** custom: 在线 ASR 平台的声明式配置表单（按平台 id） */
    @Serializable
    data class VoiceProviderConfig(val providerId: String) : AppRoute

    /** custom: 手写输入设置页（独立输入方案） */
    @Serializable
    data object HandwritingSettings : AppRoute

    /** custom: 触控笔手势动作演示 + 试用页（设置页内直接演示） */
    @Serializable
    data object HandwritingGestureDemo : AppRoute

    /** custom: 整段语音预处理测试页（仅 debug 构建注册路由） */
    @Serializable
    data object VoicePreprocessDebug : AppRoute
}

@Serializable
enum class RawConfigHostType {
    GlobalConfig,
    InputMethodConfig,
    AddonConfig,
    PhysicalHotkey;
}

@Serializable
enum class PrefCategory {
    Advanced,
    Keyboard,
    Candidates,
    Clipboard,
    Broadcast,
    Symbols,
    TextEditing;

    fun provider() = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().let {
        when (this) {
            Advanced -> it.advanced
            Keyboard -> it.keyboard
            Candidates -> it.candidates
            Clipboard -> it.clipboard
            Broadcast -> it.broadcast
            Symbols -> it.symbols
            TextEditing -> it.textEditing
        }
    }
}

@Serializable
enum class LegacyTarget {
    /** 首页锚点：设置首页本身由 Compose 渲染，该目标只用于旧路由落地。 */
    Index;
}
