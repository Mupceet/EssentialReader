package io.legado.app.eink.feature.bookshelf

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkInfoRowIconSize
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.EInkListCoverHeight

/**
 * 网格格宽：可用宽扣除左右内容边距（各 [EInkSpacing.m]）与列间距
 * （[EInkSpacing.m] ×（列数 − 1））后均分，列数按封面宽自适应推导
 * （[adaptiveGridColumns]）。与 LazyVerticalGrid 的
 * GridCells.Fixed 同一约束解（同 contentPadding/Arrangement），显示与
 * 预取必须共用本函数（封面缓存键逐字节一致，见 coverTargetSizePx KDoc）。
 */
internal fun bookshelfGridCellWidth(availableWidth: Dp, columns: Int): Dp {
    val columns = columns.coerceAtLeast(1)
    return (availableWidth - EInkSpacing.m * 2 - EInkSpacing.m * (columns - 1)) / columns
}

/**
 * 网格列数推导（列宽主导，设计 §4）：可用宽扣除左右内容边距
 * （[EInkSpacing.m] × 2）后，按「格宽不小于 [minCellWidth]」推导列数，
 * 富余宽度均摊到各格（列距 [EInkSpacing.m]）。列数随屏宽与旋转自适应。
 */
internal fun adaptiveGridColumns(availableWidth: Dp, minCellWidth: Dp): Int {
    val effective = availableWidth - EInkSpacing.m * 2
    val columns = ((effective + EInkSpacing.m) / (minCellWidth + EInkSpacing.m)).toInt()
    return columns.coerceAtLeast(1)
}

/**
 * 网格标题最小高度：最大行数 × 标题行高，行数钳制到宿主滑杆区间 1..5。
 *
 * 用最小高度而不是 minLines：约束只抬高报告尺寸，不额外触发段落排版。
 */
internal fun bookshelfGridTitleHeight(
    density: Density,
    lineHeight: TextUnit,
    maxLines: Int,
): Dp = with(density) {
    (lineHeight * maxLines.coerceIn(1, 5)).toDp()
}

/**
 * 列表行高单点解析（列表模式对应网格的 [bookshelfGridCellWidth]）：
 * 取「基础封面高 [EInkListCoverHeight]」与「worst-case 文字实需高」
 * 的较大值——标题行 + 作者行 + 当前进度行（[showLatestChapter] 时
 * 再加最新章节行）逐行累加，信息行高为 max(图标 [EInkInfoRowIconSize]，
 * 文字行高)。
 *
 * 字体缩放只放大 sp、不改 dp：行高钉死常量时，放大文字即被竖向截断
 * （钉死 90dp 时按系统非线性缩放曲线约 1.35x 起四行实需超出内容框，
 * 1.6x 达约 102dp）。行高随缩放伸缩后，与文字列等高的封面一并放大；
 * 列内 xxs 垂直内边距（2dp×2）由基础封面高 120dp 的余量吸收——注意
 * Android 的 sp→dp 走非线性曲线（Compose 复刻系统查表插值，fontScale
 * ≥ 1.03 生效），不是线性放大，换算必须经真实 [Density]（同宿主），
 * 不可线性外推。
 *
 * 所有条目共用同一行高（不逐条实测）：固定页项数分页
 * （EInkListPagerState）依赖条目等高保证"页内项全部完整展示"，逐条
 * 高度会重新引入半截条目。行高直接决定封面解码尺寸与内存缓存键——
 * 显示与预取必须共用同一解析值（同 [bookshelfGridCellWidth]，宿主
 * 单点解析后下发），并作为列表分页状态的几何键（行高变化后分页
 * 重建重测）。
 *
 * 纯函数不读主题：typography 经 CompositionLocal 下发、组合外不可用，
 * 三种行高由调用方（HomeRoute 组合内）传入。
 */
internal fun bookshelfListRowHeight(
    density: Density,
    titleLineHeight: TextUnit,
    authorLineHeight: TextUnit,
    chapterLineHeight: TextUnit,
    showLatestChapter: Boolean,
): Dp = with(density) {
    val titleHeight = titleLineHeight.toDp()
    val authorRowHeight = maxOf(EInkInfoRowIconSize, authorLineHeight.toDp())
    val chapterRowHeight = maxOf(EInkInfoRowIconSize, chapterLineHeight.toDp())
    val textHeight = titleHeight + authorRowHeight + chapterRowHeight +
        if (showLatestChapter) chapterRowHeight else 0.dp
    maxOf(EInkListCoverHeight, textHeight)
}

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
 * 布局模式由 [BookshelfUiState.isGridLayout] 驱动（初始值读宿主快照；
 * 切换入口在首页顶栏，切换经 VM 乐观更新并反向写宿主竖屏键）：
 * 网格模式条目为 封面 + 未读角标 + 书名（对齐 View 版 item_bookshelf_grid），
 * 列数按封面宽自适应推导（adaptiveGridColumns）（GridCells.Fixed，格宽
 * [bookshelfGridCellWidth] 均分）；列表模式条目为 封面 + 四行信息
 * （对齐 View 版 item_bookshelf_list），行高由宿主按字体缩放单点解析
 * （[bookshelfListRowHeight]），封面与文字列等高、全部条目同一行高
 * （固定页项数分页的等高前提）。两种模式同为
 * E-Ink 分页模式（禁自由滚动，整页翻页），[listState]/[gridState]
 * 均由外层提升供首页底部操作栏驱动。
 */
