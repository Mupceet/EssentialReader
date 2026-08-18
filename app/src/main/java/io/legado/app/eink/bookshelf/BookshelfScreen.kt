package io.legado.app.eink.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
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
 * 列表项遵循规范 §41: title + secondary text + metadata + divider：
 * 点击进阅读，长按进详情（对齐 View 版书架交互）。
 */
@Composable
internal fun BookshelfScreen(
    state: BookshelfUiState,
    onBookClick: (String) -> Unit,
    onBookLongClick: (Book) -> Unit,
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
                updatingBookUrls = state.updatingBookUrls,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
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
    updatingBookUrls: Set<String>,
    onBookClick: (String) -> Unit,
    onBookLongClick: (Book) -> Unit,
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
        // 不用 key：按 key 锚定时列表原地重排（最后阅读排序置顶）会让视口
        // 跟随原首可见项漂移、再被分页对齐拉回，整个列表抖动；按下标锚定
        // 视口不动，仅内容变化的项重绘。列表项无跨重排保留的内部状态，
        // 无需 key
        items(books) { book ->
            BookListItem(
                book = book,
                isUpdating = updatingBookUrls.contains(book.bookUrl),
                onClick = { onBookClick(book.bookUrl) },
                onLongClick = { onBookLongClick(book) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: Book,
    isUpdating: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val unreadCount = book.getUnreadChapterNum()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s)
    ) {
        EInkBookCover(
            url = book.getDisplayCover(),
            name = book.name,
            author = book.getRealAuthor(),
            sourceOrigin = book.origin,
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
            // 书名行：标题占满剩余宽度，角标只与标题同一行
            Row(verticalAlignment = Alignment.CenterVertically) {
                EInkText(
                    text = book.name,
                    style = EInkTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when {
                    // 刷新中：角标静态替换为省略号（E-Ink 禁止加载动画）
                    isUpdating -> ShelfBadge(
                        text = "…",
                        highlight = false,
                        modifier = Modifier.padding(start = EInkSpacing.xs)
                    )
                    // 未读章节数（View 版 getUnreadChapterNum；本次刷新发现新章时高亮）
                    unreadCount > 0 -> ShelfBadge(
                        text = unreadCount.toString(),
                        highlight = book.lastCheckCount > 0,
                        modifier = Modifier.padding(start = EInkSpacing.xs)
                    )
                }
            }
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


/**
 * 书架角标：位于书籍标题同一行右侧，不覆盖封面与下方信息行。
 *
 * E-Ink 约束：静态绘制、零动画零阴影——刷新中不做转圈/闪烁，仅把角标
 * 静态替换为省略号，完成后一次性替换为数字或消失。高亮（本次刷新发现
 * 新章，lastCheckCount > 0）为反色实心（黑底白字），普通为描边
 * （白底黑字），两种状态均单帧绘制。
 */
@Composable
private fun ShelfBadge(
    text: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = modifier
            .border(1.dp, colors.onSurface)
            .background(if (highlight) colors.onSurface else colors.background)
            .padding(horizontal = EInkSpacing.s, vertical = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.labelSmall,
            color = if (highlight) colors.background else colors.onSurface
        )
    }
}


@Composable
private fun EmptyBookshelf(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        EInkText("书架为空", style = EInkTheme.typography.bodyLarge)
    }
}
