// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Kingz Cheung
//
// 移植自 Xime (https://github.com/ximeiorg/xime) 的 plugin-core 模块（ASR 子集），
// 见仓库根 NOTICE.md。

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // 说明：catalog 里没有 kotlin-android 插件别名，且本仓库（AGP 9.x 内置 Kotlin 支持）
    // 所有模块都不显式应用 org.jetbrains.kotlin.android（见 :webdav），故此处同样不应用。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kingzcheung.xime.plugin.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 只保留 androidx.compose.runtime（IPluginEntryClass.Content() 的 @Composable 注解），
    // 模块内无任何 Compose UI，故不开启 Compose 编译。
    buildFeatures {
        compose = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.luaj.jse)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.core.ktx)
    // IPluginEntryClass.Content() / PluginCapabilities 等公共 API 暴露 @Composable 注解
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}
