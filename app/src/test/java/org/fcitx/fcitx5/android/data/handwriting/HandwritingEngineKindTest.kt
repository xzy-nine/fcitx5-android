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
                HandwritingEngineKind.Onnx,
            ),
            HandwritingEngineKind.entries.toList(),
        )
    }

    @Test
    fun `system engine falls back to google then onnx`() {
        assertEquals(
            listOf(
                HandwritingEngineKind.System,
                HandwritingEngineKind.GoogleDigitalInk,
                HandwritingEngineKind.Onnx,
            ),
            HandwritingEngineKind.chainFrom(HandwritingEngineKind.System),
        )
    }

    @Test
    fun `google falls back to onnx only`() {
        assertEquals(
            listOf(HandwritingEngineKind.GoogleDigitalInk, HandwritingEngineKind.Onnx),
            HandwritingEngineKind.chainFrom(HandwritingEngineKind.GoogleDigitalInk),
        )
    }

    @Test
    fun `onnx is the last resort`() {
        assertEquals(
            listOf(HandwritingEngineKind.Onnx),
            HandwritingEngineKind.chainFrom(HandwritingEngineKind.Onnx),
        )
    }

    @Test
    fun `system and google recognize whole ink, onnx needs the segmenter`() {
        assertEquals(true, HandwritingEngineKind.System.wholeInk)
        assertEquals(true, HandwritingEngineKind.GoogleDigitalInk.wholeInk)
        assertEquals(false, HandwritingEngineKind.Onnx.wholeInk)
    }
}
