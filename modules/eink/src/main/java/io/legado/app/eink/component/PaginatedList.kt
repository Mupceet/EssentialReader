package io.legado.app.eink.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import io.legado.app.eink.theme.EInkSpacing
import kotlinx.coroutines.launch

/**
 * Style options for page indicators shown beneath paginated content.
 *
 * @property Dots   Row of dots, one per page, with the current page highlighted.
 * @property Numbers A "第 X / Y 页" textual indicator.
 * @property None   No indicator at all.
 */
enum class PageIndicatorStyle {
    Dots,
    Numbers,
    None
}

/**
 * E-Ink optimized paginated list that replaces scrolling with discrete page
 * navigation. Uses [HorizontalPager] for swipe navigation between content pages
 * and optionally supports tap-to-turn-page (left half = previous, right half =
 * next) as a swipe alternative that avoids drag-style refreshes on E-Ink.
 *
 * @param items List of items to display
 * @param modifier Modifier for the component
 * @param itemsPerPage Number of items to display per page
 * @param pagerState State for controlling the pager
 * @param showPageIndicator Whether to show a page indicator at the bottom
 * @param indicatorStyle Style of page indicator
 * @param tapToTurnPage When true, taps on the left/right half of the pager
 *                      navigate to the previous/next page (no swipe needed)
 * @param pageContent Composable to render each page of items
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> PaginatedList(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemsPerPage: Int = 10,
    pagerState: PagerState = rememberEInkPagerState(items.size, itemsPerPage),
    showPageIndicator: Boolean = true,
    indicatorStyle: PageIndicatorStyle = PageIndicatorStyle.Numbers,
    tapToTurnPage: Boolean = false,
    pageContent: @Composable (items: List<T>, pageIndex: Int) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pager content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .then(if (tapToTurnPage) Modifier.tapToTurnPage(pagerState) else Modifier)
        ) { pageIndex ->
            val startIndex = pageIndex * itemsPerPage
            val endIndex = minOf(startIndex + itemsPerPage, items.size)
            val pageItems = if (startIndex < items.size) {
                items.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            pageContent(pageItems, pageIndex)
        }

        // Page indicator
        if (showPageIndicator && pagerState.pageCount > 1 && indicatorStyle != PageIndicatorStyle.None) {
            Spacer(modifier = Modifier.height(EinkPaginatedSpacing))

            when (indicatorStyle) {
                PageIndicatorStyle.Dots -> EInkDotPageIndicator(
                    currentPage = pagerState.currentPage,
                    pageCount = pagerState.pageCount
                )
                PageIndicatorStyle.Numbers -> EInkNumberPageIndicator(
                    currentPage = pagerState.currentPage + 1,
                    pageCount = pagerState.pageCount
                )
                PageIndicatorStyle.None -> Unit // handled by the guard above
            }
        }
    }
}

/**
 * Simple paginated list with default item rendering in a column.
 *
 * The [itemContent] lambda receives the item plus its *global* index across
 * the whole list (page index * items per page + row within page).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> SimplePaginatedList(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemsPerPage: Int = 10,
    pagerState: PagerState = rememberEInkPagerState(items.size, itemsPerPage),
    showPageIndicator: Boolean = true,
    indicatorStyle: PageIndicatorStyle = PageIndicatorStyle.Numbers,
    tapToTurnPage: Boolean = false,
    itemContent: @Composable (item: T, index: Int) -> Unit
) {
    PaginatedList(
        items = items,
        modifier = modifier,
        itemsPerPage = itemsPerPage,
        pagerState = pagerState,
        showPageIndicator = showPageIndicator,
        indicatorStyle = indicatorStyle,
        tapToTurnPage = tapToTurnPage
    ) { pageItems, pageIndex ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(EinkPaginatedPadding),
            verticalArrangement = Arrangement.spacedBy(EinkPaginatedItemSpacing)
        ) {
            pageItems.forEachIndexed { localIndex, item ->
                val globalIndex = pageIndex * itemsPerPage + localIndex
                itemContent(item, globalIndex)
            }
        }
    }
}

/**
 * Paginated grid layout for displaying items in a grid format.
 *
 * Bug fix: the original implementation computed each cell's global index via
 * `pageItems.indexOf(item)`, which returns the *first* matching element and
 * therefore produces wrong indices whenever the list contains duplicate items
 * (e.g. two equal strings). Index computation now uses the row/column offset
 * within the page (`rowIndex * columns + colIndex`), which is unique by
 * construction and independent of item equality.
 *
 * @param items List of items to display
 * @param columns Number of grid columns
 * @param modifier Modifier for the component
 * @param itemsPerPage Number of items per page (defaults to 5 rows of [columns])
 * @param pagerState State for controlling the pager
 * @param showPageIndicator Whether to show a page indicator at the bottom
 * @param indicatorStyle Style of page indicator
 * @param tapToTurnPage When true, taps on the left/right half of the pager
 *                      navigate to the previous/next page
 * @param itemContent Composable to render each item; receives the item and its
 *                    global index in the full list
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> PaginatedGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    itemsPerPage: Int = columns * 5, // Default to 5 rows per page
    pagerState: PagerState = rememberEInkPagerState(items.size, itemsPerPage),
    showPageIndicator: Boolean = true,
    indicatorStyle: PageIndicatorStyle = PageIndicatorStyle.Numbers,
    tapToTurnPage: Boolean = false,
    itemContent: @Composable (item: T, index: Int) -> Unit
) {
    PaginatedList(
        items = items,
        modifier = modifier,
        itemsPerPage = itemsPerPage,
        pagerState = pagerState,
        showPageIndicator = showPageIndicator,
        indicatorStyle = indicatorStyle,
        tapToTurnPage = tapToTurnPage
    ) { pageItems, pageIndex ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(EinkPaginatedPadding),
            verticalArrangement = Arrangement.spacedBy(EinkPaginatedItemSpacing)
        ) {
            // Chunk by columns so each chunk is one visual row.
            pageItems.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EinkPaginatedItemSpacing)
                ) {
                    rowItems.forEachIndexed { colIndex, item ->
                        // Offset-based index: unique regardless of item equality,
                        // fixing the indexOf bug for lists with duplicate items.
                        val globalIndex = pageIndex * itemsPerPage +
                            rowIndex * columns + colIndex
                        Box(modifier = Modifier.weight(1f)) {
                            itemContent(item, globalIndex)
                        }
                    }
                    // Fill remaining columns to keep the grid aligned on the last row.
                    if (rowItems.size < columns) {
                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared default [PagerState] factory.
 *
 * Extracted so [PaginatedList], [SimplePaginatedList] and [PaginatedGrid] share
 * one identical default-pager expression instead of three copy-pasted copies.
 * The page count is derived from the item count and items-per-page and is
 * recomposed when either changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberEInkPagerState(
    itemCount: Int,
    itemsPerPage: Int
): PagerState {
    val pageCount = remember(itemCount, itemsPerPage) {
        if (itemCount <= 0 || itemsPerPage <= 0) 1 else (itemCount + itemsPerPage - 1) / itemsPerPage
    }
    return rememberPagerState(pageCount = { pageCount })
}

/**
 * Tap-to-turn-page modifier: a single tap on the left half of the pager goes
 * to the previous page, a tap on the right half goes to the next page.
 *
 * Uses [detectTapGestures] (a discrete gesture) so there is no continuous
 * drag-induced refresh — friendlier to E-Ink than swipe when enabled.
 * Boundaries are clamped to the valid page range.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tapToTurnPage(pagerState: PagerState): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(pagerState) {
        detectTapGestures(
            onTap = { offset ->
                val width = size.width
                val isLeftHalf = offset.x < width / 2
                scope.launch {
                    val target = if (isLeftHalf) {
                        (pagerState.currentPage - 1).coerceAtLeast(0)
                    } else {
                        (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1)
                    }
                    if (target != pagerState.currentPage) {
                        pagerState.animateScrollToPage(target)
                    }
                }
            }
        )
    }
}

// Local spacing constants — keep the paginated components consistent with the
// rest of the kit (16dp page padding, 8dp item gap, 16dp indicator gap).
private val EinkPaginatedSpacing = EInkSpacing.m
private val EinkPaginatedPadding = EInkSpacing.m
private val EinkPaginatedItemSpacing = EInkSpacing.s
