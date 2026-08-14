package io.legado.app.eink.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        scope.launch { pager.resetToFirstPage() }
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
            ResultItem(book = book, inShelf = isInBookshelf(book))
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun ResultItem(book: SearchBook, inShelf: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EInkText(
                text = book.name,
                modifier = Modifier.weight(1f),
                style = EInkTheme.typography.bodyMedium,
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
        EInkText(
            text = buildString {
                append(book.author.ifBlank { "佚名" })
                book.originName.ifBlank { "" }.takeIf { it.isNotEmpty() }?.let {
                    append(" · ")
                    append(it)
                }
            },
            style = EInkTheme.typography.bodySmall,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
            EInkText(
                text = it,
                style = EInkTheme.typography.bodySmall,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
