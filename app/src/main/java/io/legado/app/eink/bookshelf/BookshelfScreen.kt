package io.legado.app.eink.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.legado.app.eink.component.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.modifier.EInkPageSwipe
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.widget.EInkBookCover
import io.legado.app.eink.widget.EInkCoverHeight
import io.legado.app.eink.widget.EInkCoverWidth
import io.legado.app.eink.widget.EInkInfoRow

/**
 * 无状态书架列表 Screen — 纯渲染。
 *
 * 由首页（home/HomeRoute）承载：顶部搜索框与底部操作栏在外层，
 * 本组件只负责书架列表内容。
 *
 * [listState] 由外层提升，供首页底部操作栏的翻页箭头驱动。
 *
 * 列表不支持自由滚动（E-Ink 分页模式，参考微信读书墨水屏版）：
 *  - `userScrollEnabled = false` 禁用拖动/惯性滚动；
 *  - `overscrollEffect = null` 去除边缘回弹（拉伸/发光）效果；
 *  - 上下滑动手势经 [EInkPageSwipe] 识别为整页翻页，
 *    与底部操作栏 ▲▼ 按钮触发同一动作。
 *
 * 列表项遵循规范 §41: title + secondary text + metadata + divider。
 */
@Composable
internal fun BookshelfScreen(
    state: BookshelfUiState,
    onBookClick: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
            state.isEmpty -> EmptyBookshelf(modifier = Modifier.fillMaxSize())
            else -> BookList(
                books = state.books,
                onBookClick = onBookClick,
                listState = listState,
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
        }
    }
}

@Composable
private fun BookList(
    books: List<Book>,
    onBookClick: (String) -> Unit,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            ),
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null
    ) {
        items(books, key = { it.bookUrl }) { book ->
            BookListItem(book = book, onClick = { onBookClick(book.bookUrl) })
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun BookListItem(book: Book, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.screenHorizontal, vertical = EInkSpacing.s)
    ) {
        // 左侧封面；无封面/加载失败时 [EInkBookCover] 显示文字占位封面
        EInkBookCover(
            url = book.getDisplayCover(),
            name = book.name,
            author = book.getRealAuthor(),
            modifier = Modifier
                .width(EInkCoverWidth)
                .height(EInkCoverHeight)
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        Column(
            modifier = Modifier
                .weight(1f)
                // 与封面等高，四行信息间距平均分布
                .height(EInkCoverHeight)
                .padding(vertical = EInkSpacing.xxs),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 书名
            EInkText(
                text = book.name,
                style = EInkTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 作者（图标 + 文字，同 View 版 iv_author）
            EInkInfoRow(
                iconRes = R.drawable.ic_author,
                text = book.getRealAuthor(),
                style = EInkTheme.typography.bodySmall
            )
            // 当前进度章节（同 View 版 iv_read / ic_history）
            book.durChapterTitle?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.ic_history,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }
            // 最新章节（同 View 版 iv_last / ic_book_last）
            book.latestChapterTitle?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.ic_book_last,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }
        }
    }
}


@Composable
private fun EmptyBookshelf(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        EInkText("书架为空", style = EInkTheme.typography.bodyLarge)
    }
}
