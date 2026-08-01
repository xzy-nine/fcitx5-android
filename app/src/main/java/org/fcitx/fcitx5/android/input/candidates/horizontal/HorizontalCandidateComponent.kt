/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.annotation.Keep
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val swipePref = AppPrefs.getInstance().keyboard.horizontalCandidateSwipe
    private val swipeEnabled by swipePref
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private var lastExpandedOffset = -1
    private var loadingMore = false
    private var noMoreData = false
    private var candidateGeneration = 0
    private var lastCandidateData = FcitxEvent.CandidateListEvent.Data()

    @Keep
    private val swipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        view.post {
            if (view.isLaidOut) {
                lastExpandedOffset = -1
                loadingMore = false
                view.layoutManager = createLayoutManager()
                if (lastCandidateData.candidates.isNotEmpty()) {
                    applyCandidates(lastCandidateData)
                } else {
                    view.requestLayout()
                }
            }
        }
    }

    init {
        swipePref.registerOnChangeListener(swipeChangeListener)
    }

    private val loadMoreBatch by lazy {
        maxSpanCountPref.getValue().coerceAtLeast(LOAD_MORE_BATCH_MIN)
    }

    private fun refreshExpanded() {
        val firstVisible = if (swipeEnabled) firstVisiblePosition().coerceAtLeast(0) else -1
        val offset = if (swipeEnabled) firstVisible else view.childCount
        if (offset == lastExpandedOffset) return
        lastExpandedOffset = offset
        _expandedCandidateOffset.tryEmit(offset)
        val done = if (swipeEnabled) adapter.total == adapter.itemCount
        else adapter.total == view.childCount
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to done
        )
    }

    private fun loadMoreIfNeeded() {
        if (loadingMore || noMoreData) return
        if (adapter.total >= 0 && adapter.itemCount >= adapter.total) return
        val nearEnd = lastVisiblePosition() >=
            adapter.itemCount - LOAD_MORE_THRESHOLD
        // keep loading until content overflows the viewport, so that the bar is scrollable
        if (nearEnd || !(view.canScrollHorizontally(1) || view.canScrollHorizontally(-1))) {
            loadMore()
        }
    }

    private fun loadMore() {
        if (loadingMore || noMoreData) return
        if (adapter.total >= 0 && adapter.itemCount >= adapter.total) return
        loadingMore = true
        val generation = candidateGeneration
        fcitx.launchOnReady {
            val more = it.getCandidates(adapter.itemCount, loadMoreBatch)
            // launchOnReady runs on fcitx's Default dispatcher, post UI updates to main thread
            view.post {
                if (generation == candidateGeneration) {
                    if (more.isNotEmpty()) {
                        adapter.appendCandidates(more)
                    } else {
                        noMoreData = true
                    }
                    loadingMore = false
                }
            }
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!swipeEnabled) return
            refreshExpanded()
            loadMoreIfNeeded()
        }
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                if (swipeEnabled) {
                    holder.itemView.updateLayoutParams<RecyclerView.LayoutParams> {
                        width = layoutMinWidth
                    }
                } else {
                    holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                        minWidth = layoutMinWidth
                        flexGrow = layoutFlexGrow
                    }
                }
                holder.itemView.setOnClickListener {
                    fcitx.launchOnReady { it.select(holder.idx) }
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    private fun firstVisiblePosition(): Int {
        val lm = view.layoutManager!!
        return when (lm) {
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
            is FlexboxLayoutManager -> lm.findFirstVisibleItemPosition()
            else -> 0
        }
    }

    private fun lastVisiblePosition(): Int {
        val lm = view.layoutManager!!
        return when (lm) {
            is LinearLayoutManager -> lm.findLastVisibleItemPosition()
            is FlexboxLayoutManager -> lm.findLastVisibleItemPosition()
            else -> 0
        }
    }

    val layoutManager: RecyclerView.LayoutManager
        get() = checkNotNull(view.layoutManager)

    private fun createLayoutManager(): RecyclerView.LayoutManager {
        if (swipeEnabled) {
            // LinearLayoutManager for reliable horizontal scrolling: FlexboxLayoutManager's
            // sub-orientation scroll is broken when the RecyclerView is narrower than its
            // parent view (it clamps the scroll with the parent's width instead of the
            // content's), which makes the bar snap back instead of scrolling
            return object : LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
                override fun canScrollVertically() = false
                override fun canScrollHorizontally() = true
                override fun onLayoutCompleted(state: RecyclerView.State) {
                    super.onLayoutCompleted(state)
                    refreshExpanded()
                    loadMoreIfNeeded()
                }
            }
        }
        return object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = false
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    // [^2] RecyclerView can't display all candidates
                    // update LayoutParams in onLayoutCompleted would trigger another
                    // onLayoutCompleted, skip the second one to avoid infinite loop
                    if (cnt < adapter.candidates.size) {
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded()
            }
            // no need to override `generate{,Default}LayoutParams`, because HorizontalCandidateViewAdapter
            // guarantees ViewHolder's layoutParams to be `FlexboxLayoutManager.LayoutParams`
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (swipeEnabled || fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = createLayoutManager()
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
            addOnScrollListener(scrollListener)
        }
    }

    private fun applyCandidates(
        data: FcitxEvent.CandidateListEvent.Data,
        prevData: FcitxEvent.CandidateListEvent.Data = FcitxEvent.CandidateListEvent.Data()
    ) {
        val candidates = data.candidates
        val total = data.total
        val maxSpanCount = maxSpanCountPref.getValue()
        if (swipeEnabled) {
            // use a reasonable min width (1/maxSpanCount of the bar width) so that
            // candidates are neither too dense (natural width) nor stretched, and the
            // bar overflows for the given span count, aligning with the expanded grid
            if (view.width > 0) {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
            }
            layoutFlexGrow = 0f
            secondLayoutPassNeeded = false
        } else {
            when (fillStyle) {
                NeverFillWidth -> {
                    layoutMinWidth = 0
                    layoutFlexGrow = 0f
                    secondLayoutPassNeeded = false
                }
                AutoFillWidth -> {
                    layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                    layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                    // [^1] total candidates count < maxSpanCount
                    secondLayoutPassNeeded = candidates.size < maxSpanCount
                    secondLayoutPassDone = false
                }
                AlwaysFillWidth -> {
                    layoutMinWidth = 0
                    layoutFlexGrow = 1f
                    secondLayoutPassNeeded = false
                }
            }
        }
        adapter.updateCandidates(candidates, total)
        loadingMore = false
        noMoreData = false
        candidateGeneration++
        lastExpandedOffset = -1
        // in swipe mode, keep the scroll position when the list only got refreshed
        // (the previous candidates are still a prefix of the new ones, e.g. after the
        // engine re-sends the same list with an updated total), and reset it otherwise
        val keepScroll = swipeEnabled &&
            prevData.candidates.isNotEmpty() &&
            candidates.take(prevData.candidates.size) == prevData.candidates
        if (!keepScroll) {
            layoutManager.scrollToPosition(0)
        }
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded()
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (data == lastCandidateData) {
            // ignore duplicate updates: getCandidates may trigger an engine UI refresh
            // which re-sends the same list; applying it would reset the scroll position
            // and cause an endless load-more loop in swipe mode
            return
        }
        val prevData = lastCandidateData
        lastCandidateData = data
        applyCandidates(data, prevData)
    }

    companion object {
        private const val LOAD_MORE_BATCH_MIN = 16
        private const val LOAD_MORE_THRESHOLD = 3
    }
}
