/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 构建期对齐 sherpa-onnx AAR 与 `ai.onnxruntime` 的 ONNX Runtime 版本。
 *
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** sherpa AAR 里需要改写版本要求的依赖方（**不含** runtime 自身）。 */
private val DEPENDENT_LIBS = listOf("libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so")

/** 需要从 AAR 摘掉的 runtime（改由 ai.onnxruntime 提供，避免两份同名 .so 竞争）。 */
private const val RUNTIME_LIB = "libonnxruntime.so"

abstract class PatchSherpaOrtVersionTask : DefaultTask() {

    @get:InputFile
    abstract val sherpaAar: RegularFileProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    val fromTag: String = "VERS_1.28.2"

    @get:Input
    val toTag: String = "VERS_1.28.0"

    /** 打过补丁的 jniLibs 根目录（内部再分 `<abi>/`）。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** 摘掉自带 runtime 的 AAR 副本（语音依赖改用它）。 */
    @get:OutputFile
    abstract val strippedAar: RegularFileProperty

    @TaskAction
    fun run() {
        val from = fromTag.toByteArray(Charsets.US_ASCII)
        val to = toTag.toByteArray(Charsets.US_ASCII)
        require(from.size == to.size) { "标签长度必须一致，否则原地替换会破坏文件偏移" }

        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()
        outRoot.mkdirs()

        val aarFile = sherpaAar.get().asFile
        require(aarFile.isFile) { "找不到 sherpa AAR：$aarFile" }

        val stripped = strippedAar.get().asFile
        stripped.parentFile?.mkdirs()

        val wantedAbis = abis.get().toSet()
        var patched = 0
        var extracted = 0

        ZipFile(aarFile).use { zip ->
            ZipOutputStream(stripped.outputStream().buffered()).use { zout ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || !name.startsWith("jni/") || !name.endsWith(".so")) {
                        zout.putNextEntry(ZipEntry(name))
                        zip.getInputStream(entry).use { it.copyTo(zout) }
                        zout.closeEntry()
                        continue
                    }

                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val fileName = name.substringAfterLast('/')

                    // 1) 摘掉自带 runtime
                    if (fileName == RUNTIME_LIB) {
                        logger.lifecycle("drop  $name（runtime 由 ai.onnxruntime 提供）")
                        continue
                    }

                    // 2) 改写依赖方的版本要求
                    var payload = bytes
                    if (fileName in DEPENDENT_LIBS) {
                        val hits = countOccurrences(payload, from)
                        require(hits <= 1) {
                            "$name 内 '$fromTag' 出现 $hits 次（期望 0 或 1），拒绝改写"
                        }
                        if (hits == 1) {
                            val index = indexOf(payload, from)
                            System.arraycopy(to, 0, payload, index, to.size)
                            require(payload.size == bytes.size) { "长度必须不变" }
                            patched++
                            logger.lifecycle("patch $name: $fromTag -> $toTag")
                        }
                    }

                    // 3) 按需落到生成的 jniLibs（供 APK 打包使用打过补丁的那份）
                    val abi = name.removePrefix("jni/").substringBefore('/')
                    if (abi in wantedAbis) {
                        val outFile = outRoot.resolve("$abi/$fileName")
                        outFile.parentFile?.mkdirs()
                        outFile.writeBytes(payload)
                        extracted++
                    }

                    zout.putNextEntry(ZipEntry(name))
                    zout.write(payload)
                    zout.closeEntry()
                }
            }
        }

        logger.lifecycle(
            "sherpa ORT 对齐完成：patch=$patched，jniLibs 导出 $extracted 个文件 -> $outRoot"
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        var count = 0
        var offset = 0
        while (offset <= haystack.size - needle.size) {
            val slice = haystack.copyOfRange(offset, haystack.size)
            val index = indexOf(slice, needle)
            if (index < 0) break
            count++
            offset += index + 1
        }
        return count
    }
}

/**
 * 注册构建期任务 `patchSherpaOrtVersion`。
 *
 */
class SherpaOrtAlignPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val generated = target.layout.buildDirectory.dir(SherpaOrtAlignPaths.GENERATED_DIR)

        target.tasks.register(
            "patchSherpaOrtVersion",
            PatchSherpaOrtVersionTask::class.java
        ) {
            group = "custom"
            description = "构建期对齐 sherpa-onnx AAR 的 ONNX Runtime ELF 符号版本，并摘掉其自带 runtime"
            sherpaAar.set(target.layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar"))
            abis.set(Versions.supportedABIs)
            outputDir.set(generated.map { dir -> dir.dir("jniLibs") })
            strippedAar.set(generated.map { dir -> dir.file(SherpaOrtAlignPaths.STRIPPED_AAR_NAME) })
        }
    }
}

/**
 * 产物路径常量：插件与 app 脚本共用，避免把 Provider 暴露出去。
 */
object SherpaOrtAlignPaths {
    /** 相对 app 模块 `buildDir` 的产物目录。 */
    const val GENERATED_DIR = "generated/sherpa-ort-align"

    /** 打过补丁的 jniLibs 子目录。 */
    const val JNI_LIBS_DIR = "jniLibs"

    /** 摘掉自带 runtime 的 AAR 副本文件名。 */
    const val STRIPPED_AAR_NAME = "sherpa-onnx-1.13.8-stripped.aar"
}
