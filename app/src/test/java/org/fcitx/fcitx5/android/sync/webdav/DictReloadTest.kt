/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import org.fcitx.fcitx5.android.sync.webdav.DictReload.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictReloadTest {

    @Test
    fun userDictIsItsOwnKindBecauseEngineOwnsItInMemory() {
        assertEquals(Kind.USER_DICT, DictReload.kindOf("pinyin/user.dict"))
        // 引擎用内存内容覆写该文件，热重载无效，必须重建进程
        assertTrue(DictReload.needsRestart(setOf(Kind.USER_DICT)))
    }

    @Test
    fun customPhraseIsSeparateReloadEntryFromPinyinDict() {
        // customphrase 与 dictionaries 是两个独立的热重载入口，不能混为一类
        assertEquals(Kind.CUSTOM_PHRASE, DictReload.kindOf("pinyin/customphrase"))
        assertEquals(Kind.PINYIN, DictReload.kindOf("pinyin/dictionaries/my.dict"))
        assertFalse(DictReload.needsRestart(setOf(Kind.CUSTOM_PHRASE)))
        assertFalse(DictReload.needsRestart(setOf(Kind.PINYIN)))
    }

    @Test
    fun quickPhraseUserDictUnderDataDirIsReloadable() {
        // 用户 QuickPhrase 词库实际位于 data/quickphrase.d/（相对 data/ 的路径以 data/ 开头）
        assertEquals(
            Kind.QUICKPHRASE,
            DictReload.kindOf("data/quickphrase.d/email.mb")
        )
        assertEquals(Kind.QUICKPHRASE, DictReload.kindOf("quickphrase/email.mb"))
        assertFalse(DictReload.needsRestart(setOf(Kind.QUICKPHRASE)))
    }

    @Test
    fun tableAndUnknownPathsNeedRestart() {
        assertEquals(Kind.TABLE, DictReload.kindOf("table/wubi.main.dict"))
        assertEquals(Kind.TABLE, DictReload.kindOf("inputmethod/wubi.conf"))
        // 未知目录保守按 table 处理，保证内容一定生效
        assertEquals(Kind.TABLE, DictReload.kindOf("something/unknown.bin"))
        assertTrue(DictReload.needsRestart(setOf(Kind.TABLE)))
        assertTrue(DictReload.needsRestart(setOf(Kind.TABLE, Kind.PINYIN)))
    }

    @Test
    fun noKindsMeansNoRestart() {
        assertFalse(DictReload.needsRestart(emptySet()))
    }
}