@Composable
fun BookshelfScreen(
    state: BookshelfUiState,
    gridCellWidth: Dp,
    gridColumns: Int,
    listCoverWidth: Dp,
    listCoverHeight: Dp,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
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
                style = state.style,
                gridCellWidth = gridCellWidth,
                gridColumns = gridColumns,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
                gridState = gridState,
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )

            else -> BookList(
                books = state.books,
                updatingBookUrls = state.updatingBookUrls,
                style = state.style,
                listCoverWidth = listCoverWidth,
                listCoverHeight = listCoverHeight,
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
    books: List<BookshelfItemUiModel>,
    updatingBookUrls: Set<String>,
    style: BookshelfStyle,
    listCoverWidth: Dp,
    listCoverHeight: Dp,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
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
                style = style,
                listCoverWidth = listCoverWidth,
                listCoverHeight = listCoverHeight,
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
 * 列数按封面宽自适应推导（adaptiveGridColumns）传入（GridCells.Fixed），
 * 格宽由 [bookshelfGridCellWidth] 单点解析后传入；不用 key 的
 * 理由与 [BookList] 相同。
 */
@Composable
private fun BookGrid(
    books: List<BookshelfItemUiModel>,
    updatingBookUrls: Set<String>,
    style: BookshelfStyle,
    gridCellWidth: Dp,
    gridColumns: Int,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
    gridState: LazyGridState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns.coerceAtLeast(1)),
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
                style = style,
                gridCellWidth = gridCellWidth,
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
 * 书名高度由宿主标题最大行数控制（1..5）。以
 * 最大行数 × 行高作最小高度约束，保证同行各列等高、翻页按完整行计；
 * 行高为 sp，字体缩放放大时随之增长但不破坏对齐。不用 minLines：
 * 实际行数不足时它会补一次段落排版，整页组合时在弱 SoC 上被放大。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    book: BookshelfItemUiModel,
    style: BookshelfStyle,
    gridCellWidth: Dp,
    isUpdating: Boolean,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit
) {
    val unreadCount = book.unreadCount
    val gridCoverHeight = gridCellWidth * (EInkCoverHeight / EInkCoverWidth)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onBookClick(book.bookUrl) },
                onLongClick = { onBookLongClick(book) }
            )
    ) {
        // 封面占满格宽，按 View 版封面 66:90 比例定高。解码尺寸用格宽公式
        // （bookshelfGridCellWidth 的同一 Dp 值）推导而非逐项
        // BoxWithConstraints 实测（子组合在弱 SoC 上拖慢整页翻帧）：格宽
        // 与列数由宿主单点解析，解码尺寸与显示、预取逐字节一致，墨水屏
        // 灰阶下无重采样痕迹
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
                width = gridCellWidth,
                height = gridCoverHeight
            )
            // 角标规则与列表项一致：刷新中"…"，未读角标受宿主开关门控
            //（本次刷新发现新章时高亮）；位置同 View 版网格（封面右上角）
            val badgeText = shelfBadgeText(isUpdating, style.showUnreadBadge, unreadCount)
            if (badgeText != null) {
                ShelfBadge(
                    text = badgeText,
                    highlight = shelfBadgeHighlight(
                        isUpdating,
                        style.showUnreadBadge,
                        style.highlightNewChapter,
                        book.hasNewChapter
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(EInkSpacing.xxs)
                )
            }
        }
        // E-Ink 固定使用 14sp/16sp 紧凑标题并居中；titleSmallFont/titleCenter
        // 按设计主动忽略，避免字体放大或破坏现有网格观感
        val titleStyle = EInkTheme.typography.bodySmall
        val titleMaxLines = style.titleMaxLines.coerceIn(1, 5)
        val titleHeight = bookshelfGridTitleHeight(
            density = LocalDensity.current,
            lineHeight = titleStyle.lineHeight,
            maxLines = titleMaxLines,
        )
        EInkText(
            text = book.name,
            style = titleStyle,
            textAlign = TextAlign.Center,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = EInkSpacing.xs)
                .heightIn(min = titleHeight)
        )
    }
}

