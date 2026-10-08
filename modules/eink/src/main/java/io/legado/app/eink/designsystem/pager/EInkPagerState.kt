package io.legado.app.eink.designsystem.pager

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * E-Ink 固定页分页控制器的公共接口（列表 [EInkListPagerState] 与网格
 * [EInkGridPagerState] 两种实现）。
 *
 * 让承载层（首页等）在列表与网格布局间切换时，对两种分页状态无差别
 * 调用：翻页可用性判断、整页翻页、数据变化后的页首对齐。
 */
@Stable
interface EInkPageController {

    /** 当前页首项下标（等差序列：0, k, 2k, ...）。 */
    val pageStart: Int

    /** 一页完整展示的项数（首次布局实测，之后固定）。 */
    val pageItemCount: Int

    /** 是否可向前翻页（当前不在第一页）。 */
    fun canPageUp(): Boolean

    /** 是否可向后翻页（当前不在最后一页）。 */
    fun canPageDown(totalItems: Int): Boolean

    /** 上一页。 */
    suspend fun pageUp()

    /** 下一页；最后一页可能不满一页（到尾即止）。 */
    suspend fun pageDown(totalItems: Int)

    /**
     * 数据集变化后把实际滚动位置拉回当前页首（见各实现的详细说明）。
     */
    suspend fun realignToPageStart(totalItems: Int)
}

/**
 * E-Ink 固定页数分页状态。
 *
 * 列表首次布局时，实测"第一页完整展示的项数" [pageItemCount]，
 * 之后每次翻页固定移动该项数（[pageStart] ± [pageItemCount]，scrollToItem 直接跳转）。
 *
 * 分页状态经 [rememberEInkListPagerState] 随导航栈保存恢复：从其他界面
 * 返回时 [pageStart] 保持原值（不回第一页），且恢复后 [pageItemCount] > 0，
 * 自动跳过重新测量——恢复的非零滚动位置不会被测量流程归零。
 *
 * 由此保证：
 *  - 每一页从完整项边界开始，第一项永远完整展示；
 *  - 页内项全部完整展示，不存在底部裁剪半截的项；
 *  - 上下往返页位完全确定（等差页首序列），到底/回翻无歧义。
 *
 * 配合 LazyColumn `userScrollEnabled = false` + [EInkPageSwipe] 使用。
 */
@Stable
class EInkListPagerState(val listState: LazyListState) : EInkPageController {

    /** 一页完整展示的项数（首次布局实测，之后固定）。 */
    override var pageItemCount: Int by mutableIntStateOf(0)
        private set

    /** 当前页首项下标（等差序列：0, k, 2k, ...）。 */
    override var pageStart: Int by mutableIntStateOf(0)
        private set

    /**
     * 首次布局后测量一页的项数：从下标 0 起连续计数完整可见项。
     *
     * 等到首个「视口被填满」的布局才落定（存在底部被截断的项，即内容
     * 已超过一页）：流式追加的列表（换源页常驻首项、搜索页首批结果）
     * 首个非空布局可能只有个位数条目，视口未被填满时计数只是过渡值，
     * 落定过早会把整页翻页永久退化成逐条翻。列表不足一页时永不落定
     * （[pageItemCount] 保持 0、翻页保持禁用——单页列表本就无需翻页）。
     */
    internal suspend fun measureOnFirstLayout() {
        if (pageItemCount > 0) return
        val measured = CompletableDeferred<Unit>()
        coroutineScope {
            val job = launch {
                snapshotFlow { listState.layoutInfo }
                    .filter { it.visibleItemsInfo.isNotEmpty() }
                    .collect { info ->
                        // 滚动位置可能被 rememberSaveable 恢复到非首项（如从阅读页返回书架）：
                        // 此时首个可见项下标不为 0，逐项计数第一步就会中断，页大小被误测
                        // 为 1，整页翻页退化成逐项翻。新分页实例逻辑上从第一页开始，
                        // 先回到首项再测量。
                        if (listState.firstVisibleItemIndex != 0 ||
                            listState.firstVisibleItemScrollOffset != 0
                        ) {
                            scrollToPageStart(0)
                            return@collect
                        }
                        val viewportEnd = info.viewportEndOffset
                        var count = 0
                        for (item in info.visibleItemsInfo) {
                            if (item.index != count) break
                            if (item.offset + item.size > viewportEnd) break
                            count++
                        }
                        // 视口填满 = 首项起连续完整可见的项数少于可见项总数
                        //（末项被截断）；恰好整页放下的列表同短列表一样继续等，
                        // 翻页可用性两种落定值下一致（均为不可下翻）
                        if (count > 0 && count < info.visibleItemsInfo.size) {
                            pageItemCount = count
                            measured.complete(Unit)
                        }
                    }
            }
            measured.await()
            job.cancel()
        }
    }

