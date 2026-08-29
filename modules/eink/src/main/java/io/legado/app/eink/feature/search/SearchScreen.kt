package io.legado.app.eink.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R

import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.control.EInkSearchInputBar
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.coverTargetSizePx
import io.legado.app.eink.feature.common.prefetchCovers
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import kotlinx.coroutines.launch

/**
 * 搜索 Route — ViewModel 感知层。
 *
 * 骨架（参考微信读书墨水屏版）：
 *  - 顶部搜索条与首页完全同款（位置/尺寸/样式不变），进入即聚焦拉起输入法；
 *  - 点击"搜索"才发起搜索（输入过程不触发）；搜索中按钮切换为"停止"；
 *  - 结果/历史列表为固定页分页（[rememberEInkListPagerState]），
 *    滑动手势与底部 ▲▼ 翻页行为一致；
 *  - 底部操作栏：左侧返回按钮，右侧上下翻页箭头。
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onBookClick: (SearchBookUiModel) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun triggerSearch(key: String) {
        if (key.isBlank()) return
        keyboard?.hide()
        viewModel.search(key)
        // 只重置分页计数；列表被清空后会自然回到首页，不要在数据切换期滚动
        //（scrollToItem 会与测量竞争，导致越界或 layout state is not idle 崩溃）。
        pager.resetPaging()
    }

    val isResultListVisible = uiState.results.isNotEmpty() || uiState.isSearching
    val totalItems = if (isResultListVisible) uiState.results.size else uiState.history.size
    val canPage = isResultListVisible || uiState.history.isNotEmpty()

    // 翻页动作 remember 稳定实例：下传后接收方（列表 / EInkPageSwipe）
    // 不因 lambda 逐次更换而被迫重组；翻页后上报 PageTurn 意图（规范 §26/§40）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, totalItems, refresh, scope) {
        {
            scope.launch { pager.pageDown(totalItems) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 结果列表封面预取：当前页落定后预热下一页（同首页说明，翻页时
    // EInkAsyncImage 同步命中内存缓存，零占位帧、单次绘制）
    val prefetchContext = LocalContext.current
    val prefetchDensity = LocalDensity.current
    LaunchedEffect(uiState.results) {
        val (coverWidthPx, coverHeightPx) =
            coverTargetSizePx(EInkCoverWidth, EInkCoverHeight, prefetchDensity)
        snapshotFlow { pager.pageStart to pager.pageItemCount }
            .collect { page ->
                val start = page.first
                val pageSize = page.second
                if (pageSize <= 0) return@collect
                prefetchCovers(
                    context = prefetchContext,
                    items = uiState.results.drop(start + pageSize).take(pageSize),
                    widthPx = coverWidthPx,
                    heightPx = coverHeightPx,
                    coverUrl = { it.coverUrl },
                    sourceOrigin = { it.origin },
                )
            }
    }

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个搜索页随每次翻页重组；
    // 收敛到槽内读取，翻页只重组箭头两个图标
    val pageArrows: @Composable () -> Unit = {
        EInkPageArrows(
            pageUpEnabled = canPage && pager.canPageUp(),
            pageDownEnabled = canPage && pager.canPageDown(totalItems),
            onPageUp = pageUp,
            onPageDown = pageDown
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        EInkSearchInputBar(
            value = uiState.searchKey,
            onValueChange = viewModel::updateKey,
            onImeAction = { triggerSearch(uiState.searchKey) },
            autoFocus = true,
            action = {
                EInkText(
                    text = if (uiState.isSearching) "停止" else "搜索",
                    style = EInkTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable {
                            if (uiState.isSearching) viewModel.stopSearch()
                            else triggerSearch(uiState.searchKey)
                        }
                        .padding(vertical = EInkSpacing.m)
                )
            }
        )
        EInkHorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            when {
                isResultListVisible -> ResultList(
                    state = uiState,
                    isInBookshelf = viewModel::isInBookshelf,
                    onBookClick = onBookClick,
                    pagerListState = pager.listState,
                    onPageUp = pageUp,
                    onPageDown = pageDown
                )
                uiState.showEmpty -> CenterMessage("无搜索结果")
                uiState.history.isNotEmpty() -> HistoryList(
                    state = uiState,
                    pagerListState = pager.listState,
                    onClearHistory = viewModel::clearHistory,
                    onSearch = { triggerSearch(it) },
                    onPageUp = pageUp,
                    onPageDown = pageDown
                )
                else -> CenterMessage("输入书名开始搜索")
            }
        }

        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_arrow_back),
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            pageArrows = pageArrows
        )
    }
}

/**
 * 无状态结果列表（固定页分页 + 手势整页翻页）。
 */
@Composable
private fun ResultList(
    state: SearchUiState,
    isInBookshelf: (SearchBookUiModel) -> Boolean,
    onBookClick: (SearchBookUiModel) -> Unit,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
) {
    LazyColumn(
        state = pagerListState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        items(state.results, key = { "${it.origin}-${it.bookUrl}" }) { book ->
            ResultItem(book = book, inShelf = isInBookshelf(book), onClick = { onBookClick(book) })
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun ResultItem(book: SearchBookUiModel, inShelf: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        // 封面（与首页书架一致；无封面时显示文字占位封面）
        EInkBookCover(
            url = book.coverUrl,
            name = book.name,
            author = book.author,
            sourceOrigin = book.origin,
            modifier = Modifier
                .width(EInkCoverWidth)
                .height(EInkCoverHeight)
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = EInkSpacing.xxs)
        ) {
            // 标题行：标题 + 右侧"已在书架"
            Row(verticalAlignment = Alignment.CenterVertically) {
                EInkText(
                    text = book.name,
                    modifier = Modifier.weight(1f),
                    style = EInkTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (inShelf) {
                    EInkText(
                        text = "已在书架",
                        style = EInkTheme.typography.labelMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = EInkSpacing.s)
                    )
                }
            }
            // 作者（移除书源信息，与首页一致）
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_author,
                text = book.author.ifBlank { "佚名" },
                style = EInkTheme.typography.bodySmall
            )
            // 最新章节（有则显示）
            book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.eink_ic_book_last,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }

            // 简介（参考 View 版 tv_introduce；无简介显示"暂无简介"）
            EInkText(
                text = book.intro,
                style = EInkTheme.typography.bodySmall,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = EInkSpacing.xxs)
            )
        }
    }
}

/**
 * 无状态历史列表（同样固定页分页 + 手势翻页）。
 */
@Composable
private fun HistoryList(
    state: SearchUiState,
    pagerListState: LazyListState,
    onClearHistory: () -> Unit,
    onSearch: (String) -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
) {
    LazyColumn(
        state = pagerListState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        item(key = "history_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.xs
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EInkText(
                    text = "搜索历史",
                    style = EInkTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                EInkText(
                    text = "清空",
                    style = EInkTheme.typography.labelMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onClearHistory)
                )
            }
        }
        items(state.history, key = { it.word }) { keyword ->
            EInkText(
                text = keyword.word,
                style = EInkTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearch(keyword.word) }
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
            )
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EInkText(text = message, style = EInkTheme.typography.bodyLarge)
    }
}
