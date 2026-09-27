/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音入口路由的可用性矩阵（对应用户要求：内置优先，未启用/未就绪时回落外部语音输入法）。
 */
class VoiceEntryRoutingTest {

    @Test
    fun `disabled with external voice ime falls back`() {
        assertEquals(
            VoiceEntryPlan.SwitchExternalIme,
            VoiceEntryRouting.plan(
                enabled = false,
                permissionGranted = true,
                useLocal = true,
                localModelReady = true,
                onlinePluginReady = false,
                hasExternalVoiceIme = true,
            )
        )
    }

    @Test
    fun `disabled without external voice ime does nothing`() {
        assertEquals(
            VoiceEntryPlan.Noop,
            VoiceEntryRouting.plan(
                enabled = false,
                permissionGranted = true,
                useLocal = true,
                localModelReady = true,
                onlinePluginReady = false,
                hasExternalVoiceIme = false,
            )
        )
    }

    @Test
    fun `missing permission still opens panel so user can grant`() {
        assertEquals(
            VoiceEntryPlan.OpenPanel,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = false,
                useLocal = true,
                localModelReady = false,
                onlinePluginReady = false,
                hasExternalVoiceIme = true,
            )
        )
    }

    @Test
    fun `local engine ready opens panel`() {
        assertEquals(
            VoiceEntryPlan.OpenPanel,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = true,
                useLocal = true,
                localModelReady = true,
                onlinePluginReady = false,
                hasExternalVoiceIme = false,
            )
        )
    }

    @Test
    fun `local model missing falls back to external ime`() {
        assertEquals(
            VoiceEntryPlan.SwitchExternalIme,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = true,
                useLocal = true,
                localModelReady = false,
                onlinePluginReady = true,
                hasExternalVoiceIme = true,
            )
        )
    }

    @Test
    fun `online engine ready opens panel`() {
        assertEquals(
            VoiceEntryPlan.OpenPanel,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = true,
                useLocal = false,
                localModelReady = true,
                onlinePluginReady = true,
                hasExternalVoiceIme = false,
            )
        )
    }

    @Test
    fun `online plugin not configured falls back to external ime`() {
        assertEquals(
            VoiceEntryPlan.SwitchExternalIme,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = true,
                useLocal = false,
                localModelReady = true,
                onlinePluginReady = false,
                hasExternalVoiceIme = true,
            )
        )
    }

    @Test
    fun `no engine and no external ime opens panel to guide setup`() {
        assertEquals(
            VoiceEntryPlan.OpenPanel,
            VoiceEntryRouting.plan(
                enabled = true,
                permissionGranted = true,
                useLocal = false,
                localModelReady = false,
                onlinePluginReady = false,
                hasExternalVoiceIme = false,
            )
        )
    }
}
