/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.sync.webdav

import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * WebDAV 连接与同步配置。存储于应用私有 filesDir，读取/写入均为同步阻塞，调用方在 IO 线程使用。
 */
@Serializable
data class WebDavSyncConfig(
    /** WebDAV 服务器根地址（默认坚果云 WebDAV） */
    val serverUrl: String = DEFAULT_SERVER_URL,
    val username: String = "",
    val password: String = "",
    /** 设备名，用于偏好备份文件名 */
    val deviceName: String = DEFAULT_DEVICE_NAME,
    /** 词库自动同步开关（事件+常驻驱动，仅进程存活时生效） */
    val dictAutoSync: Boolean = false,
    /** 最近一次词库上传指纹（内容未变则跳过上传） */
    var dictUploadFingerprint: String = "",
    /** 最近一次下载到的远端词库文件 mtime（未变则跳过下载） */
    var dictRemoteMtime: Long = 0L,
    /** 最近一次成功上传的偏好 zip 文件名与内容摘要（同日同名同内容则跳过） */
    var lastPrefsUploadName: String = "",
    var lastPrefsUploadDigest: String = "",
    /** 最近一次同步信息（用于界面展示） */
    var lastSyncDescription: String = ""
) {
    companion object {
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
        const val CLOUD_DIR_NAME = "fcitx5xzy"
        const val DICT_FILE_NAME = "fcitx5-dict.zip"
        const val PREFS_FILE_PREFIX = "fcitx5-settings_"
        const val CONFIG_FILE_NAME = "webdav_sync_config.json"
        private const val DEFAULT_DEVICE_NAME = "android"

        private val json = Json { prettyPrint = true }

        private val configFile: File by lazy {
            File(appContext.filesDir, CONFIG_FILE_NAME)
        }

        /** 配置文件写入锁：并发 save 时串行化，避免共用临时文件互相踩踏。 */
        private val fileLock = Any()

        fun load(): WebDavSyncConfig {
            val config = runCatching {
                if (!configFile.exists()) return WebDavSyncConfig()
                json.decodeFromString<WebDavSyncConfig>(configFile.readText())
            }.getOrElse {
                WebDavSyncConfig()
            }
            // 兼容历史占位设备名：置空/占位值时重新自动获取真实设备名
            val device = config.deviceName.trim()
            return if (device.isEmpty() ||
                device.equals(DEFAULT_DEVICE_NAME, ignoreCase = true) ||
                device.equals("Android", ignoreCase = true)
            ) {
                config.copy(deviceName = defaultDeviceName())
            } else {
                config
            }
        }

        /**
         * 获取本机设备名（无需蓝牙/额外权限），参考 Notify-Relay DeviceUtils：
         * 优先读系统存储的用户设备名（部分 ROM 放 Settings.Secure("bluetooth_name")，
         * 另一些放 Settings.Global("device_name")），都取不到再回退 Build.MODEL / Build.DEVICE。
         */
        fun defaultDeviceName(): String {
            val resolver = appContext.contentResolver
            runCatching {
                Settings.Secure.getString(resolver, "bluetooth_name")
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            runCatching {
                Settings.Global.getString(resolver, "device_name")
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val model = Build.MODEL?.trim().orEmpty()
            if (model.isNotBlank()) {
                val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
                if (manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true)) {
                    return "$manufacturer $model"
                }
                return model
            }
            return Build.DEVICE?.trim().orEmpty().ifBlank { DEFAULT_DEVICE_NAME }
        }
    }

    /**
     * 原子写入：先把完整 JSON 写到正式文件同目录的临时文件，再整体替换正式文件。
     * 写入或替换失败时保留旧配置文件，并返回 failure 供调用方判断。
     */
    fun save(): Result<Unit> = synchronized(fileLock) {
        runCatching {
            val dir = configFile.parentFile
                ?: throw IOException("WebDAV config file has no parent directory")
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Cannot create WebDAV config directory: ${dir.absolutePath}")
            }
            val tmp = File(dir, "${configFile.name}.tmp")
            try {
                tmp.writeText(json.encodeToString(WebDavSyncConfig.serializer(), this))
                replaceAtomically(tmp, configFile)
            } finally {
                // 替换失败时清理临时文件；成功时它已被移走
                tmp.delete()
            }
        }
    }

    private fun replaceAtomically(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(), dst.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            if (!src.renameTo(dst)) {
                throw IOException("Cannot replace WebDAV config: ${dst.absolutePath}")
            }
        }
    }
}
