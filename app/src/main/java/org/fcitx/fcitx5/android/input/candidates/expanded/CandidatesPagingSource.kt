/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import kotlin.math.max

/**
 * 展开候选面板的分页数据源。
 *
 * 数据来自横向候选栏持有的同一份候选列表：两处展示的是同一组候选，
 * 只是排布方式不同（横向滑动 / 网格滚动）。因此这里不再单独向引擎取数，
 * 而是按需向共享列表请求，避免展开后从头再取一遍。
 */
class CandidatesPagingSource(
    private val total: Int,
    /** 读取共享候选列表的快照。 */
    private val snapshot: () -> List<CandidateWord>,
    /** 请求加载更多候选；返回是否已取到新内容。 */
    private val requestMore: suspend (offset: Int, limit: Int) -> Int,
) : PagingSource<Int, CandidateWord>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CandidateWord> {
        val startIndex = params.key ?: 0
        val pageSize = params.loadSize

        // 共享列表不足以覆盖本次请求时，先让候选栏补足。
        var candidates = snapshot()
        if (candidates.size < startIndex + pageSize) {
            requestMore(candidates.size, pageSize)
            candidates = snapshot()
        }

        val slice = candidates.drop(startIndex).take(pageSize)
        val prevKey = if (startIndex > 0) max(0, startIndex - pageSize) else null
        val nextKey = if (total > 0) {
            if (startIndex + slice.size >= total) null else startIndex + slice.size
        } else {
            if (slice.size < pageSize) null else startIndex + slice.size
        }
        return LoadResult.Page(slice, prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, CandidateWord>) = null
}