    override fun canPageUp(): Boolean = pageStart > 0

    override fun canPageDown(totalItems: Int): Boolean =
        pageItemCount > 0 && pageStart + pageItemCount < totalItems

    /** 下一页；最后一页可能不足 [pageItemCount] 项（到尾即止）。 */
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
     * 从保存的状态恢复分页（导航返回，经 [rememberEInkListPagerState] 的
     * Saver 调用）。恢复后 [pageItemCount] > 0，[measureOnFirstLayout]
     * 自动跳过。
     */
    internal fun restorePaging(pageStart: Int, pageItemCount: Int) {
        this.pageStart = pageStart
        this.pageItemCount = pageItemCount
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
     * 列表尚未挂载或当前没有 item 时，[LazyListState.scrollToItem] 会抛
     * `IndexOutOfBoundsException`（如搜索刚开始/无结果返回空列表），因此先判断
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
 * 定位遮盖的前置等待：分页测量落定（内容超一页，[pageItemCount] > 0），
 * 或当前内容单页即可完整展示。
 *
 * [measureOnFirstLayout] 只在「末项被截断」（视口被填满）时落定——内容
 * 恰好装进一页的列表（短列表，或行高变化后原本微溢出的列表缩回一页）
 * 按设计永不落定；此时无需翻页也无页可跳（[jumpToItemAligned] 对
 * pageItemCount ≤ 0 直接 no-op），必须放行，否则以测量落定为揭盖条件的
 * 界面（目录页、字体设置页）遮盖常驻——不透明遮盖无指针处理，表现为
 * 「列表不可见但行可点击」。
 *
 * 调用方须保证等待期间 totalItems 已是终态（数据整批到位，非流式追加），
 * 避免过渡批次被误判为单页。
 */
internal suspend fun EInkListPagerState.awaitPositionReady() {
    snapshotFlow { listState.layoutInfo to pageItemCount }.first { (info, pages) ->
        pages > 0 || (info.visibleItemsInfo.isNotEmpty() && singlePageContent(info))
    }
}

/** 当前布局是否单页可容纳：首项起、末项即列表末尾且完整可见（含首项
 *  自身即超出视口的退化布局——异常字体度量撑爆行高等）。 */
private fun singlePageContent(info: LazyListLayoutInfo): Boolean {
    val visible = info.visibleItemsInfo
    val first = visible.firstOrNull() ?: return false
    if (first.index != 0) return false
    val last = visible.last()
    if (last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset) {
        return true
    }
    return visible.size == 1 && first.offset + first.size > info.viewportEndOffset
}

/**
 * 创建并记住 [EInkListPagerState]，内部在首次布局时自动测量页项数。
 *
 * 分页状态（[EInkListPagerState.pageStart]/[EInkListPagerState.pageItemCount]）
 * 经 [rememberSaveable] 随导航栈条目保存恢复，与 [rememberLazyListState]
 * 的滚动位置恢复保持一致：从其他界面返回时停在离开时的页，不回第一页。
 *
 * `inputs` 传几何键（如 orientation），变化后分页状态重建、页首回第一页。
 */
@Composable
fun rememberEInkListPagerState(vararg inputs: Any?): EInkListPagerState {
    val listState = rememberLazyListState()
    val saver = remember(listState) {
        Saver<EInkListPagerState, List<Any>>(
            save = { listOf(it.pageStart, it.pageItemCount) },
            restore = { values ->
                EInkListPagerState(listState).apply {
                    restorePaging(values[0] as Int, values[1] as Int)
                }
            }
        )
    }
    val state = rememberSaveable(*inputs, saver = saver) { EInkListPagerState(listState) }
    LaunchedEffect(state) { state.measureOnFirstLayout() }
    return state
}
