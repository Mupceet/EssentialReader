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

    /**
     * 跳转到指定项所在页：页首对齐到完整页边界并同步 [pageStart]。
     *
     * 用于"回到当前 / 去到底部 / 快速拖动结束"等任意位置跳转，
     * 跳转后翻页序列仍保持完整页边界。
     */
    suspend fun jumpToItemAligned(index: Int) {
        if (pageItemCount <= 0) return
        val aligned = (index.coerceAtLeast(0) / pageItemCount) * pageItemCount
        pageStart = aligned
        scrollToPageStart(aligned)
    }

    /**
     * 重置分页计数到第一页（不滚动）。
     *
     * 发起新搜索等会先清空列表再填充新数据的场景使用：列表被清空后
     * [LazyListState] 会自然回到首页，无需（也不应）在数据切换期间调用
     * [scrollToItem]——此时滚动会与测量通道竞争，触发越界或
     * `layout state is not idle` 崩溃。
     */
    fun resetPaging() {
        pageStart = 0
    }

    /**
     * 数据集变化后把实际滚动位置拉回 [pageStart]。
     *
     * LazyColumn 按 key 锚定，列表原地重排（如按最后阅读时间排序更新）时，
     * 首可见项会跟随原 key 漂移，与 [pageStart] 脱钩：新页首被顶到可视区
     * 之上、翻页可用状态与实际位置不一致。每次数据更新后调用本方法，
     * 恢复"实际位置 = 页首"不变式；列表缩短时页首同步收敛到最后一个
     * 完整页起点。
     */
    suspend fun realignToPageStart(totalItems: Int) {
        if (pageItemCount <= 0) return
        if (totalItems <= 0) {
            pageStart = 0
            return
        }
        val maxStart = ((totalItems - 1) / pageItemCount) * pageItemCount
        if (pageStart > maxStart) {
            pageStart = maxStart
        }
        if (listState.layoutInfo.totalItemsCount > 0 &&
            (listState.firstVisibleItemIndex != pageStart ||
                    listState.firstVisibleItemScrollOffset != 0)
        ) {
            try {
                listState.scrollToItem(pageStart)
            } catch (_: IndexOutOfBoundsException) {
                // 数据集切换竞态：忽略即可
            }
        }
    }

    /**
     * 安全滚动到页首下标。
     *
     * 列表尚未挂载或当前没有 item 时，[LazyListState.scrollToItem] 会在 remeasure
     * 时抛 `IndexOutOfBoundsException`（如搜索刚开始/无结果返回空列表），因此先判断
     * 列表确有内容再滚动；同时存在竞态：读取 [LazyListState.layoutInfo] 之后、滚动
     * 真正生效之前，数据集可能已被切换为空（重复搜索时旧结果→清空），滚动同样会抛
     * 越界。此时列表为空本就无需滚动，新数据到达后列表自然从首页开始，故捕获忽略。
     */
    private suspend fun scrollToPageStart(index: Int) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            try {
                listState.scrollToItem(index)
            } catch (_: IndexOutOfBoundsException) {
                // 数据集切换竞态：忽略即可，空列表无需滚动。
            }
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
