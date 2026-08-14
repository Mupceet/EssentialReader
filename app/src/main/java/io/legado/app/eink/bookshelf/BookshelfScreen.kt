package io.legado.app.eink.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.Book
import androidx.compose.foundation.clickable
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.theme.eInkTypography
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.theme.EInkSpacing

/**
 * 书架 Route — ViewModel 感知层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfRoute(
    onBookClick: (String) -> Unit,
    onSearch: () -> Unit,
    onBookSource: () -> Unit,
    onSettings: () -> Unit,
    viewModel: BookshelfViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    Text(
                        text = "搜索",
                        modifier = Modifier
                            .clickable(onClick = onSearch)
                            .padding(horizontal = EInkSpacing.s),
                        style = eInkTypography().labelLarge
                    )
                    Text(
                        text = "书源",
                        modifier = Modifier
                            .clickable(onClick = onBookSource)
                            .padding(horizontal = EInkSpacing.s),
                        style = eInkTypography().labelLarge
                    )
                    Text(
                        text = "设置",
                        modifier = Modifier
                            .clickable(onClick = onSettings)
                            .padding(horizontal = EInkSpacing.s),
                        style = eInkTypography().labelLarge
                    )
                }
            )
        }
    ) { innerPadding ->
        BookshelfScreen(
            state = uiState,
            onBookClick = onBookClick,
            contentPadding = innerPadding
        )
    }
}

/**
 * 无状态书架 Screen — 纯渲染。
 *
 * 使用 LazyColumn（规范 §40 允许 LazyColumn，但禁止 animateItem）。
 * 列表项遵循规范 §41: title + secondary text + metadata + divider。
 */
@Composable
internal fun BookshelfScreen(
    state: BookshelfUiState,
    onBookClick: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
            state.isEmpty -> EmptyBookshelf(modifier = Modifier.fillMaxSize())
            else -> BookList(
                books = state.books,
                onBookClick = onBookClick,
                contentPadding = contentPadding
            )
        }
    }
}

@Composable
private fun BookList(
    books: List<Book>,
    onBookClick: (String) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        items(books, key = { it.bookUrl }) { book ->
            BookListItem(book = book, onClick = { onBookClick(book.bookUrl) })
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun BookListItem(book: Book, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        // 书名
        Text(
            text = book.name,
            style = io.legado.app.eink.theme.eInkTypography().titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 作者
        Text(
            text = book.getRealAuthor(),
            style = io.legado.app.eink.theme.eInkTypography().bodySmall,
            color = io.legado.app.eink.theme.eInkColorScheme().onSurfaceVariant,
            maxLines = 1
        )
        // 阅读进度
        book.durChapterTitle?.let { title ->
            Text(
                text = title,
                style = io.legado.app.eink.theme.eInkTypography().labelMedium,
                color = io.legado.app.eink.theme.eInkColorScheme().onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyBookshelf(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("书架为空", style = io.legado.app.eink.theme.eInkTypography().bodyLarge)
    }
}
