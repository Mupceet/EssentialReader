package io.legado.app.eink.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import io.legado.app.eink.component.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.modifier.EInkPageSwipe
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.widget.EInkBookCover
import io.legado.app.eink.widget.EInkCoverHeight
import io.legado.app.eink.widget.EInkCoverWidth
import io.legado.app.eink.widget.EInkInfoRow

/**
 * 网格最小格宽门槛（GridCells.Adaptive 的 minSize）。
 *
 * Adaptive 的列数为整除向下取整：minSize 是"扣完成边距与列距后的门槛值"，
 * 不是视觉格宽——实际格宽由列数反推。取 96dp 的依据（左右边距 16dp、
 * 列距 16dp）：
 *  - 360dp 手机屏恰好落 3 列，格宽约 99dp（门槛可行区间 83~98.7 的
 *    靠上取整，上保 360dp 有 3 列、下防 411dp 掉进 4 列）；
 *  - 617dp 7 英寸阅读器屏落 5 列，格宽约 107dp；
 *  - 常见密度（560/440/420/330dpi）下 px 取整均不越界翻列。
 */
internal val EInkBookshelfGridMinCellWidth = 96.dp

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
 *
 * 布局模式由 [BookshelfUiState.isGridLayout] 驱动（首页顶栏切换按钮）：
 * 网格模式条目为 封面 + 未读角标 + 书名（对齐 View 版 item_bookshelf_grid），
 * 列数按屏宽自适应（[EInkBookshelfGridMinCellWidth]）。两种模式同为
 * E-Ink 分页模式（禁自由滚动，整页翻页），[listState]/[gridState]
 * 均由外层提升供首页底部操作栏驱动。
 */
@Composable
internal fun BookshelfScreen(
    state: BookshelfUiState,
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
            state.isEmpty -> EmptyBookshelf(modifier = Modifier.fillMaxSize())
            state.isGridLayout -> BookGrid(
                books = state.books,
                updatingBookUrls = state.updatingBookUrls,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
                gridState = gridState,
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
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
    books: List<ShelfBookUiModel>,
    updatingBookUrls: Set<String>,
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit,
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
        //
        // 条目跳过：不在 items 块里逐项新建 click lambda（那会让条目参数
        // 永不相等），回调直接透传、由条目内部用 model 字段构造；数据未变
        // 的条目参数全稳定相等，整条跳过重组
        items(books) { book ->
            BookListItem(
                book = book,
                isUpdating = updatingBookUrls.contains(book.bookUrl),
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick
            )
        }
    }
}

/**
 * 书架网格（E-Ink 分页模式，同 [BookList] 的翻页约定）。
 *
 * 列数由 [GridCells.Adaptive] 按屏宽解析（见 [EInkBookshelfGridMinCellWidth]
 * 的推导说明）；不用 key 的理由与 [BookList] 相同。
 */
@Composable
private fun BookGrid(
    books: List<ShelfBookUiModel>,
    updatingBookUrls: Set<String>,
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit,
    gridState: LazyGridState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(EInkBookshelfGridMinCellWidth),
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            ),
        state = gridState,
        contentPadding = PaddingValues(
            horizontal = EInkSpacing.m,
            vertical = EInkSpacing.s
        ),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m),
        verticalArrangement = Arrangement.spacedBy(EInkSpacing.m),
        userScrollEnabled = false,
        overscrollEffect = null
    ) {
        items(books) { book ->
            BookGridItem(
                book = book,
                isUpdating = updatingBookUrls.contains(book.bookUrl),
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick
            )
        }
    }
}

/**
 * 网格条目：封面（未读角标叠加右上角）+ 书名，对齐 View 版
 * item_bookshelf_grid 的组成与比例。
 *
 * 书名固定两行高（minLines = maxLines = 2），保证同行各列行高一致、
 * 翻页按完整行计；字体缩放放大（如 1.3）时高度随之增长但不破坏对齐。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    book: ShelfBookUiModel,
    isUpdating: Boolean,
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit
) {
    val unreadCount = book.unreadCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onBookClick(book.bookUrl) },
                onLongClick = { onBookLongClick(book) }
            )
    ) {
        // 封面占满格宽，按 View 版封面 66:90 比例定高。解码尺寸用门槛宽
        // 推导而非逐项 BoxWithConstraints 实测（子组合在弱 SoC 上拖慢整页
        // 翻帧）：各屏实际格宽 ≥ 门槛 96dp，上采样 ≤10%，墨水屏灰阶下
        // 不可感知
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(EInkCoverWidth / EInkCoverHeight)
        ) {
            EInkBookCover(
                url = book.coverUrl,
                name = book.name,
                author = book.displayAuthor,
                sourceOrigin = book.origin,
                modifier = Modifier.fillMaxSize(),
                width = EInkBookshelfGridMinCellWidth,
                height = EInkBookshelfGridMinCellWidth * (EInkCoverHeight / EInkCoverWidth)
            )
            // 角标规则与列表项一致：刷新中"…"，未读章节数（本次刷新
            // 发现新章时高亮）；位置同 View 版网格（封面右上角）
            val badgeText = when {
                isUpdating -> "…"
                unreadCount > 0 -> unreadCount.toString()
                else -> null
            }
            if (badgeText != null) {
                ShelfBadge(
                    text = badgeText,
                    highlight = !isUpdating && book.hasNewChapter,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(EInkSpacing.xxs)
                )
            }
        }
        EInkText(
            text = book.name,
            style = EInkTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = EInkSpacing.xs)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: ShelfBookUiModel,
    isUpdating: Boolean,
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit
) {
    val unreadCount = book.unreadCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onBookClick(book.bookUrl) },
                onLongClick = { onBookLongClick(book) }
            )
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        EInkBookCover(
            url = book.coverUrl,
            name = book.name,
            author = book.displayAuthor,
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
                        highlight = book.hasNewChapter,
                        modifier = Modifier.padding(start = EInkSpacing.xs)
                    )
                }
            }
            // 作者（图标 + 文字，同 View 版 iv_author）
            EInkInfoRow(
                iconRes = R.drawable.ic_author,
                text = book.displayAuthor,
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
