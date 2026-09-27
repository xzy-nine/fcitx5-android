plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// custom: 离线 ASR 的 ONNX Runtime（MIT）
//
// 与 Xime 同版本同来源：从 Maven Central 解析官方 AAR
//   com.microsoft.onnxruntime:onnxruntime-android:1.28.0
// 并抽出 jni/<abi>/libonnxruntime.so。C++ 头文件已随仓库收录于
// app/src/main/cpp/asr/onnxruntime/include/（AAR 不提供头文件）。
// ---------------------------------------------------------------------------
val onnxRuntimeVersion = "1.28.0"
val onnxRuntimeJniDir = layout.buildDirectory.dir("onnxruntime/aar/jni").get().asFile
val onnxRuntimeLibDir = onnxRuntimeJniDir

val onnxRuntimeAar = configurations.create("onnxRuntimeAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(onnxRuntimeAar.name, "com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")

val extractOnnxRuntime = tasks.register<Sync>("extractOnnxRuntime") {
    description = "Extract libonnxruntime.so from the official ONNX Runtime Android AAR"
    group = "custom"
    into(layout.buildDirectory.dir("onnxruntime/aar"))
    inputs.files(onnxRuntimeAar).withPropertyName("onnxRuntimeAar")
    from({ onnxRuntimeAar.files.map { zipTree(it) } }) {
        include("jni/**/libonnxruntime.so")
    }
}

// ---------------------------------------------------------------------------
// custom: 把仓库根 `plugins/` 下的 Lua 在线 ASR 插件打包进 assets/plugins/<id>/
// （与 Xime 同布局：源码在仓库根 plugins/，运行时由 PluginManager 从 assets 安装）
// ---------------------------------------------------------------------------
val luaPluginsAssetsDir = layout.buildDirectory.dir("luaPluginsAssets").get().asFile
val copyLuaPluginsToAssets = tasks.register<Sync>("copyLuaPluginsToAssets") {
    description = "Copy bundled Lua ASR plugins into assets/plugins"
    group = "custom"
    from(rootProject.file("plugins")) { into("plugins") }
    into(luaPluginsAssetsDir)
}

// CMake configure 与打包都必须在抽取完成之后
tasks.configureEach {
    if (name != extractOnnxRuntime.name &&
        (name.startsWith("configureCMake") ||
                name.startsWith("buildCMake") ||
                name.startsWith("externalNativeBuild") ||
                (name.startsWith("merge") && name.endsWith("JniLibFolders")) ||
                name == "preBuild")
    ) {
        dependsOn(extractOnnxRuntime)
    }
    if (name.startsWith("merge") && name.endsWith("Assets") ||
        name == "preBuild" || name == "generateDebugLintReportModel"
    ) {
        dependsOn(copyLuaPluginsToAssets)
    }
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // custom: 应用名与图标不再随构建类型变化，debug/release 统一使用 release 版本
        resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
        resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
        resValue("string", "app_name", "@string/app_name_release")

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification",
                    // custom: 离线流式 zipformer2 ASR（移植自 Xime）
                    "asr_jni"
                )
                // custom: ASR 原生库需要 libonnxruntime.so 的位置（由 extractOnnxRuntime 抽取）
                arguments("-DONNXRUNTIME_LIB_DIR=${onnxRuntimeLibDir.absolutePath}")
            }
        }
    }

    // custom: libonnxruntime.so 由 CMake 的 IMPORTED 目标链接，但 AGP 不会自动打包
    // IMPORTED 库，必须额外挂进 jniLibs（这也是 Xime 需要放两份 .so 的原因）
    sourceSets.getByName("main") {
        jniLibs.directories.add(onnxRuntimeJniDir.absolutePath)
        // Lua 在线 ASR 插件（仓库根 plugins/ → assets/plugins/）
        assets.directories.add(luaPluginsAssetsDir.absolutePath)
    }

    buildFeatures {
        viewBinding = true
        resValues = true
        compose = true
        // custom: 离线语音识别服务 AIDL（:asr 进程）
        aidl = true
    }

    buildTypes {
        release {
            proguardFile("proguard-rules.pro")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.compose.runtime)
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(project(":webdav"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.miuix.core.android)
    implementation(libs.miuix.ui.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.nav.android)
    implementation(libs.miuix.blur.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.jieba.analysis)
    // custom: 语音输入（Xime 核心移植）—— 在线 ASR 插件、模型索引/下载、简繁转换
    implementation(project(":plugin-core"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kaml)
    implementation(libs.commons.compress)
    implementation(libs.opencc4j)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
