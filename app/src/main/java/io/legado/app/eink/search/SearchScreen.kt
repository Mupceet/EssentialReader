package io.legado.app.eink.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * 搜索 Route。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        SearchScreen(
            state = uiState,
            onKeyChange = viewModel::updateKey,
            onSearch = viewModel::search,
            onClearHistory = viewModel::clearHistory,
            isInBookshelf = viewModel::isInBookshelf,
            contentPadding = innerPadding
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
    contentPadding: PaddingValues = PaddingValues(),
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
                    isInBookshelf = isInBookshelf,
                    contentPadding = contentPadding
                )
                state.showEmpty -> CenterMessage("无搜索结果")
                state.isSearching -> CenterMessage("正在搜索……")
                state.history.isNotEmpty() -> HistoryList(
                    history = state.history,
                    onClearHistory = onClearHistory,
                    onSearch = onSearch,
                    contentPadding = contentPadding
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
            textStyle = TextStyle(fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(searchKey) }),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(vertical = EInkSpacing.xs),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchKey.isEmpty()) {
                        Text(
                            text = "书名 / 作者",
                            style = TextStyle(fontSize = 16.sp, color = eInkColorScheme().outline)
                        )
                    }
                    innerTextField()
                }
            }
        )
        Text(
            text = if (isSearching) "搜索中" else "搜索",
            modifier = Modifier
                .clickable { onSearch(searchKey) }
                .padding(start = EInkSpacing.m),
            style = eInkTypography().labelLarge
        )
    }
}

@Composable
private fun ResultList(
    results: List<SearchBook>,
    isInBookshelf: (SearchBook) -> Boolean,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
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
            Text(
                text = book.name,
                modifier = Modifier.weight(1f),
                style = eInkTypography().bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (inShelf) {
                Text(
                    text = "已在书架",
                    style = eInkTypography().labelMedium,
                    color = eInkColorScheme().onSurfaceVariant,
                    modifier = Modifier.padding(start = EInkSpacing.s)
                )
            }
        }
        Text(
            text = buildString {
                append(book.author.ifBlank { "佚名" })
                book.originName.ifBlank { "" }.takeIf { it.isNotEmpty() }?.let {
                    append(" · ")
                    append(it)
                }
            },
            style = eInkTypography().bodySmall,
            color = eInkColorScheme().onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = eInkTypography().bodySmall,
                color = eInkColorScheme().onSurfaceVariant,
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
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = EInkSpacing.m,
                    end = EInkSpacing.m,
                    top = EInkSpacing.xs
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索历史",
                    style = eInkTypography().labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "清空",
                    style = eInkTypography().labelMedium,
                    color = eInkColorScheme().onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onClearHistory)
                )
            }
        }
        items(history, key = { it.word }) { keyword ->
            Text(
                text = keyword.word,
                style = eInkTypography().bodyMedium,
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
        Text(text = message, style = eInkTypography().bodyLarge)
    }
}
