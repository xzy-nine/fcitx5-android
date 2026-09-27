/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.compose.dialog.RawListEditMode
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigBool
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnum
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnumList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigInt
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigKey
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigString
import org.fcitx.fcitx5.android.utils.config.ConfigType

/**
 * How a single fcitx [ConfigDescriptor] row must be rendered.
 *
 * The decision is made **only** from the descriptor, never from the presence of a value node under
 * the fcitx `cfg` tree. This mirrors the legacy View renderer
 * ([org.fcitx.fcitx5.android.ui.main.settings.PreferenceScreenFactory]) and is what keeps rows out
 * of the "unimplemented type" state: fcitx `ExternalOption` / `SubConfigOption` are `skipSave()`d
 * (see fcitx-config `option.cpp`) and therefore *never* exist in `cfg`, while they still have to be
 * rendered, because tapping them opens a sub-editor.
 */
sealed interface RawConfigRowSpec {
    /** `ConfigBool` → switch. */
    data object Bool : RawConfigRowSpec

    /** `ConfigEnum` → dropdown. */
    data class Enum(val entries: List<String>, val entriesI18n: List<String>?) : RawConfigRowSpec

    /** `ConfigInt` → slider (both bounds known) or number dialog (unbounded). */
    data class Number(val min: Int?, val max: Int?) : RawConfigRowSpec

    /** `ConfigString` → text dialog. */
    data object Text : RawConfigRowSpec

    /** `ConfigKey` → key capture dialog. */
    data object Key : RawConfigRowSpec

    /** `ConfigEnumList` → multi-choice list editor. */
    data class EnumList(val entries: List<String>, val entriesI18n: List<String>?) : RawConfigRowSpec

    /** `ConfigList` of a supported subtype → list editor. */
    data class ListEditor(val mode: RawListEditMode) : RawConfigRowSpec

    /** `ConfigExternal` → opens another page / a dialog. */
    data object External : RawConfigRowSpec

    /** `ConfigCustom` → nested group, rendered by its custom type definition. */
    data object Group : RawConfigRowSpec

    /** Genuinely unsupported descriptor (unknown list subtype). */
    data class Unsupported(val typeName: String) : RawConfigRowSpec
}

/**
 * What an [ConfigExternal] row opens. Resolved from the descriptor alone so that it can be unit
 * tested and so that it never depends on `cfg` (external options are not saved by fcitx).
 */
sealed interface RawConfigExternalAction {
    data object PinyinDictionary : RawConfigExternalAction
    data class Punctuation(val title: String, val lang: String?) : RawConfigExternalAction
    data object QuickPhrase : RawConfigExternalAction
    data object PinyinCustomPhrase : RawConfigExternalAction
    data object TableInputMethods : RawConfigExternalAction
    data object RimeUserDataDir : RawConfigExternalAction

    /** Another addon's config page, e.g. `fcitx://config/addon/chttrans`. */
    data class AddonConfig(val addon: String) : RawConfigExternalAction

    /** No route is known for this uri; the row is shown as a disabled placeholder. */
    data class Unresolved(val uri: String?) : RawConfigExternalAction
}

/**
 * Parsed `fcitx://config/addon/...` uri. `subPath` is non-null for sub-config uris such as
 * `fcitx://config/addon/pinyin/dictmanager`.
 */
data class ExternalAddonUri(val addon: String, val subPath: String?)

/** Pure helpers backing [RawConfigScreen]; kept free of Compose so they can be unit tested. */
object RawConfigRows {

    private const val ADDON_URI_PREFIX = "fcitx://config/addon/"

    fun specOf(descriptor: ConfigDescriptor<*, *>): RawConfigRowSpec = when (descriptor) {
        is ConfigBool -> RawConfigRowSpec.Bool
        is ConfigEnum -> RawConfigRowSpec.Enum(descriptor.entries, descriptor.entriesI18n)
        is ConfigInt -> {
            val min = descriptor.intMin
            val max = descriptor.intMax
            if (min != null && max != null && max > min) {
                RawConfigRowSpec.Number(min, max)
            } else {
                RawConfigRowSpec.Number(null, null)
            }
        }
        is ConfigString -> RawConfigRowSpec.Text
        is ConfigKey -> RawConfigRowSpec.Key
        is ConfigEnumList -> RawConfigRowSpec.EnumList(descriptor.entries, descriptor.entriesI18n)
        is ConfigList -> when (descriptor.ty.subtype) {
            ConfigType.TyBool -> RawConfigRowSpec.ListEditor(RawListEditMode.Bool)
            ConfigType.TyInt -> RawConfigRowSpec.ListEditor(RawListEditMode.Number)
            ConfigType.TyString -> RawConfigRowSpec.ListEditor(RawListEditMode.FreeText)
            ConfigType.TyKey -> RawConfigRowSpec.ListEditor(RawListEditMode.Key)
            // `List|Enum` is always parsed into ConfigEnumList, anything else (List|Custom,
            // List|External, ...) has no in-app editor.
            else -> RawConfigRowSpec.Unsupported(ConfigType.pretty(descriptor.ty))
        }
        is ConfigExternal -> RawConfigRowSpec.External
        is ConfigCustom -> RawConfigRowSpec.Group
    }

