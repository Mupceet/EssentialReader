package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** 布局条目跨度（px）：[androidx.compose.foundation.lazy.LazyListItemInfo] 的极简投影。 */
data class EInkFlowItemSpan(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * 变高列表的下一页页首：当前页最后一条**完整可见**条目之后的条目下标；
 * null = 没有下一页（已到列表末尾或无可见条目）。
 *
 * 为什么不能像定高列表那样按项数翻页：书签/笔记卡片高度随内容行数变化，
 * 按固定项数翻会让页底裁掉半张卡片、或整条被跳过。改为按布局实测翻页后，
 * 每一页都从「上一条完整展示完」的位置接着走——不裁半截、不漏条目。
 *
 * [keepWithNext] = 该条目必须与下一条同页（章节头）：页底停在它之前，
 * 整组（章节头 + 卡片）留给下一页，避免页底孤零零一个章节标题。
 *
 * @param items 当前可见条目（含 offset/size，px，与 [viewportEndOffset] 同空间）
 * @param viewportEndOffset 视口下沿（px）
 */
fun nextFlowPageStart(
    items: List<EInkFlowItemSpan>,
    viewportEndOffset: Int,
    keepWithNext: (Int) -> Boolean = { false },
): Int? {
    if (items.isEmpty()) return null
    val lastFullyVisible = items.lastOrNull { it.offset + it.size <= viewportEndOffset }
    // 单条比视口还高（无完整可见条目）：只能整条独占一页，下一页从下一条开始
    var end = lastFullyVisible?.index ?: items.first().index
    // 页底是章节头 → 回退一条，让章节头和它的卡片同页（页底留白）
    while (keepWithNext(end)) {
        val previous = items.lastOrNull { it.index < end } ?: break
        if (previous.index < items.first().index) break
        end = previous.index
        if (end == items.first().index) break
    }
    return end + 1
}

/**
 * 变高列表分页状态（书签 Tab / 笔记 Tab）。
 *
 * 与 [EInkListPagerState]（定高列表：首次布局量出「一页几项」后等差翻页）
 * 的区别：卡片高度随内容变化，页首序列**不是**等差，而是每次翻页按布局
 * 实测决定——
 *  - 下一页：从当前页最后一条完整可见条目之后开始（[nextFlowPageStart]）；
 *  - 上一页：回到**记录过的**页首（前进时压栈，后退弹栈），往返位置确定；
 *  - 跳转（回到当前 / 去底部）：目标条对齐到页首并清空栈，重新起页序列。
 *
 * 配合 LazyColumn `userScrollEnabled = false` + [EInkPageSwipe] 使用。
 * 纯判定抽成 [nextFlowPageStart] 单测；滚动调度在无布局的测试 JVM 上
 * 安全跳过（同 [EInkListPagerState] 先例）。
 */
/** 上一页取向（变高列表）。 */
internal enum class FlowPageUpPlan {
    /** 有记录过的页首：弹回上一页页首（往返位置确定）。 */
    HISTORY,

    /** 无历史（刚跳转过：去底部 / 回到当前）：按视口回退一页并对齐页首。 */
    VIEWPORT,

    /** 已在列表开头：不动。 */
    NONE,
}

/**
 * 上一页取向判定（纯函数，单测锚定）：
 *
 * 有历史页首 → [FlowPageUpPlan.HISTORY]；否则**按视口回退**——跳转（去底部 /
 * 回到当前）会清空历史栈，此时若退一条目，用户要点很多次才退得动一页
 * （真机反馈"到底部/回当前后翻页要翻很多次"）。变高列表上方未组合、没有
 * 已测高度可用，视口回退 + 页首对齐是唯一稳定可行的"上一页"。
 */
internal fun planFlowPageUp(
    hasHistory: Boolean,
    pageStart: Int,
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
): FlowPageUpPlan = when {
    hasHistory -> FlowPageUpPlan.HISTORY
    pageStart <= 0 && firstVisibleIndex == 0 && firstVisibleScrollOffset == 0 ->
        FlowPageUpPlan.NONE

    else -> FlowPageUpPlan.VIEWPORT
}

@Stable
class EInkFlowPagerState(val listState: LazyListState) {

    /** 当前页首条目下标（每次翻页按布局实测推进，非等差）。 */
    var pageStart: Int by mutableIntStateOf(0)
        private set

    /** 访问过的页首栈（前进压栈、后退弹栈）。 */
    private var visited: List<Int> by mutableStateOf(emptyList())

    /** 是否可向前翻页：有历史页首，或当前页首不在列表开头。 */
    fun canPageUp(): Boolean = visited.isNotEmpty() || pageStart > 0

    /** 是否可向后翻页：按当前布局实测还有条目未完整展示。 */
    fun canPageDown(totalItems: Int, keepWithNext: (Int) -> Boolean = { false }): Boolean {
        val next = nextFlowPageStart(currentSpans(), viewportEnd(), keepWithNext) ?: return false
        return next < totalItems
    }

    /** 下一页：页首推进到当前页最后一条完整可见条目之后。 */
    suspend fun pageDown(totalItems: Int, keepWithNext: (Int) -> Boolean = { false }) {
        val next = nextFlowPageStart(currentSpans(), viewportEnd(), keepWithNext) ?: return
        if (next >= totalItems) return
        visited = visited + pageStart
        pageStart = next
        scrollToPageStart(next)
    }

    /**
     * 上一页：优先弹回记录过的页首；无历史（刚用去底部 / 回到当前跳转过）
     * 时按视口回退一页并对齐页首——退一条目会让用户点很多次才退一页。
     */
    suspend fun pageUp() {
        val previous = visited.lastOrNull()
        when (
            planFlowPageUp(
                hasHistory = previous != null,
                pageStart = pageStart,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        ) {
            FlowPageUpPlan.HISTORY -> {
                visited = visited.dropLast(1)
                pageStart = previous ?: 0
                scrollToPageStart(pageStart)
            }

            FlowPageUpPlan.VIEWPORT -> pageUpByViewport()
            FlowPageUpPlan.NONE -> Unit
        }
    }

    /**
     * 按视口回退一页：先 `scrollBy(-视口高)`，再把首个可见条目对齐到页首
     * （去掉顶部半截 offset），得到与向下翻页同粒度的一页。
     */
    private suspend fun pageUpByViewport() {
        val info = listState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (viewport <= 0) return
        try {
            listState.scrollBy(-viewport.toFloat())
        } catch (_: IndexOutOfBoundsException) {
            return
        }
        val first = listState.firstVisibleItemIndex
        pageStart = first
        scrollToPageStart(first)
    }

    /**
     * 跳转到指定条目（回到当前章节 / 去底部 / 快速拖动）：该条对齐页首，
     * 清空历史栈——跳转即新的页序列起点（往返语义从这一刻重新建立）。
     */
    suspend fun jumpToItem(index: Int, totalItems: Int = Int.MAX_VALUE) {
        val target = index.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        visited = emptyList()
        pageStart = target
        scrollToPageStart(target)
    }

    /** 数据整体切换（换书等）后回到第一页；不滚动（列表会自然回到首页）。 */
    fun resetPaging() {
        visited = emptyList()
        pageStart = 0
    }

    /** 数据原地变化后把实际位置拉回 [pageStart]（列表缩短时收敛到末条）。 */
    suspend fun realignToPageStart(totalItems: Int) {
        if (totalItems <= 0) {
            resetPaging()
            return
        }
        if (pageStart > totalItems - 1) pageStart = totalItems - 1
        scrollToPageStart(pageStart)
    }

    private fun currentSpans(): List<EInkFlowItemSpan> =
        listState.layoutInfo.visibleItemsInfo.map {
            EInkFlowItemSpan(index = it.index, offset = it.offset, size = it.size)
        }

    private fun viewportEnd(): Int = listState.layoutInfo.viewportEndOffset

    private suspend fun scrollToPageStart(index: Int) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            try {
                listState.scrollToItem(index)
            } catch (_: IndexOutOfBoundsException) {
                // 数据集切换竞态：忽略即可，空列表无需滚动
            }
        }
    }

    internal fun restorePaging(pageStart: Int, visited: List<Int>) {
        this.pageStart = pageStart
        this.visited = visited
    }

    internal fun savedVisited(): List<Int> = visited
}

/**
 * 创建并记住 [EInkFlowPagerState]（书签/笔记 Tab 的变高分页），
 * 分页状态随导航栈保存恢复（返回时停在离开时的页，不回第一页）。
 */
@Composable
fun rememberEInkFlowPagerState(vararg inputs: Any?): EInkFlowPagerState {
    val listState = rememberLazyListState()
    val saver = remember(listState) {
        Saver<EInkFlowPagerState, List<Any>>(
            save = { listOf(it.pageStart, it.savedVisited()) },
            restore = { values ->
                @Suppress("UNCHECKED_CAST")
                EInkFlowPagerState(listState).apply {
                    restorePaging(values[0] as Int, values[1] as List<Int>)
                }
            },
        )
    }
    val state = rememberSaveable(*inputs, saver = saver) { EInkFlowPagerState(listState) }
    return state
}
