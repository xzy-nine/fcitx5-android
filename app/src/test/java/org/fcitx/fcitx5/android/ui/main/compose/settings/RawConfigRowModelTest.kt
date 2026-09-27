/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.compose.settings

import arrow.core.getOrElse
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.compose.dialog.RawListEditMode
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression guard for the addon config pages ("设置 → 附加组件 → 具体附加组件").
 *
 * The page used to show `⛔ 未实现类型` for every fcitx `ExternalOption` / `SubConfigOption`
 * (DictManager / CustomPhrase / Punctuation / Chttrans / QuickPhrase / TableGlobal / …) because the
 * renderer bailed out whenever the option had no node under `cfg`. fcitx *never* saves those
 * options (`ExternalOption::skipSave() == true`), so the row shape must be derived from the
 * descriptor only; a missing node may only affect the initial value.
 *
 * The fixtures below mirror a real `getAddonConfig("pinyin")` dump (desc from
 * `Configuration::dumpDescription`, cfg from `Configuration::save`).
 */
class RawConfigRowModelTest {

    // region fixtures

    private fun leaf(name: String, value: String) = RawConfig(name, value)

    private fun node(name: String, vararg children: RawConfig) =
        RawConfig(name, children.toList().toTypedArray())

    private fun desc(name: String, type: String, description: String = name, vararg extra: RawConfig) =
        node(name, leaf("Type", type), leaf("Description", description), *extra)

    private fun enumDesc(
        name: String,
        entries: List<String>,
        description: String = name,
        defaultValue: String? = null,
    ) = node(
        name,
        leaf("Type", "Enum"),
        leaf("Description", description),
        *listOfNotNull(defaultValue?.let { leaf("DefaultValue", it) }).toTypedArray(),
        node("Enum", *entries.mapIndexed { i, e -> leaf(i.toString(), e) }.toTypedArray()),
    )

    /** fcitx declares `External`/`SubConfig` options as `Type=External` + `External=<uri>`. */
    private fun externalDesc(name: String, uri: String?, description: String = name) = node(
        name,
        leaf("Type", "External"),
        leaf("Description", description),
        leaf("DefaultValue", ""),
        *listOfNotNull(uri?.let { leaf("External", it) }).toTypedArray(),
    )

    /** desc of the pinyin addon (`PinyinEngineConfig`) as returned by the fcitx daemon. */
    private fun pinyinDesc(): RawConfig {
        val top = node(
            "PinyinEngineConfig",
            enumDesc(
                "ShuangpinProfile",
                listOf("Ziranma", "MS", "Ziguang", "ABC", "Zhongwenzhixing", "PinyinJiajia", "Xiaohe", "GB Standard", "Custom"),
                "Shuangpin Profile",
                "Ziranma",
            ),
            desc("PageSize", "Integer", "Candidates Per Page", leaf("DefaultValue", "7"), leaf("IntMin", "3"), leaf("IntMax", "10")),
            desc("SpellEnabled", "Boolean", "Show English Candidates", leaf("DefaultValue", "True")),
            desc("LongWordLengthLimit", "Integer", "Prompt long word length", leaf("DefaultValue", "4"), leaf("IntMin", "0"), leaf("IntMax", "10")),
            desc("ChooseCharFromPhrase", "List|Key", "Choose Character from Phrase"),
            desc("QuickPhraseTriggerRegex", "List|String", "Regular expression to trigger quick phrase", leaf("IsRegex", "True")),
            externalDesc("DictManager", "fcitx://config/addon/pinyin/dictmanager", "Manage Dictionaries"),
            externalDesc("CustomPhrase", "fcitx://config/addon/pinyin/customphrase", "Manage Custom Phrase"),
            node(
                "Punctuation",
                leaf("Type", "External"),
                leaf("Description", "Punctuation"),
                leaf("DefaultValue", ""),
                leaf("External", "fcitx://config/addon/punctuation/punctuationmap/zh_CN"),
                leaf("LaunchSubConfig", "True"),
            ),
            node(
                "Chttrans",
                leaf("Type", "External"),
                leaf("Description", "Configure Simplified/Traditional Chinese Conversion"),
                leaf("DefaultValue", ""),
                leaf("External", "fcitx://config/addon/chttrans"),
                leaf("LaunchSubConfig", "True"),
            ),
            externalDesc("QuickPhrase", "fcitx://config/addon/quickphrase/editor", "Quick Phrase"),
            node("Fuzzy", leaf("Type", "Fuzzy\$FuzzyConfig"), leaf("Description", "Fuzzy Pinyin")),
        )
        val fuzzyDef = node(
            "Fuzzy\$FuzzyConfig",
            desc("VE_UE", "Boolean", "ue -> ve", leaf("DefaultValue", "True")),
            enumDesc("Correction", listOf("None", "Qwerty"), "Correction Layout", "Qwerty"),
            desc("PartialSp", "Boolean", "Match partial shuangpin", leaf("DefaultValue", "False")),
        )
        return RawConfig("", "", "", arrayOf(top, fuzzyDef))
    }

