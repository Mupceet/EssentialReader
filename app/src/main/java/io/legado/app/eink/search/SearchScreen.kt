package io.legado.app.eink.search

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.component.EInkBackButton
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkOperationBar
import io.legado.app.eink.modifier.EInkPageSwipe
import io.legado.app.eink.component.EInkSearchInputBar
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.rememberEInkPagedListState
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.widget.EInkBookCover
import io.legado.app.eink.widget.EInkCoverHeight
import io.legado.app.eink.widget.EInkCoverWidth
import io.legado.app.eink.widget.EInkInfoRow
import kotlinx.coroutines.launch

/**
 * 搜索 Route — ViewModel 感知层。
 *
 * 骨架（参考微信读书墨水屏版）：
 *  - 顶部搜索条与首页完全同款（位置/尺寸/样式不变），进入即聚焦拉起输入法；
 *  - 点击"搜索"才发起搜索（输入过程不触发）；搜索中按钮切换为"停止"；
 *  - 结果/历史列表为固定页分页（[rememberEInkPagedListState]），
 *    滑动手势与底部 ▲▼ 翻页行为一致；
 *  - 底部操作栏：左侧返回按钮，右侧上下翻页箭头。
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pager = rememberEInkPagedListState()
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

    val pageUp: () -> Unit = { scope.launch { pager.pageUp() } }
    val pageDown: () -> Unit = { scope.launch { pager.pageDown(totalItems) } }

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
            navigationIcon = { EInkBackButton(onClick = onBack) },
            pageUpEnabled = canPage && pager.canPageUp(),
            pageDownEnabled = canPage && pager.canPageDown(totalItems),
            onPageUp = pageUp,
            onPageDown = pageDown
        )
    }
}

/**
 * 无状态结果列表（固定页分页 + 手势整页翻页）。
 */
@Composable
private fun ResultList(
    state: SearchUiState,
    isInBookshelf: (SearchBook) -> Boolean,
    onBookClick: (SearchBook) -> Unit,
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
private fun ResultItem(book: SearchBook, inShelf: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s)
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
                iconRes = R.drawable.ic_author,
                text = book.author.ifBlank { "佚名" },
                style = EInkTheme.typography.bodySmall
            )
            // 最新章节（有则显示）
            book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.ic_book_last,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }

            // 简介（参考 View 版 tv_introduce；无简介显示"暂无简介"）
            EInkText(
                text = book.trimIntro(LocalContext.current),
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
                    .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s)
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
