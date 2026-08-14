package io.legado.app.eink.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * 无状态书架列表 Screen — 纯渲染。
 *
 * 由首页（home/HomeRoute）承载：顶部搜索框与底部操作栏在外层，
 * 本组件只负责书架列表内容。
 *
 * [listState] 由外层提升，供首页底部操作栏的翻页箭头驱动。
 *
 * 使用 LazyColumn（规范 §40 允许 LazyColumn，但禁止 animateItem）。
 * 列表项遵循规范 §41: title + secondary text + metadata + divider。
 */
@Composable
internal fun BookshelfScreen(
    state: BookshelfUiState,
    onBookClick: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
            state.isEmpty -> EmptyBookshelf(modifier = Modifier.fillMaxSize())
            else -> BookList(
                books = state.books,
                onBookClick = onBookClick,
                listState = listState
            )
        }
    }
}

@Composable
private fun BookList(
    books: List<Book>,
    onBookClick: (String) -> Unit,
    listState: LazyListState
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState
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
            style = eInkTypography().titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 作者
        Text(
            text = book.getRealAuthor(),
            style = eInkTypography().bodySmall,
            color = eInkColorScheme().onSurfaceVariant,
            maxLines = 1
        )
        // 阅读进度
        book.durChapterTitle?.let { title ->
            Text(
                text = title,
                style = eInkTypography().labelMedium,
                color = eInkColorScheme().onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyBookshelf(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("书架为空", style = eInkTypography().bodyLarge)
    }
}
