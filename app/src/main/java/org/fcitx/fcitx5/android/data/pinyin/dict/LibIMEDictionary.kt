/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

class LibIMEDictionary(file: File) : PinyinDictionary() {

    override var file: File = file
        private set

    var isEnabled: Boolean = true
        private set

    override val type: Type = Type.LibIME

    override val name: String
        get() = if (isEnabled) super.name
        else file.name.substringBefore(".${type.ext}.$DISABLE")

    init {
        ensureFileExists()
        isEnabled = when {
            file.extension == type.ext -> {
                true
            }
            file.name.endsWith(".${type.ext}.$DISABLE") -> {
                false
            }
            else -> errorArg(R.string.exception_libime_dict_filename, file.name)
        }
    }

    fun enable() {
        if (isEnabled)
            return
        val newFile = file.resolveSibling(name + ".${type.ext}")
        file.renameTo(newFile)
        file = newFile
        isEnabled = true
        AutoDictSync.notifyDictChanged()
    }

    fun disable() {
        if (!isEnabled)
            return
        val newFile = file.resolveSibling(name + ".${type.ext}.$DISABLE")
        file.renameTo(newFile)
        file = newFile
        isEnabled = false
        AutoDictSync.notifyDictChanged()
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        throw UnsupportedOperationException("libime binary dictionaries are not supported")
    }

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary {
        ensureBin(dest)
        file.copyTo(dest)
        return LibIMEDictionary(dest)
    }

    companion object {
        const val DISABLE = "disable"
    }
}