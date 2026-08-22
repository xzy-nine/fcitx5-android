/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.JavaVersion

object Versions {

    // NOTE: miuix 0.9.4-rc01 ships JVM 21 bytecode, so project bytecode target must be >= 21
    val java = JavaVersion.VERSION_21
    // NOTE: miuix 0.9.4-rc01 (+ androidx compose 1.12.0-rc01) requires compileSdk >= 37 and minSdk >= 24
    const val compileSdk = 37
    const val minSdk = 24
    const val targetSdk = 36

    const val defaultCMake = "3.31.6"
    const val defaultNDK = "28.0.13004108"
    const val defaultBuildTools = "36.1.0"

    // NOTE: increase this value to bump version code
    const val baseVersionCode = 11
    const val baseVersionName = "0.1.3"

    val supportedABIs = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    const val fallbackABI = "arm64-v8a"

    fun calculateVersionCode(abi: String = fallbackABI): Int {
        val abiId = when (abi) {
            "armeabi-v7a" -> 1
            "arm64-v8a" -> 2
            "x86" -> 3
            "x86_64" -> 4
            else -> 0
        }
        return baseVersionCode * 10 + abiId
    }
}
