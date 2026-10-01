/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 构建期拉取「谷歌数字墨水」中文模型到 assets（随包内置，免运行时下载）。
 *
 * 模型不随 ML Kit SDK 分发。ML Kit digital-ink AAR 内的 `assets/manifest.json` 列出了每个
 * pack 的 zip 直链与 md5，`assets/packmapping.pb` 给出语言 tag → pack 的映射；本任务按
 * [DIGITAL_INK_ZH_HANI_PACKS]（`zh-Hani` 对应的两个 pack）下载、校验 md5、解压到
 * `<buildDir>/generated/digitalink-model/assets/mlkit/digitalink/...`，其目录结构就是
 * ML Kit/GMS MDD 在应用私有目录里的布局（另见 `MlKitBundledModel`）。
 *
 * 说明：MDD 的「文件组已下载」状态（`shared_prefs/gms_icing_mdd_*` 等）官方不提供下载，
 * 由仓库内 `app/src/main/mlkit-assets/` 随源码携带，与这里的产物合并成同一 assets 树。
 *
 * 离线构建可用 `-PdigitalInkModel=off` 跳过拉取（包内将不含内置模型：引擎链会回落到
 * 系统内置引擎，或由用户在设置页手动下载）。
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * `zh-Hani` 的 pack 定义：`pack 名 -> "zip 直链|md5|目标目录（相对 assets 根）"`。
 *
 * - 直链与 md5 取自 ML Kit digital-ink AAR 的 `assets/manifest.json`（`packs[].download_urls`
 *   与 `md5_checksum`）；升 ML Kit 版本后如报 md5 不匹配，照该文件更新即可；
 * - 目标目录名（`datadownloadfile_<timestamp>`）被仓库内 MDD 状态 XML 索引引用，
 *   **改名会让 MDD 找不到文件**，故与状态文件一起固定。
 */
private val DIGITAL_INK_ZH_HANI_PACKS = linkedMapOf(
    "lstm_chinese_tflite_4_192_tflite_20181109_zip" to
            "https://dl.google.com/handwriting/models/lstm.chinese.tflite_4_192.tflite.20181109.zip" +
            "|b4e30f63c66538e59a62a4fc4c2bbdff" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497352",
    "qrnn_zh_reco_20191217_fst_none_recospec_zip" to
            "https://dl.google.com/handwriting/models/qrnn.zh.reco_20191217.fst_none.recospec.zip" +
            "|2dec4a29db406f066460ffdf46ba62bc" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497651",
)

abstract class FetchDigitalInkModelTask : DefaultTask() {

    /** pack 名 → `"url|md5|目标目录"`。 */
    @get:Input
    abstract val packs: MapProperty<String, String>

    /** `-PdigitalInkModel=off`：跳过拉取（不产出内置模型）。 */
    @get:Input
    abstract val skip: Property<Boolean>

    /** assets 源集根（其下为 `mlkit/digitalink/...`）。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (skip.get()) {
            logger.lifecycle("跳过谷歌数字墨水模型拉取（-PdigitalInkModel=off）：包内不含内置模型")
            return
        }
        packs.get().forEach { (pack, spec) -> fetchPack(root, pack, spec) }
    }

    private fun fetchPack(assetsRoot: java.io.File, pack: String, spec: String) {
        val parts = spec.split('|')
        require(parts.size == 3) { "pack '$pack' 定义格式应为 url|md5|目标目录" }
        val (url, expectedMd5, targetDir) = parts
        val target = assetsRoot.resolve(DigitalInkModelPaths.ASSET_ROOT).resolve(targetDir)
        target.mkdirs()

        val zipBytes = download(url)
        val actualMd5 = md5(zipBytes)
        check(actualMd5.equals(expectedMd5, ignoreCase = true)) {
            "$pack 的 md5 不匹配：期望 $expectedMd5，实际 $actualMd5（$url）"
        }
        var written = 0
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name.isEmpty()) continue
                target.resolve(name).outputStream().buffered().use { zip.copyTo(it) }
                written++
            }
        }
        check(written > 0) { "$pack 解压后没有任何文件（$url）" }
        logger.lifecycle("拉取 $pack -> ${target.relativeTo(assetsRoot)}（$written 个文件）")
    }

    private fun download(url: String): ByteArray {
        val connection = try {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "fcitx5-android-build")
            }
        } catch (t: Throwable) {
            throw IllegalStateException(buildFailureHint(url), t)
        }
        return try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                buildFailureHint(url) + "（HTTP ${connection.responseCode}）"
            }
            connection.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            throw IllegalStateException(buildFailureHint(url), t)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildFailureHint(url: String): String =
        "无法拉取谷歌数字墨水模型：$url\n" +
                "  · 需要能访问 dl.google.com 的网络；若本次构建不需要内置模型，用 -PdigitalInkModel=off 跳过"

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * 注册构建期任务 `fetchDigitalInkModel`。
 *
 * 消费方（`app`）：
 * - `android.sourceSets.main.assets.srcDir(<buildDir>/generated/digitalink-model/assets)`
 * - `merge*Assets` 显式 `dependsOn("fetchDigitalInkModel")`
 */
class DigitalInkModelPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val generated = target.layout.buildDirectory.dir(DigitalInkModelPaths.GENERATED_DIR)
        target.tasks.register(
            "fetchDigitalInkModel",
            FetchDigitalInkModelTask::class.java
        ) {
            group = "custom"
            description = "构建期拉取谷歌数字墨水中文（zh-Hani）模型到 assets（md5 按 ML Kit 官方 manifest 校验）"
            packs.set(DIGITAL_INK_ZH_HANI_PACKS)
            skip.set(
                target.providers.gradleProperty("digitalInkModel")
                    .map { it.equals("off", ignoreCase = true) || it.equals("skip", ignoreCase = true) }
                    .orElse(false)
            )
            outputDir.set(generated.map { it.dir(DigitalInkModelPaths.ASSETS_SUBDIR) })
        }
    }
}

/**
 * 产物路径常量：插件与 app 脚本共用，避免把 Provider 暴露出去。
 */
object DigitalInkModelPaths {

    /** 相对 app 模块 `buildDir` 的产物目录。 */
    const val GENERATED_DIR = "generated/digitalink-model"

    /** 产物内作为 assets 源集根的子目录。 */
    const val ASSETS_SUBDIR = "assets"

    /** 内置模型在 assets 内的根路径（与 `MlKitBundledModel.ASSET_ROOT` 一致）。 */
    const val ASSET_ROOT = "mlkit/digitalink"
}
