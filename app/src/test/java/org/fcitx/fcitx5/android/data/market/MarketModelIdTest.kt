/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketModelIdTest {

    @Test
    fun `valid ids pass`() {
        assertTrue(MarketModelId.isValid("en"))
        assertTrue(MarketModelId.isValid("zh-Hani-CN"))
        assertTrue(MarketModelId.isValid("model-1"))
    }

    @Test
    fun `empty or blank ids are rejected`() {
        assertFalse(MarketModelId.isValid(""))
        assertFalse(MarketModelId.isValid("   "))
    }

    @Test
    fun `dot-prefixed ids are rejected`() {
        assertFalse(MarketModelId.isValid("."))
        assertFalse(MarketModelId.isValid(".."))
        assertFalse(MarketModelId.isValid(".hidden"))
        assertFalse(MarketModelId.isValid("..x"))
    }

    @Test
    fun `ids with path separators are rejected`() {
        assertFalse(MarketModelId.isValid("a/b"))
        assertFalse(MarketModelId.isValid("a\\b"))
        assertFalse(MarketModelId.isValid("a/b/c"))
    }
}