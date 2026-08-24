/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.os.Build
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.item
import splitties.resources.styledColor
import kotlin.math.min

abstract class ClipboardAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean
) : PagingDataAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(diffCallback) {

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem == newItem
            }
        }

        // Cache for analyzed chips by entry ID to avoid reprocessing on rebind
        private val chipsCache = mutableMapOf<Int, List<ClipboardTextAnalyzer.Entity>>()

        /**
         * excerpt text to show on ClipboardEntryUi, to reduce render time of very long text
         * @param str text to excerpt
         * @param mask mask text content with "•"
         * @param lines max output lines
         * @param chars max chars per output line
         */
        fun excerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String = buildString {
            val length = str.length
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1   // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
                    } else {
                        append(str.substring(start, excerptEnd))
                    }
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(end - start))
                    } else {
                        appendLine(str.substring(start, end))
                    }
                }
            }
        }
    }

    private var popupMenu: PopupMenu? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    class ViewHolder(val entryUi: ClipboardEntryUi) : RecyclerView.ViewHolder(entryUi.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, entryRadius))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position) ?: return
        // 整个 bind 过程兜底：任何异常都降级为纯文本，保证列表始终可渲染
        val display = excerptText(entry.text, entry.sensitive && maskSensitive)

        // Check cache first; if not present, use empty list initially and analyze off the main thread
        val cachedChips = chipsCache[entry.id]
        val chips = if (entry.sensitive && maskSensitive) {
            emptyList()
        } else if (cachedChips != null) {
            cachedChips
        } else {
            // Start background analysis and update when ready
            emptyList<ClipboardTextAnalyzer.Entity>().also {
                scope.launch {
                    val analyzed = withContext(Dispatchers.IO) {
                        runCatching { ClipboardTextAnalyzer.analyze(entry.text) }
                            .getOrDefault(emptyList())
                    }
                    chipsCache[entry.id] = analyzed
                    // Refresh the affected item if still visible
                    val currentEntry = getItem(position)
                    if (currentEntry?.id == entry.id) {
                        notifyItemChanged(position)
                    }
                }
            }
        }

        runCatching {
            with(holder.entryUi) {
                setEntry(display, entry.pinned, chips) { snippet ->
                    onPasteText(snippet)
                }
                root.setOnClickListener {
                    onPaste(entry)
                }
                root.setOnLongClickListener {
                    val popup = PopupMenu(ctx, root)
                    val menu = popup.menu
                    val iconTint = ctx.styledColor(android.R.attr.colorControlNormal)
                    if (entry.pinned) {
                        menu.item(R.string.unpin, R.drawable.ic_outline_push_pin_24, iconTint) {
                            onUnpin(entry.id)
                        }
                    } else {
                        menu.item(R.string.pin, R.drawable.ic_baseline_push_pin_24, iconTint) {
                            onPin(entry.id)
                        }
                    }
                    menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint) {
                        onEdit(entry.id)
                    }
                    menu.item(R.string.share, R.drawable.ic_baseline_share_24, iconTint) {
                        onShare(entry)
                    }
                    menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
                        onDelete(entry.id)
                    }
                    popupMenu = popup
                    popup.show()
                    true
                }
            }
        }.onFailure { e ->
            // 极端情况下连纯文本都失败，至少把文本塞进去，避免整页空白
            runCatching {
                holder.entryUi.textView.text = display
                holder.entryUi.root.setOnClickListener { onPaste(entry) }
            }
        }
    }

    fun getEntryAt(position: Int) = getItem(position)

    fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    abstract fun onPaste(entry: ClipboardEntry)

    /**
     * 点击条目中提取出的实体气泡（验证码/号码/姓名等）时回调，参数为气泡对应的文本片段。
     */
    abstract fun onPasteText(text: String)

    abstract fun onPin(id: Int)

    abstract fun onUnpin(id: Int)

    abstract fun onEdit(id: Int)

    abstract fun onShare(entry: ClipboardEntry)

    abstract fun onDelete(id: Int)

}