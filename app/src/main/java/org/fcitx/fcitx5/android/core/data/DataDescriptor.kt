/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import kotlinx.serialization.Serializable

typealias SHA256 = String

/**
 * A list of files with sha256
 */
@Serializable
data class DataDescriptor(
    /**
     * Implementation dependent, will be used to quick check if two descriptors are the same
     */
    val sha256: SHA256,
    /**
     * path -> sha256
     * sha256 will be empty if the path is a directory
     */
    val files: Map<String, SHA256>,
    /**
     * Symbolic links from target -> source
     */
    val symlinks: Map<String, String> = mapOf()
) {
    /**
     * 分隔符统一为 `/`。
     *
     * 清单里的路径是资源相对路径，与构建主机无关；历史清单在 Windows 上生成时会带反斜杠，
     * 与新建清单比较会全部判为「不同」，进而把每一条旧路径都排成删除动作。
     * 消费方（AssetMigration）只接受 `/` 分隔，故解析后立即统一。
     */
    fun withNormalizedPaths(): DataDescriptor = DataDescriptor(
        sha256,
        files.mapKeys { (path, _) -> path.replace('\\', '/') },
        symlinks.mapKeys { (path, _) -> path.replace('\\', '/') }
            .mapValues { (_, source) -> source.replace('\\', '/') }
    )
}