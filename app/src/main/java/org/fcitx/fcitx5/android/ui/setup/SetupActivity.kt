/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.NotificationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.hasUndonePage
import org.fcitx.fcitx5.android.utils.notificationManager
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                SetupScreen(onFinish = { finish() })
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.setup_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onPause() {
        if (SetupPage.hasUndonePage())
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_keyboard_24)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.setup_keyboard))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        super.onPause()
    }

    override fun onResume() {
        notificationManager.cancel(NOTIFY_ID)
        super.onResume()
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 233

        /**
         * Show the onboarding whenever the IME is not set up (not enabled or not selected). The
         * previous in-memory `shown` flag suppressed re-prompting within the same process; after a
         * build update the system may reset the IME selection ("losing permissions"), so we always
         * check the actual IME state instead and re-run the guide when needed.
         */
        fun shouldShowUp() = hasUndonePage()
    }
}