    /** cfg of the pinyin addon: every option above *except* the external ones (fcitx skips them). */
    private fun pinyinCfg(): RawConfig = RawConfig(
        "",
        "",
        "",
        arrayOf(
            RawConfig("ShuangpinProfile", "Ziranma"),
            RawConfig("PageSize", "7"),
            RawConfig("SpellEnabled", "True"),
            RawConfig("LongWordLengthLimit", "4"),
            node("ChooseCharFromPhrase", leaf("0", "bracketleft")),
            node("QuickPhraseTriggerRegex", leaf("0", ".(/|@)\$")),
            node(
                "Fuzzy",
                leaf("VE_UE", "True"),
                leaf("Correction", "None"),
                leaf("PartialSp", "False"),
            ),
        ),
    )

    private fun parseTopLevel(desc: RawConfig): ConfigDescriptor.ConfigTopLevelDef =
        ConfigDescriptor.parseTopLevel(desc).orFail("desc parse failed")

    /** [getOrElse] with a throwing default so the happy path keeps its precise type. */
    private fun <T> arrow.core.Either<*, T>.orFail(what: String): T =
        getOrElse { throw AssertionError("$what: $it") }

    // endregion

    @Test
    fun `no row of a real addon config is classified as unimplemented`() {
        val top = parseTopLevel(pinyinDesc())
        val unsupported = top.values.filterIsInstance<ConfigCustom>()
            .flatMap { it.customTypeDef?.values.orEmpty() }
            .plus(top.values)
            .filter { RawConfigRows.specOf(it) is RawConfigRowSpec.Unsupported }
            .map { it.name }
        assertEquals("unexpected unimplemented rows: $unsupported", emptyList<String>(), unsupported)
        // the fixture itself must not silently lose descriptors
        assertEquals(12, top.values.size)
    }

    @Test
    fun `external rows keep the legacy view destinations`() {
        val top = parseTopLevel(pinyinDesc())
        fun actionOf(name: String) = RawConfigRows.externalActionOf(
            top.values.first { it.name == name } as ConfigExternal,
            currentAddon = "pinyin",
        )

        assertEquals(RawConfigExternalAction.PinyinDictionary, actionOf("DictManager"))
        assertEquals(RawConfigExternalAction.PinyinCustomPhrase, actionOf("CustomPhrase"))
        assertEquals(
            RawConfigExternalAction.Punctuation("Punctuation", "zh_CN"),
            actionOf("Punctuation"),
        )
        assertEquals(RawConfigExternalAction.AddonConfig("chttrans"), actionOf("Chttrans"))
        assertEquals(RawConfigExternalAction.QuickPhrase, actionOf("QuickPhrase"))
    }

    @Test
    fun `table addon keeps the android-only manage-input-methods entry`() {
        // RawConfigHostScreen appends this descriptor to the desc of the table addon; it has no
        // cfg node at all, which used to turn it into an unimplemented row
        val tableEntry = node(
            "AndroidTable",
            leaf("Type", "External"),
            leaf("Description", "Manage Table Input Methods"),
        )
        val descriptor = ConfigDescriptor.parse(tableEntry).orFail("parse failed")
        assertEquals(RawConfigRowSpec.External, RawConfigRows.specOf(descriptor))
        assertEquals(
            RawConfigExternalAction.TableInputMethods,
            RawConfigRows.externalActionOf(descriptor as ConfigExternal),
        )
    }

    @Test
    fun `unknown addon uri opens that addon while deeper paths stay unresolved`() {
        val cloudPinyin = node(
            "CloudPinyin",
            leaf("Type", "External"),
            leaf("Description", "Configure Cloud Pinyin"),
            leaf("External", "fcitx://config/addon/cloudpinyin"),
        )
        val parsed = ConfigDescriptor.parse(cloudPinyin).orFail("parse failed")
        assertEquals(
            RawConfigExternalAction.AddonConfig("cloudpinyin"),
            RawConfigRows.externalActionOf(parsed as ConfigExternal, currentAddon = "pinyin"),
        )
        // never navigate into the page we are already on
        assertEquals(
            RawConfigExternalAction.Unresolved("fcitx://config/addon/cloudpinyin"),
            RawConfigRows.externalActionOf(parsed, currentAddon = "cloudpinyin"),
        )
        // a sub-config uri has no in-app route yet
        assertEquals(ExternalAddonUri("pinyin", "dictmanager"), RawConfigRows.parseAddonUri("fcitx://config/addon/pinyin/dictmanager"))
        assertNull(RawConfigRows.parseAddonUri("xdg-open \"/data/rime\""))
        assertNull(RawConfigRows.parseAddonUri(null))
        val subConfig = node(
            "SomethingDeep",
            leaf("Type", "External"),
            leaf("Description", "Deep"),
            leaf("External", "fcitx://config/addon/pinyin/dictmanager"),
        )
        val deep = ConfigDescriptor.parse(subConfig).orFail("parse failed")
        assertTrue(
            RawConfigRows.externalActionOf(deep as ConfigExternal) is RawConfigExternalAction.Unresolved
        )
    }

