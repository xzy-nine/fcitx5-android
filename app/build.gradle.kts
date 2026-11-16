import org.gradle.api.tasks.testing.Test

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    // custom: 构建期对齐 sherpa AAR 的 ONNX Runtime 符号版本（见文件头说明）
    id("org.fcitx.fcitx5.android.sherpa-ort-align")
    // custom: 构建期拉取谷歌数字墨水模型（内置进包，见 DigitalInkModelPlugin）
    id("org.fcitx.fcitx5.android.digitalink-model")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val sherpaAlignedDir = layout.buildDirectory.dir("generated/sherpa-ort-align")

// custom: fetchDigitalInkModel 的产物（内置数字墨水模型的 assets 源集根）
val digitalInkModelAssetsDir = layout.buildDirectory.dir("generated/digitalink-model/assets")

val packageBase = "org.fcitx.fcitx5.android"
val flavorFx = "fx"
val imeSettingsActivity = "$packageBase.ui.main.MainActivity"
val inputDiagnostics = providers.gradleProperty("inputDiagnostics").orNull == "true"

android {
    namespace = packageBase

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        buildConfigField("boolean", "INPUT_DIAGNOSTICS", inputDiagnostics.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // custom: 应用名与图标不再随构建类型变化，debug/release 统一使用 release 版本
        resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
        resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
        resValue("string", "app_name", "@string/app_name_release")
        manifestPlaceholders["appLabel"] = "@string/app_name"
        resValue("string", "ime_settings_activity", imeSettingsActivity)

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments("-DINPUT_DIAGNOSTICS=${if (inputDiagnostics) "ON" else "OFF"}")
                targets("direct-rime")
            }
        }
    }

    flavorDimensions += "brand"
    productFlavors {
        create(flavorFx) {
            dimension = "brand"
            buildConfigField("boolean", "IS_FX_BUILD", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
        compose = true
        // custom: 离线语音识别服务 AIDL（:asr 进程）
        aidl = true
        prefab = false
    }
    externalNativeBuild.cmake.path = file("../direct-rime/src/main/cpp/CMakeLists.txt")

    buildTypes {
        release {
            proguardFile("proguard-rules.pro")
        }
    }


    sourceSets.getByName("main").kotlin.srcDir("../direct-rime/src/engine/java")
    sourceSets.getByName("androidTest").assets.srcDir("../direct-rime/src/main/assets")

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    sourceSets {
        getByName("main") {
            // custom: 随包内置的谷歌数字墨水模型走**独立 assets 源集**（不进 `src/main/assets`，
            // 否则 generateDataDescriptor 会收录它、DataManager 会把模型再复制一份到 fcitx 数据目录）：
            //  - `src/main/mlkit-assets` = MDD 的「文件组已下载」状态（随源码，官方无处可下）
            //  - `digitalInkModelAssetsDir` = 构建期拉取的模型本体（fetchDigitalInkModel 产出）
            // 两者在 APK 内合并成同一棵 assets 树 `mlkit/digitalink/**`，只由 `MlKitBundledModel` 读取。
            assets.srcDir("src/main/mlkit-assets")
            assets.srcDir(digitalInkModelAssetsDir)
        }
    }

    packaging {
        jniLibs {
            // 仅剩 ai.onnxruntime 自带的那一份 runtime（sherpa 副本里已摘除）
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Let JVM unit tests exercise code that logs via android.util.Log: the stubbed
            // android.jar otherwise throws "Stub!" on every call. Framework layout
            // regressions use Robolectric; parser and validator tests use the stubs.
            isReturnDefaultValues = true
        }
    }
}

// custom: 把「构建期打过补丁」的 jniLibs 目录挂进主源集
//（由 sherpa-ort-align 插件产出：libsherpa-onnx-{jni,c-api}.so 的 ONNX Runtime
//  符号版本要求已对齐到 1.28.0；sherpa AAR 依赖被换成摘掉自带 runtime 的副本，
//  因此包内不会出现两份同名 .so）。
//
// 用确定路径 + 显式任务依赖：AGP 默认拒绝给 SourceSet 传 Provider
//（故 gradle.properties 里 `android.sourceset.disallowProvider=false`），
// 而该开关**不会自动带任务依赖**，所以下面手动挂。
afterEvaluate {
    android.sourceSets.getByName("main").jniLibs.srcDir(
        sherpaAlignedDir.map { it.dir("jniLibs") }
    )
    // AGP 消费该目录的任务是 `merge<Variant>JniLibFolders`（**不是** `*NativeLibs`），
    // 两者都覆盖，否则 Gradle 校验会报
    // "uses this output of task ':app:patchSherpaOrtVersion' without declaring an explicit dependency"。
    tasks.matching {
        it.name.startsWith("merge") &&
                (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs"))
    }.configureEach { dependsOn("patchSherpaOrtVersion") }

    // 内置数字墨水模型的 assets 是 fetchDigitalInkModel 的产物：合并 assets 前先拉取
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn("fetchDigitalInkModel") }
}

tasks.withType<Test>().configureEach {
    // Robolectric retains framework resource tables for each SDK sandbox.
    // Give the serial worker enough heap to load another Android resource
    // archive near the end of the full suite.
    maxHeapSize = "1536m"
    maxParallelForks = 1
    // custom: Robolectric 自带依赖解析器默认直连 Maven Central，其 android-all
    // 运行时按 SDK 逐个下载（每个上百 MB），国内网络经常超时。改走国内镜像。
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
    systemProperty("robolectric.dependency.repo.id", "aliyun-central")
}

run {
    val directAssets = layout.buildDirectory.dir("generated/direct-assets")
    val prepareDirectRimeAssets = tasks.register<Sync>("prepareDirectRimeAssets") {
        dependsOn(rootProject.tasks.named("prepareRimeSdk"))
        into(directAssets)
        // rime-schemas 只并入 usr/share/rime-data（见下），不随 assets 原样复制。
        from("src/main/assets") { exclude("usr/**", "descriptor.json", "rime-schemas/**") }
        into("usr/share/rime-data") {
            from(rootProject.file("native/rime-data"))
            from(rootProject.file("native/rime-prelude")) { include("key_bindings.yaml", "punctuation.yaml", "symbols.yaml") }
            // custom: 内置简体拼音方案（方案定义与词典）。
            // default.yaml 由 native/rime-data 提供（含引擎必需配置），此处只并入方案本身。
            from("src/main/assets/rime-schemas")
        }
        into("usr/share/opencc") { from(rootProject.file("native/rime-sdk/opencc/data")) }
    }
    android.sourceSets.getByName("main").assets.setSrcDirs(listOf(directAssets))
    tasks.named<DataDescriptorPlugin.DataDescriptorTask>("generateDataDescriptor") {
        dependsOn(prepareDirectRimeAssets)
        inputDir.set(directAssets)
        outputFile.set(directAssets.map { it.file("descriptor.json") })
    }
}

generateDataDescriptor {
    // rime-data ships its own copy of opencc data; link it to the shared one
    symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc")
    // 内置布局/主题/图标主题/符号面板数据只随 APK 分发、由 BundledPresets 在运行时解包到
    // 外部存储，不参与 dataDir 资源同步（避免占用 data 目录且保持清单驱动）。
    // 符号 catalog 由 SymbolCatalogs 直接从 assets 读取，不落盘到 data 目录。
    excludes.addAll(
        listOf(
            "clearurls", // Host-only catalog/licenses; read from APK, never copied to Rime data.
            "bundled",
            "bundled/keyboard_layouts",
            "bundled/keyboard_layouts/PopupPreset.json",
            "bundled/themes",
            "bundled/icon_themes",
            "bundled/symbols",
            "bundled/symbols/symbols.json",
            "bundled/symbols/emoji.json",
            "bundled/symbols/kaomoji.json",
            "rime-schemas" // 已并入 usr/share/rime-data，不再单独同步
        )
    )
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.compose.runtime)
    // custom: 离线语音引擎。用**构建期生成的「摘掉自带 libonnxruntime.so」副本**
    //（`sherpa-ort-align` 插件产出），避免它与 ai.onnxruntime 的 runtime 争夺同一个文件名。
    implementation(
        files(sherpaAlignedDir.map { it.file("sherpa-onnx-1.13.8-stripped.aar") })
    )
    ksp(project(":codegen"))
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
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.preference)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.recyclerview)
    // 2.12 requires API 24; keep this app's API 23 support.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.androidx.tracing.ktx)
    // 1.6.0+ snapshots child transitions before resuming them, avoiding mutation crashes.
    implementation(libs.androidx.transition)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    // Fragment 1.7+ is required for seekable predictive-back fragment animators.
    implementation(libs.androidx.fragment)
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
    // custom: 语音输入 —— 在线识别（okhttp/SSE）、模型归档解压（commons-compress）
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.commons.compress)
    // custom: ONNX Runtime —— 语音（sherpa-onnx）用：它的 AAR 副本已被摘掉自带的
    // libonnxruntime.so，native runtime 由本依赖提供（见上方 sherpa-ort-align 说明）
    implementation(libs.onnxruntime.android)
    // custom: 手写识别 —— Google ML Kit 数字墨水（推理在端上；语言模型由用户手动下载）
    implementation(libs.mlkit.digital.ink)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.xz)
    // custom: 纯 Java zstd 解码器，用于读取旧版 libime 二进制词库
    implementation(libs.unzstd)
    implementation("org.yaml:snakeyaml:2.7")
    implementation(libs.androidsvg)
    implementation(libs.quickjs.kt)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    // 跟随主 okhttp 版本，避免 mockwebserver 把 okhttp 拉回 4.x
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    // Robolectric 的 JVM 沙箱只加载 jar，okhttp 在 Android 变体里是 AAR（classes.jar 不被扫描），
    // 因此测试classpath 需要显式补 okhttp-jvm 才能拿到 okhttp3.internal.* 等实现类。
    testImplementation("com.squareup.okhttp3:okhttp-jvm:${libs.versions.okhttp.get()}")
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

// Android 变体的 QuickJS 只带 .so，桌面 JVM 单测加载不了它（见 JsVoiceBridgeContractTest）。
// 只在**单元测试**的配置上换成带桌面原生库的 jvm 变体：APK 仍然打包 android 变体，
// 生产依赖与打出来的包都不受影响。
configurations.matching {
    it.name.endsWith("UnitTestRuntimeClasspath") || it.name.endsWith("UnitTestCompileClasspath")
}.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:" + libs.versions.quickjs.kt.get()))
    }
}
