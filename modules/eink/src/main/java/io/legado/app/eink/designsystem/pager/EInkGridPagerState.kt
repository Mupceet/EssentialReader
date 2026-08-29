package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * E-Ink 固定页数分页状态（网格版，配套 LazyVerticalGrid）。
 *
 * 与列表版 [EInkListPagerState] 同一套整页跳转模型，差别仅在"一页"的
 * 计量单位：以完整行 × 列数为页项数（[pageItemCount]），页首下标始终为
 * 行首（[pageItemCount] 的等差序列，因网格项按行填充、行首下标为列数的
 * 倍数，而页项数是列数的整数倍）。
 *
 * 首次布局实测"第一页完整展示的行数与列数"，之后每次翻页固定移动整页。
 * 分页状态经 [rememberEInkGridPagerState] 随导航栈保存恢复。
 *
 * 列数由 `GridCells.Adaptive` 按屏宽解析，页项数在首次布局后固定：
 * 旋转屏幕等改变列数的事件不会重新测量（与列表版对旋转后行高变化的
 * 处理一致，E-Ink 设备基本不旋转）。
 *
 * 配合 LazyVerticalGrid `userScrollEnabled = false` + [EInkPageSwipe] 使用。
 */
@Stable
class EInkGridPagerState(val gridState: LazyGridState) : EInkPageController {

    /** 一页完整展示的项数（完整行数 × 列数，首次布局实测后固定）。 */
    override var pageItemCount: Int by mutableIntStateOf(0)
        private set

    /** 当前页首项下标（等差序列：0, k, 2k, ...，k 为 [pageItemCount]）。 */
    override var pageStart: Int by mutableIntStateOf(0)
        private set

    /**
     * 首次布局后测量一页的项数：统计第一页完整展示的行数与首行列数。
     */
    internal suspend fun measureOnFirstLayout() {
        if (pageItemCount > 0) return
        val layoutFlow = snapshotFlow { gridState.layoutInfo }
            .filter { it.visibleItemsInfo.isNotEmpty() }
        var info = layoutFlow.first()
        // 滚动位置可能被 rememberSaveable 恢复到非首行：先回第一行再测量，
        // 否则首行不完整会被漏计（同列表版的恢复归零处理）
        if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
            scrollToPageStart(0)
            info = layoutFlow.first { it.visibleItemsInfo.first().index == 0 }
        }
        // 同一行项共享 offset.y：按 y 聚合行边界（top -> bottom，行内取最大）
        val rowBounds = LinkedHashMap<Int, Int>()
        for (item in info.visibleItemsInfo) {
            val top = item.offset.y
            val bottom = top + item.size.height
            rowBounds[top] = maxOf(rowBounds[top] ?: bottom, bottom)
        }
        val firstRowTop = rowBounds.keys.min()
        val columns = info.visibleItemsInfo.count { it.offset.y == firstRowTop }
        // 行按 top 升序统计完整行（行 bottom 不越过视口底），遇首个不完整行即止
        val completeRows = rowBounds.entries
            .sortedBy { it.key }
            .takeWhile { it.value <= info.viewportEndOffset }
            .count()
        pageItemCount = (completeRows * columns).coerceAtLeast(1)
    }

    override fun canPageUp(): Boolean = pageStart > 0

    override fun canPageDown(totalItems: Int): Boolean =
        pageItemCount > 0 && pageStart + pageItemCount < totalItems

    /** 下一页；最后一页可能不满整行（到尾即止）。 */
    override suspend fun pageDown(totalItems: Int) {
        if (!canPageDown(totalItems)) return
        pageStart = (pageStart + pageItemCount).coerceAtMost((totalItems - 1).coerceAtLeast(0))
        scrollToPageStart(pageStart)
    }

    override suspend fun pageUp() {
        if (!canPageUp()) return
        pageStart -= pageItemCount
        scrollToPageStart(pageStart)
    }

    /**
     * 从保存的状态恢复分页（导航返回，经 [rememberEInkGridPagerState]
     * 的 Saver 调用）。恢复后 [pageItemCount] > 0，[measureOnFirstLayout]
     * 自动跳过。
     */
    internal fun restorePaging(pageStart: Int, pageItemCount: Int) {
        this.pageStart = pageStart
        this.pageItemCount = pageItemCount
    }

    /**
     * 数据集变化后把实际滚动位置拉回 [pageStart]（语义同列表版
     * [EInkListPagerState.realignToPageStart]）：网格重排后首可见项与
     * 页首脱钩时恢复"实际位置 = 页首"；数据缩短时页首收敛到最后一个
     * 完整页起点。
     */
    override suspend fun realignToPageStart(totalItems: Int) {
        if (pageItemCount <= 0) return
        if (totalItems <= 0) {
            pageStart = 0
            return
        }
        val maxStart = ((totalItems - 1) / pageItemCount) * pageItemCount
        if (pageStart > maxStart) {
            pageStart = maxStart
        }
        if (gridState.layoutInfo.totalItemsCount > 0 &&
            (gridState.firstVisibleItemIndex != pageStart ||
                    gridState.firstVisibleItemScrollOffset != 0)
        ) {
            try {
                gridState.scrollToItem(pageStart)
            } catch (_: IndexOutOfBoundsException) {
                // 数据集切换竞态：忽略即可
            }
        }
    }

    /**
     * 安全滚动到页首下标（同列表版：列表空/未挂载或数据切换竞态时跳过）。
     */
    private suspend fun scrollToPageStart(index: Int) {
        if (gridState.layoutInfo.totalItemsCount > 0) {
            try {
                gridState.scrollToItem(index)
            } catch (_: IndexOutOfBoundsException) {
                // 数据集切换竞态：忽略即可，空网格无需滚动。
            }
        }
    }
}

/**
 * 创建并记住 [EInkGridPagerState]，内部在首次布局时自动测量页项数。
 *
 * 分页状态经 [rememberSaveable] 随导航栈条目保存恢复，与
 * [rememberLazyGridState] 的滚动位置恢复保持一致。
 */
@Composable
fun rememberEInkGridPagerState(): EInkGridPagerState {
    val gridState = rememberLazyGridState()
    val saver = remember(gridState) {
        Saver<EInkGridPagerState, List<Any>>(
            save = { listOf(it.pageStart, it.pageItemCount) },
            restore = { values ->
                EInkGridPagerState(gridState).apply {
                    restorePaging(values[0] as Int, values[1] as Int)
                }
            }
        )
    }
    val state = rememberSaveable(saver = saver) { EInkGridPagerState(gridState) }
    LaunchedEffect(state) { state.measureOnFirstLayout() }
    return state
}