    @Test
    fun `missing nodes only affect the initial value and are created on write`() {
        val cfg = pinyinCfg()
        val value = RawConfigValue(cfg, "PageSize")
        assertEquals("7", value.read(RawConfigRows.defaultValueOf(parseTopLevel(pinyinDesc()).values.first { it.name == "PageSize" })))

        // fcitx never writes External options into cfg
        val external = RawConfigValue(cfg, "DictManager")
        assertNull(external.node)
        assertEquals("", external.read(""))
        external.write("ignored")
        assertNotNull("write must create the node on demand", cfg.findByName("DictManager"))
        assertEquals("ignored", cfg.findByName("DictManager")?.value)
        // untouched siblings survive
        assertEquals("True", cfg.findByName("SpellEnabled")?.value)
    }

    @Test
    fun `descriptor defaults are used when cfg contains no node at all`() {
        val top = parseTopLevel(pinyinDesc())
        val emptyCfg = RawConfig("", "", "", arrayOf())
        fun valueOf(name: String): String {
            val descriptor = top.values.first { it.name == name }
            return RawConfigValue(emptyCfg, name).read(RawConfigRows.defaultValueOf(descriptor))
        }
        assertEquals("True", valueOf("SpellEnabled"))
        assertEquals("7", valueOf("PageSize"))
        assertEquals("Ziranma", valueOf("ShuangpinProfile"))
        assertEquals("", valueOf("QuickPhrase"))
        // and the row shape never depends on cfg: nothing turns unimplemented
        assertTrue(
            top.values.none { RawConfigRows.specOf(it) is RawConfigRowSpec.Unsupported }
        )
    }

    @Test
    fun `list rows read and write sub items and detect their subtype`() {
        val top = parseTopLevel(pinyinDesc())
        val cfg = pinyinCfg()

        val keys = top.values.first { it.name == "ChooseCharFromPhrase" }
        assertEquals(
            RawConfigRowSpec.ListEditor(RawListEditMode.Key),
            RawConfigRows.specOf(keys),
        )
        val keyValue = RawConfigValue(cfg, "ChooseCharFromPhrase")
        assertEquals(listOf("bracketleft"), keyValue.readValues(RawConfigRows.defaultListOf(keys)))
        keyValue.writeValues(listOf("a", "b"))
        assertEquals(listOf("a", "b"), keyValue.readValues(emptyList()))
        assertEquals("0", cfg.findByName("ChooseCharFromPhrase")?.subItems?.get(0)?.name)

        val regex = top.values.first { it.name == "QuickPhraseTriggerRegex" }
        assertEquals(
            RawConfigRowSpec.ListEditor(RawListEditMode.FreeText),
            RawConfigRows.specOf(regex),
        )
        assertEquals(listOf(".(/|@)\$"), RawConfigValue(cfg, "QuickPhraseTriggerRegex").readValues(RawConfigRows.defaultListOf(regex)))

        // a list option that fcitx did not save still renders as its editor
        val missing = RawConfigValue(cfg, "NotSavedList")
        assertEquals(emptyList<String>(), missing.readValues(RawConfigRows.defaultListOf(regex)))
    }

    @Test
    fun `custom groups expose their members and nested rows are renderable`() {
        val top = parseTopLevel(pinyinDesc())
        val fuzzy = top.values.first { it.name == "Fuzzy" } as ConfigCustom
        assertEquals(RawConfigRowSpec.Group, RawConfigRows.specOf(fuzzy))
        val members = fuzzy.customTypeDef?.values.orEmpty()
        assertEquals(3, members.size)
        assertFalse(members.any { RawConfigRows.specOf(it) is RawConfigRowSpec.Unsupported })
    }

    @Test
    fun `enum lists unbounded numbers and default-less booleans stay renderable`() {
        val enumList = node(
            "Sorted",
            leaf("Type", "List|Enum"),
            leaf("Description", "Sorted"),
            node("Enum", leaf("0", "a"), leaf("1", "b")),
        )
        assertEquals(
            RawConfigRowSpec.EnumList(listOf("a", "b"), null),
            RawConfigRows.specOf(parse(enumList)),
        )

        val unbounded = parse(
            desc("NoSortInputLength", "Integer", "Don't sort word shorter than", leaf("DefaultValue", "0"))
        )
        assertEquals(RawConfigRowSpec.Number(null, null), RawConfigRows.specOf(unbounded))
        assertEquals("0", RawConfigRows.defaultValueOf(unbounded))

        val noDefault = parse(desc("SpellEnabled", "Boolean", "Show English Candidates"))
        assertEquals(RawConfigRowSpec.Bool, RawConfigRows.specOf(noDefault))
        assertEquals("False", RawConfigRows.defaultValueOf(noDefault))
    }

    private fun parse(raw: RawConfig): ConfigDescriptor<*, *> =
        ConfigDescriptor.parse(raw).orFail("parse failed")
}
