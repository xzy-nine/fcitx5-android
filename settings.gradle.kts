pluginManagement {
    includeBuild("build-logic")
    repositories {
        // custom: 国内镜像优先，官方仓库兜底
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // custom: 国内镜像优先，官方仓库兜底。
        // Robolectric 的 android-all 运行时按 SDK 逐个拉取，官方源在国内经常超时。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":codegen")
include(":app")
include(":webdav")

// Deliberately a separate APK/process/data sandbox: librime has process-global state.
// This optional diagnostic app is not the full keyboard product.
if (providers.gradleProperty("directRimePrototype").orNull == "true") {
    include(":direct-rime")
}
