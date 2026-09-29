plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    // custom: 构建期对齐 sherpa AAR 的 ONNX Runtime 符号版本（见文件头说明）
    id("org.fcitx.fcitx5.android.sherpa-ort-align")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val sherpaAlignedDir = layout.buildDirectory.dir("generated/sherpa-ort-align")

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
                    "androidnotification"
                )
            }
        }
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

    packaging {
        jniLibs {
            // 仅剩 ai.onnxruntime 自带的那一份 runtime（sherpa 副本里已摘除）
            pickFirsts += "**/libonnxruntime.so"
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
    // custom: 离线语音引擎。用**构建期生成的「摘掉自带 libonnxruntime.so」副本**
    //（`sherpa-ort-align` 插件产出），避免它与 ai.onnxruntime 的 runtime 争夺同一个文件名。
    implementation(
        files(sherpaAlignedDir.map { it.file("sherpa-onnx-1.13.8-stripped.aar") })
    )
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
    // custom: 语音输入 —— 在线识别（okhttp/SSE）、模型索引（kaml）、归档解压（commons-compress）
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kaml)
    implementation(libs.commons.compress)
    // custom: 手写识别 —— 官方 ONNX Runtime Java 绑定（native runtime 同版本，见上方 packaging 说明）
    implementation(libs.onnxruntime.android)
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
