/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingEngineKindTest {

    @Test
    fun `declaration order is the priority order`() {
        assertEquals(
            listOf(
                HandwritingEngineKind.System,
                HandwritingEngineKind.GoogleDigitalInk,
            ),
            HandwritingEngineKind.entries.toList(),
        )
    }

    @Test
    fun `system engine falls back to google`() {
        assertEquals(
            listOf(HandwritingEngineKind.System, HandwritingEngineKind.GoogleDigitalInk),
            HandwritingEngineKind.chainFrom(HandwritingEngineKind.System),
        )
    }

    @Test
    fun `google is the last resort`() {
        assertEquals(
            listOf(HandwritingEngineKind.GoogleDigitalInk),
            HandwritingEngineKind.chainFrom(HandwritingEngineKind.GoogleDigitalInk),
        )
    }
}
