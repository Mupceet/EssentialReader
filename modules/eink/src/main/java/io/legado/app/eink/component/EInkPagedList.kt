package io.legado.app.eink.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * E-Ink 固定页数分页状态。
 *
 * 列表首次布局时，实测"第一页完整展示的项数" [pageItemCount]，
 * 之后每次翻页固定移动该项数（[pageStart] ± [pageItemCount]，scrollToItem 直接跳转）。
 *
 * 由此保证：
 *  - 每一页从完整项边界开始，第一项永远完整展示；
 *  - 页内项全部完整展示，不存在底部裁剪半截的项；
 *  - 上下往返页位完全确定（等差页首序列），到底/回翻无歧义。
 *
 * 配合 LazyColumn `userScrollEnabled = false` + [EInkPageSwipe] 使用。
 */
@Stable
class EInkPagedListState(val listState: LazyListState) {

    /** 一页完整展示的项数（首次布局实测，之后固定）。 */
    var pageItemCount: Int by mutableIntStateOf(0)
        private set

    /** 当前页首项下标（等差序列：0, k, 2k, ...）。 */
    var pageStart: Int by mutableIntStateOf(0)
        private set

    /**
     * 首次布局后测量一页的项数：从下标 0 起连续计数完整可见项。
     */
    internal suspend fun measureOnFirstLayout() {
        if (pageItemCount > 0) return
        val info = snapshotFlow { listState.layoutInfo }
            .filter { it.visibleItemsInfo.isNotEmpty() }
            .first()
        val viewportEnd = info.viewportEndOffset
        var count = 0
        for (item in info.visibleItemsInfo) {
            if (item.index != count) break
            if (item.offset + item.size > viewportEnd) break
            count++
        }
        pageItemCount = count.coerceAtLeast(1)
    }

    fun canPageUp(): Boolean = pageStart > 0

    fun canPageDown(totalItems: Int): Boolean =
        pageItemCount > 0 && pageStart + pageItemCount < totalItems

    /** 下一页；最后一页可能不足 [pageItemCount] 项（到尾即止）。 */
    suspend fun pageDown(totalItems: Int) {
        if (!canPageDown(totalItems)) return
        pageStart = (pageStart + pageItemCount).coerceAtMost((totalItems - 1).coerceAtLeast(0))
        scrollToPageStart(pageStart)
    }

    suspend fun pageUp() {
        if (!canPageUp()) return
        pageStart -= pageItemCount
        scrollToPageStart(pageStart)
    }

    /** 重置到第一页（发起新搜索/切换数据集时调用）。 */
    suspend fun resetToFirstPage() {
        pageStart = 0
        scrollToPageStart(0)
    }

    /**
     * 安全滚动到页首下标：列表尚未挂载或当前没有 item 时，
     * [LazyListState.scrollToItem] 会在 remeasure 时抛
     * `IndexOutOfBoundsException`（如搜索刚开始/无结果返回空列表），
     * 此时仅更新页位，等待列表实际有内容后再自然落到首页。
     */
    private suspend fun scrollToPageStart(index: Int) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(index)
        }
    }
}

/**
 * 创建并记住 [EInkPagedListState]，内部在首次布局时自动测量页项数。
 */
@Composable
fun rememberEInkPagedListState(): EInkPagedListState {
    val listState = rememberLazyListState()
    val state = remember { EInkPagedListState(listState) }
    LaunchedEffect(state) { state.measureOnFirstLayout() }
    return state
}