/**
 * 书架列表条目：封面 + 标题/角标 + 作者 + 当前进度 + 最新章节，对齐
 * View 版 item_bookshelf_list 的组成。
 *
 * 封面与文字列等高、全部条目同一行高（[bookshelfListRowHeight] 解析，
 * 宿主单点下发）：等高是固定页项数分页"页内项全部完整展示"的前提；
 * 行距固定 xs、整组纵向居中，行数不足的条目（无进度/未开最新章节）
 * 整组居中、仍占满行高。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: BookshelfItemUiModel,
    style: BookshelfStyle,
    listCoverWidth: Dp,
    listCoverHeight: Dp,
    isUpdating: Boolean,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit
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
        // 解码目标尺寸（width/height 参数）与显示尺寸（modifier）同源：
        // 内存缓存键带尺寸，与预取逐字节一致（EInkBookCover KDoc）
        EInkBookCover(
            url = book.coverUrl,
            name = book.name,
            author = book.displayAuthor,
            sourceOrigin = book.origin,
            modifier = Modifier
                .width(listCoverWidth)
                .height(listCoverHeight),
            width = listCoverWidth,
            height = listCoverHeight
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        Column(
            modifier = Modifier
                .weight(1f)
                // 与封面等高；行距固定 xs、整组纵向居中——SpaceBetween 会把
                // 120dp 行高的余量全摊进行距（默认倍率下约 13dp），视觉过空；
                // 收紧后 1.6x 曲线实需 ~101.6dp + 3×xs 仍在 116dp 内容框内
                //（行距若取 s=8dp，1.6x 四行 worst case 溢出重新截断，不可）
                .height(listCoverHeight)
                .padding(vertical = EInkSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(
                EInkSpacing.xs,
                Alignment.CenterVertically
            )
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
                shelfBadgeText(isUpdating, style.showUnreadBadge, unreadCount)?.let { text ->
                    ShelfBadge(
                        text = text,
                        highlight = shelfBadgeHighlight(
                            isUpdating,
                            style.showUnreadBadge,
                            style.highlightNewChapter,
                            book.hasNewChapter
                        ),
                        modifier = Modifier.padding(start = EInkSpacing.xs)
                    )
                }
            }
            // 作者（图标 + 文字，同 View 版 iv_author）
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_author,
                text = book.displayAuthor,
                style = EInkTheme.typography.bodySmall
            )
            // 当前进度章节（同 View 版 iv_read / ic_history）
            book.currentChapterTitle?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.eink_ic_history,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }
            // 最新章节（同 View 版 iv_last / ic_book_last；宿主开关门控）
            if (style.showLatestChapter) {
                book.latestChapterTitle?.let { title ->
                    EInkInfoRow(
                        iconRes = R.drawable.eink_ic_book_last,
                        text = title,
                        style = EInkTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}


/**
 * 书架角标文本（网格与列表共用）：刷新中显示省略号（优先，E-Ink 禁止
 * 加载动画的刷新态表达）；否则未读角标受宿主开关 [showUnreadBadge]
 * 门控、未读数大于 0 才显示。
 */
internal fun shelfBadgeText(
    isUpdating: Boolean,
    showUnreadBadge: Boolean,
    unreadCount: Int,
): String? = when {
    isUpdating -> "…"
    showUnreadBadge && unreadCount > 0 -> unreadCount.toString()
    else -> null
}

/**
 * 角标反色高亮：本次刷新发现新章（语义对齐 View 版
 * showUpdateBadge = showUnread && showUnreadNew && isNew）。刷新中不高亮，
 * 角标整体隐藏（未读开关关闭）时高亮无载体。
 */
internal fun shelfBadgeHighlight(
    isUpdating: Boolean,
    showUnreadBadge: Boolean,
    highlightNewChapter: Boolean,
    hasNewChapter: Boolean,
): Boolean = !isUpdating && showUnreadBadge && highlightNewChapter && hasNewChapter


/**
 * 书架角标：位于书籍标题同一行右侧，不覆盖封面与下方信息行。
 *
 * 样式对齐 MD3 主工程书架的 TextCard 角标：4dp 圆角（[EInkShapes.medium]）
 * 实心 chip、水平内边距 4dp（[EInkSpacing.xs]）。普通态为 surfaceVariant
 * 实心底 + onSurface 文字 + 1dp outline 标准描边（HighContrast 下
 * surfaceVariant 与页面背景同色，必须靠描边成形，规范 §7）。
 *
 * E-Ink 约束：静态绘制、零动画零阴影——刷新中不做转圈/闪烁，仅把角标
 * 静态替换为省略号，完成后一次性替换为数字或消失。高亮（本次刷新发现
 * 新章，lastCheckCount > 0）保留反色实心（黑底白字、无描边）——灰阶下
 * 比主工程的 Update 图标更醒目，且不引入新图标资源；两种状态均单帧绘制。
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
            .background(
                color = if (highlight) colors.onSurface else colors.surfaceVariant,
                shape = EInkShapes.medium
            )
            .then(
                if (highlight) {
                    Modifier
                } else {
                    // 普通态 1dp 标准描边：浅底 chip 在白色页面上可辨识
                    Modifier.border(
                        width = 1.dp,
                        color = colors.outline,
                        shape = EInkShapes.medium
                    )
                }
            )
            .padding(horizontal = EInkSpacing.xs, vertical = 0.dp),
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
