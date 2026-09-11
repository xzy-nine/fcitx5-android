/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.broadcast.BroadcastSecurityManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.entriesPager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.invalidatePagingSource
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        clbDao = clbDb.clipboardDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    fun observeAllEntries() = clbDao.observeAllEntries()

    /**
     * 分页加载剪贴板条目（供 Compose 的 `collectAsLazyPagingItems` 消费）。
     *
     * 复用 [ClipboardDao.allEntries] 的 [PagingSource]：Room 自带表变更检测，
     * 条目增删改（含置顶导致的排序变化）会自动触发对应页失效并重查。
     * 经 [cachedIn] 在本对象作用域内共享缓存，避免每次重组重建分页流。
     *
     * 该 Flow 只构造一次并对外复用（单例），调用方不要重复构造——每次都新建
     * `Pager` + `cachedIn` 会在本对象作用域内累积无法释放的缓存流。需要立即
     * 重查时请改用 [invalidatePagingSource]。
     */
    val entriesPager: Flow<PagingData<ClipboardEntry>> by lazy {
        @Suppress("OPT_IN_USAGE")
        Pager(
            config = PagingConfig(
                pageSize = CLIPBOARD_PAGE_SIZE,
                // 预取一页，滚动到底前提前加载，减少可见的空白等待
                prefetchDistance = CLIPBOARD_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                clbDao.allEntries().also { currentPagingSource = it }
            },
        ).flow.cachedIn(this)
    }

    /**
     * 当前活跃的 [PagingSource]（由 [entriesPager] 的工厂创建，随刷新而更新）。
     *
     * 仅用于显式失效：软删除/撤销/物理清理会在同一帧内连续改动数据，Room 的
     * 变更检测不保证及时重排，主动 `invalidate()` 可让分页立即从第一页重查。
     * 工厂在分页协程中写入、[invalidatePagingSource] 可能从主线程读取，故用 `@Volatile`。
     */
    @Volatile
    private var currentPagingSource: PagingSource<Int, ClipboardEntry>? = null

    /**
     * 主动失效当前分页源，触发已订阅的列表重查。
     *
     * 与重建 Flow 不同，`invalidate()` 复用同一 `cachedIn` 缓存，
     * 不会在作用域内留下泄漏的缓存流。
     */
    fun invalidatePagingSource() {
        currentPagingSource?.invalidate()
    }

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        clbDao.markAsDeleted(*ids)
        updateItemCount()
        return ids
    }

    suspend fun undoDelete(vararg ids: Int) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun realDelete() {
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        val timestamp = clip.description.timestamp
        if (timestamp == lastClipTimestamp) return
        lastClipTimestamp = timestamp
        // Skip clips marked as sensitive via EXTRA_IS_SENSITIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = clip.description.extras
            if (extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                return
            }
        }
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                if (entry.text.isBlank() || entry.sensitive) return@withLock
                try {
                    clbDao.find(entry.text, entry.sensitive)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        clbDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                    if (BroadcastSecurityManager.isEnabled()) {
                        BroadcastSecurityManager.broadcastClipboardEntry(insertedEntry.text)
                    }
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(entry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            // the last one we will keep
            val last = unpinned
                .sortedBy { it.id }
                .getOrNull(unpinned.size - limit)
            // delete all unpinned before that, or delete all when limit <= 0
            clbDao.markUnpinnedAsDeletedEarlierThan(last?.timestamp ?: System.currentTimeMillis())
        }
    }

    /** 剪贴板列表分页大小，沿用旧 View 实现的取值 */
    private const val CLIPBOARD_PAGE_SIZE = 16

}