package io.legado.app.eink.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.EInkTopBar
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 搜索 Route。
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(title = "搜索", onBack = onBack)
        SearchScreen(
            state = uiState,
            onKeyChange = viewModel::updateKey,
            onSearch = viewModel::search,
            onClearHistory = viewModel::clearHistory,
            isInBookshelf = viewModel::isInBookshelf
        )
    }
}

/**
 * 无状态搜索 Screen。
 */
@Composable
internal fun SearchScreen(
    state: SearchUiState,
    onKeyChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
    isInBookshelf: (SearchBook) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchInputBar(
            searchKey = state.searchKey,
            isSearching = state.isSearching,
            onKeyChange = onKeyChange,
            onSearch = onSearch
        )
        EInkHorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.results.isNotEmpty() -> ResultList(
                    results = state.results,
                    isInBookshelf = isInBookshelf
                )
                state.showEmpty -> CenterMessage("无搜索结果")
                state.isSearching -> CenterMessage("正在搜索……")
                state.history.isNotEmpty() -> HistoryList(
                    history = state.history,
                    onClearHistory = onClearHistory,
                    onSearch = onSearch
                )
                else -> CenterMessage("输入书名开始搜索")
            }
        }
    }
}

@Composable
private fun SearchInputBar(
    searchKey: String,
    isSearching: Boolean,
    onKeyChange: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = searchKey,
            onValueChange = onKeyChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = EInkTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(searchKey) }),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(vertical = EInkSpacing.xs),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchKey.isEmpty()) {
                        EInkText(
                            text = "书名 / 作者",
                            fontSize = 16.sp,
                            color = EInkTheme.colorScheme.outline
                        )
                    }
                    innerTextField()
                }
            }
        )
        EInkText(
            text = if (isSearching) "搜索中" else "搜索",
            modifier = Modifier
                .clickable { onSearch(searchKey) }
                .padding(start = EInkSpacing.m),
            style = EInkTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ResultList(
    results: List<SearchBook>,
    isInBookshelf: (SearchBook) -> Boolean,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { "${it.origin}-${it.bookUrl}" }) { book ->
            ResultItem(book = book, inShelf = isInBookshelf(book))
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun ResultItem(book: SearchBook, inShelf: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)) {
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

@Composable
private fun HistoryList(
    history: List<io.legado.app.data.entities.SearchKeyword>,
    onClearHistory: () -> Unit,
    onSearch: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
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
        items(history, key = { it.word }) { keyword ->
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