    /** Value shown while the option has no node in `cfg` (fcitx omits not-saved options). */
    fun defaultValueOf(descriptor: ConfigDescriptor<*, *>): String = when (descriptor) {
        is ConfigBool -> if (descriptor.defaultValue == true) "True" else "False"
        is ConfigEnum -> descriptor.defaultValue.orEmpty()
        is ConfigInt -> descriptor.defaultValue?.toString().orEmpty()
        is ConfigString -> descriptor.defaultValue.orEmpty()
        is ConfigKey -> descriptor.defaultValue.orEmpty()
        is ConfigEnumList -> ""
        is ConfigList -> ""
        is ConfigExternal -> ""
        is ConfigCustom -> ""
    }

    /** List value shown while the option has no node (or no sub items) in `cfg`. */
    fun defaultListOf(descriptor: ConfigDescriptor<*, *>): List<String> = when (descriptor) {
        is ConfigEnumList -> descriptor.defaultValue.orEmpty()
        is ConfigList -> descriptor.defaultValue?.map {
            when (it) {
                is ConfigList.ConfigListValue.BoolValue -> if (it.value) "True" else "False"
                is ConfigList.ConfigListValue.IntValue -> it.value.toString()
                is ConfigList.ConfigListValue.KeyValue -> it.value
                is ConfigList.ConfigListValue.StringValue -> it.value
            }
        }.orEmpty()
        else -> emptyList()
    }

    /**
     * `fcitx://config/addon/<addon>[/<sub path>]` → [ExternalAddonUri]. Returns null for uris that
     * fcitx uses for other purposes (command lines such as rime's `xdg-open ...`) or when the addon
     * part is missing.
     */
    fun parseAddonUri(uri: String?): ExternalAddonUri? {
        if (uri == null || !uri.startsWith(ADDON_URI_PREFIX)) return null
        val rest = uri.removePrefix(ADDON_URI_PREFIX).trim('/')
        if (rest.isEmpty()) return null
        val segments = rest.split('/').filter { it.isNotEmpty() }
        val addon = segments.firstOrNull() ?: return null
        val subPath = segments.drop(1).joinToString("/").ifEmpty { null }
        return ExternalAddonUri(addon, subPath)
    }

    /**
     * Resolves the action of an external row. Known names win (same table as the legacy View
     * renderer); otherwise a plain `fcitx://config/addon/<other>` uri is opened as that addon's
     * config page. [currentAddon] guards against navigating to the page we are already on.
     */
    fun externalActionOf(
        descriptor: ConfigExternal,
        currentAddon: String? = null,
    ): RawConfigExternalAction = when (descriptor.knownType) {
        ConfigExternal.ETy.PinyinDict -> RawConfigExternalAction.PinyinDictionary
        ConfigExternal.ETy.Punctuation -> RawConfigExternalAction.Punctuation(
            title = descriptor.description ?: descriptor.name,
            lang = descriptor.uri?.substringAfterLast('/'),
        )
        ConfigExternal.ETy.QuickPhrase -> RawConfigExternalAction.QuickPhrase
        ConfigExternal.ETy.PinyinCustomPhrase -> RawConfigExternalAction.PinyinCustomPhrase
        ConfigExternal.ETy.AndroidTable -> RawConfigExternalAction.TableInputMethods
        ConfigExternal.ETy.RimeUserDataDir -> RawConfigExternalAction.RimeUserDataDir
        ConfigExternal.ETy.Chttrans -> RawConfigExternalAction.AddonConfig("chttrans")
        ConfigExternal.ETy.TableGlobal -> RawConfigExternalAction.AddonConfig("table")
        null -> {
            val parsed = parseAddonUri(descriptor.uri)
            if (parsed != null && parsed.subPath == null && parsed.addon != currentAddon) {
                RawConfigExternalAction.AddonConfig(parsed.addon)
            } else {
                RawConfigExternalAction.Unresolved(descriptor.uri)
            }
        }
    }
}

/**
 * Read/write access to one option of a fcitx config node.
 *
 * A missing node is **not** an error: fcitx does not save `External`/`SubConfig` options and a
 * config file written by an older version may lack keys added later. Reads fall back to the
 * descriptor default and writes create the node on demand (fcitx loads addon configs partially, so
 * adding a key neither resets nor breaks the untouched options).
 */
class RawConfigValue(private val parent: RawConfig, private val name: String) {

    val node: RawConfig? get() = parent.findByName(name)

    fun read(fallback: String): String = node?.value ?: fallback

    fun write(value: String) {
        parent.getOrCreate(name).value = value
    }

    fun readValues(fallback: List<String>): List<String> =
        node?.subItems?.map { it.value } ?: fallback

    fun writeValues(values: List<String>) {
        parent.getOrCreate(name).subItems =
            values.mapIndexed { index, value -> RawConfig(index.toString(), value) }.toTypedArray()
    }
}
