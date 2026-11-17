plugins {
    id("org.fcitx.fcitx5.android.rime-sdk")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    base
}

tasks.named<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// 文档门禁：经 pnpm 运行 scripts/verify-all.ts。
// 首次运行前需要 `pnpm install`；Windows 下 Gradle 调用 pnpm.cmd，其余平台调用 pnpm。
val verifyDocs =
    tasks.register<Exec>("verifyDocs") {
        group = "verification"
        description = "运行文档门禁（pnpm run verify-all）"
        workingDir = rootProject.layout.projectDirectory.asFile
        executable =
            if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
                "pnpm.cmd"
            } else {
                "pnpm"
            }
        args("run", "verify-all")
        inputs.files(
            fileTree("Docs"),
            fileTree(".agents"),
            fileTree("scripts"),
            "AGENTS.md",
            "README.md",
            "package.json",
            "pnpm-lock.yaml",
        )
        outputs.upToDateWhen { false }
    }

// `check` 聚合文档门禁与各模块验证。
tasks.named("check") {
    dependsOn(verifyDocs)
}