package io.legado.app.eink.feature.toc

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.ChapterUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkOperationTab
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 快速滑动手柄触控宽度。 */
private val HandleTouchWidth = 36.dp

/** 手柄滑块尺寸。 */
private val HandleThumbWidth = 6.dp
private val HandleThumbHeight = 48.dp

/** 未缓存章节图标尺寸。 */
private val IconSize = 16.dp

/** 当前阅读章节左侧实心标记尺寸（▮，规范 §42 列表行持久选中）。 */
private val CurrentMarkWidth = 4.dp
private val CurrentMarkHeight = 16.dp

/**
 * 目录 Route — ViewModel 感知层。
 *
 * 列表为固定页分页（翻页按钮与上下滑动手势一致），
 * 右侧滑动手柄支持快速定位；进入/切换排序时定位到当前阅读章节。
 *
 * 双 Tab（目录|书签，marksEngine 缺失时仅目录）：两个 Tab 各自独立
 * 分页状态；跳转目标（书签）由本层执行引擎动作（有会话即时跳章/
 * 无会话落进度）后交 [onJumpToLocation] 做纯导航。
 */
@Composable
fun TocRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit = {},
    onOpenNote: () -> Unit = {},
    onJumpToLocation: (JumpResolution.Located) -> Unit = {},
    viewModel: TocViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pager = rememberEInkListPagerState()
    val pagerBookmarks = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val displayCount = uiState.displayChapters.size
    val bookmarkCount = uiState.bookmarks.size

    /** 当前阅读章节在当前展示顺序中的下标。 */
    fun displayIndexOfCurrent(): Int {
        val size = uiState.displayChapters.size
        if (size <= 0) return 0
        val index = uiState.currentChapterIndex.coerceIn(0, size - 1)
        return if (uiState.isReversed) size - 1 - index else index
    }

    // 初始定位完成前遮盖列表：LazyListState 初始在第 0 项，若直接显示
    // 会先闪现第一页再跳转；列表保持参与布局（驱动页项数测量），
    // 定位完成才揭开
    var positioned by remember(bookUrl) { mutableStateOf(false) }

    // 初始进入/切换排序后定位到当前阅读章节（未过滤时）
    LaunchedEffect(uiState.chapters, uiState.isReversed, uiState.searchKey) {
        if (uiState.chapters.isEmpty()) return@LaunchedEffect
        if (uiState.searchKey.isBlank()) {
            if (!positioned) {
                snapshotFlow { pager.pageItemCount }.first { it > 0 }
            }
            pager.jumpToItemAligned(displayIndexOfCurrent())
            positioned = true
        } else {
            pager.resetPaging()
            positioned = true
        }
    }

    // 加载书籍与目录
    LaunchedEffect(bookUrl) {
        viewModel.loadBook(bookUrl)
    }

    // 一次性消息 → Toast（对齐 BookDetailRoute messages 先例：组合期订阅，
    // SharedFlow 无 replay，点击回调内订阅会丢事件）
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 跳转目标：有会话即时跳章；无会话（详情等路径进入）落进度含章内位置，
    // 导航后阅读页装载时落位；引擎动作完成后 [onJumpToLocation] 只做导航
    LaunchedEffect(viewModel, bookUrl, onJumpToLocation) {
        viewModel.jumpTarget.collect { target ->
            val reader = EInkEngineRegistry.readerEngine
            if (reader.sessionBookUrl == bookUrl) {
                reader.jumpToPosition(target.chapterIndex, target.chapterPos)
            } else {
                val chapterTitle = viewModel.uiState.value.chapters
                    .getOrNull(target.chapterIndex)?.title ?: ""
                EInkEngineRegistry.tocEngine.saveReadingProgress(
                    bookUrl, target.chapterIndex, chapterTitle, target.chapterPos,
                )
            }
            onJumpToLocation(target)
        }
    }

    val onScrub: (Int) -> Unit = { index ->
        scope.launch { pager.listState.scrollToItem(index) }
    }
    val onScrubEnd: (Int) -> Unit = { index ->
        scope.launch { pager.jumpToItemAligned(index) }
    }

    // 翻页动作 remember 稳定实例：下传后接收方（章节列表 / EInkPageSwipe）
    // 不因 lambda 逐次更换而被迫重组；翻页后上报 PageTurn 意图（规范 §26/§40）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, displayCount, refresh, scope) {
        {
            scope.launch { pager.pageDown(displayCount) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 书签 Tab 翻页动作（与目录 Tab 同一写法，分页状态各自独立）
    val bookmarkPageUp: () -> Unit = remember(pagerBookmarks, refresh, scope) {
        {
            scope.launch { pagerBookmarks.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val bookmarkPageDown: () -> Unit = remember(pagerBookmarks, bookmarkCount, refresh, scope) {
        {
            scope.launch { pagerBookmarks.pageDown(bookmarkCount) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个目录页随每次翻页/滑块
    // 定位重组；收敛到槽内读取，翻页只重组箭头两个图标。按当前 Tab
    // 读对应分页器
    val pageArrows: @Composable () -> Unit = {
        if (uiState.selectedTab == TocTab.Bookmarks) {
            EInkPageArrows(
                pageUpEnabled = pagerBookmarks.canPageUp(),
                pageDownEnabled = pagerBookmarks.canPageDown(bookmarkCount),
                onPageUp = bookmarkPageUp,
                onPageDown = bookmarkPageDown,
            )
        } else {
            EInkPageArrows(
                pageUpEnabled = pager.canPageUp(),
                pageDownEnabled = pager.canPageDown(displayCount),
                onPageUp = pageUp,
                onPageDown = pageDown,
            )
        }
    }

    TocScreen(
        state = uiState,
        positioned = positioned,
        listState = pager.listState,
        bookmarkListState = pagerBookmarks.listState,
        pageArrows = pageArrows,
        onPageUp = pageUp,
        onPageDown = pageDown,
        onBookmarkPageUp = bookmarkPageUp,
        onBookmarkPageDown = bookmarkPageDown,
        onScrub = onScrub,
        onScrubEnd = onScrubEnd,
        onBack = onBack,
        // 回到当前/去底部按 Tab 分派（点击时读最新状态）：
        // 书签 Tab「回到当前」= 定位到当前章首条书签
        onBackToCurrent = {
            scope.launch {
                if (uiState.selectedTab == TocTab.Bookmarks) {
                    if (uiState.bookmarks.isEmpty()) return@launch
                    val index = uiState.bookmarks
                        .indexOfFirst { it.chapterIndex >= uiState.currentChapterIndex }
                        .coerceAtLeast(0)
                    pagerBookmarks.jumpToItemAligned(index)
                } else {
                    pager.jumpToItemAligned(displayIndexOfCurrent())
                }
            }
        },
        onGoToBottom = {
            scope.launch {
                if (uiState.selectedTab == TocTab.Bookmarks) {
                    pagerBookmarks.jumpToItemAligned(
                        (uiState.bookmarks.size - 1).coerceAtLeast(0)
                    )
                } else {
                    pager.jumpToItemAligned((displayCount - 1).coerceAtLeast(0))
                }
            }
        },
        onChapterClick = { index ->
            viewModel.openChapter(index) {
                onOpenReader(bookUrl)
            }
        },
        onToggleReverse = viewModel::toggleReverse,
        onTabSelect = viewModel::selectTab,
        onBookmarkClick = viewModel::onBookmarkClick,
        onOpenNote = onOpenNote,
        onConfirmJump = viewModel::confirmPendingJump,
        onDismissJump = viewModel::dismissPendingJump,
    )
}

/**
 * 无状态目录 Screen。
 *
 * 结构：顶栏（书名 + Tab 相关动作按钮：目录 Tab 为正/倒序、书签 Tab 为
 * 笔记入口）→ 内容区（目录 Tab：章节列表固定页分页 + 右侧快速滑动手柄；
 * 书签 Tab：书签列表固定页分页，无手柄）→ 底部操作栏（目录|书签 双 Tab +
 * 返回 / 回到当前 / 去底部 居左连续 + 翻页胶囊，统一 EInkOperationBar）。
 *
 * [pageArrows] 为翻页箭头槽：由承载层在其中读取分页状态并组合
 * [EInkPageArrows]，使翻页可用状态的读取收敛到箭头叶作用域。
 *
 * 根为 Box：跳转确认弹层（[EInkDialog]）必须组合在全屏容器子级
 * （其组合契约），作为内容 Column 的兄弟覆盖整屏。
 */
@Composable
internal fun TocScreen(
    state: TocUiState,
    positioned: Boolean,
    listState: LazyListState,
    bookmarkListState: LazyListState,
    pageArrows: @Composable () -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onBookmarkPageUp: () -> Unit,
    onBookmarkPageDown: () -> Unit,
    onScrub: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onBack: () -> Unit,
    onBackToCurrent: () -> Unit,
    onGoToBottom: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onToggleReverse: () -> Unit,
    onTabSelect: (TocTab) -> Unit,
    onBookmarkClick: (Long) -> Unit,
    onOpenNote: () -> Unit,
    onConfirmJump: () -> Unit,
    onDismissJump: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：书名居左，动作按钮随 Tab 切换（新规格：撑满顶栏高、贴右屏）
            EInkTopBar(
                title = state.book?.name
                    ?: if (state.selectedTab == TocTab.Bookmarks) "书签" else "目录",
                actionsFillMax = true,
                actions = {
                    if (state.selectedTab == TocTab.Bookmarks) {
                        // 书签 Tab：笔记入口（笔记页 Task 10 交付，本入口先行接线）
                        EInkOperationBarIcon(
                            icon = painterResource(R.drawable.eink_ic_note_entry),
                            contentDescription = "笔记",
                            onClick = onOpenNote,
                        )
                    } else {
                        // 目录 Tab：正/倒序按钮（图标随状态互换，asc/desc 成对素材）
                        EInkOperationBarIcon(
                            icon = painterResource(
                                if (state.isReversed) R.drawable.eink_ic_toc_sort_desc
                                else R.drawable.eink_ic_toc_sort_asc
                            ),
                            contentDescription = if (state.isReversed) "倒序" else "正序",
                            onClick = onToggleReverse,
                        )
                    }
                }
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                    state.error != null -> CenterMessage(state.error)
                    state.selectedTab == TocTab.Bookmarks -> BookmarkListPane(
                        state = state,
                        listState = bookmarkListState,
                        onPageUp = onBookmarkPageUp,
                        onPageDown = onBookmarkPageDown,
                        onBookmarkClick = onBookmarkClick,
                    )
                    state.isEmpty -> CenterMessage("无章节")
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            ChapterList(
                                modifier = Modifier.weight(1f),
                                state = state,
                                listState = listState,
                                onPageUp = onPageUp,
                                onPageDown = onPageDown,
                                onChapterClick = onChapterClick,
                            )
                            FastScrollHandle(
                                listState = listState,
                                totalItems = state.displayChapters.size,
                                onScrub = onScrub,
                                onScrubEnd = onScrubEnd,
                            )
                        }
                        // 初始定位未完成时遮盖，避免闪现第一页
                        if (!positioned) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(EInkTheme.colorScheme.surface)
                            )
                        }
                    }
                }
            }
            // 底部操作栏：目录|书签 双 Tab + 返回 / 回到当前 / 去底部 居左
            // 连续 + 翻页胶囊（与其它界面统一的 EInkOperationBar）；
            // marksEngine 缺失时无 Tab，单列表现状
            EInkOperationBar(
                tabs = if (state.marksAvailable) listOf(
                    EInkOperationTab(
                        icon = painterResource(R.drawable.eink_ic_toc_e),
                        selectedIcon = painterResource(R.drawable.eink_ic_toc),
                        contentDescription = "目录",
                    ),
                    EInkOperationTab(
                        icon = painterResource(R.drawable.eink_ic_bookmark_e),
                        selectedIcon = painterResource(R.drawable.eink_ic_bookmark_s),
                        contentDescription = "书签",
                    ),
                ) else emptyList(),
                selectedTabIndex = if (state.selectedTab == TocTab.Bookmarks) 1 else 0,
                onTabSelect = { onTabSelect(if (it == 1) TocTab.Bookmarks else TocTab.Chapters) },
                navigationIcon = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_arrow_back),
                        contentDescription = "返回",
                        onClick = onBack
                    )
                },
                actions = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_toc_locate),
                        contentDescription = "回到当前",
                        onClick = onBackToCurrent
                    )
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_toc_to_bottom),
                        contentDescription = "去底部",
                        onClick = onGoToBottom
                    )
                },
                pageArrows = pageArrows
            )
        }
        // 书签跳转目标存疑时的「仍跳转/取消」确认（覆盖整屏，见根 Box 说明）
        state.pendingJump?.let { pending ->
            EInkDialog(
                onDismiss = onDismissJump,
                title = "跳转确认",
                confirmText = "仍跳转",
                cancelText = "取消",
                onConfirm = onConfirmJump,
            ) {
                EInkText(text = pending.message, style = EInkTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ChapterList(
    modifier: Modifier,
    state: TocUiState,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onChapterClick: (Int) -> Unit,
) {
    // 展示项携带真实索引，避免倒序/过滤后索引错位
    val display: List<Pair<Int, ChapterUiModel>> = state.displayChapters
        .mapIndexed { index, chapter -> index to chapter }
        .let { if (state.isReversed) it.asReversed() else it }

    // 不支持自由滚动：上下滑动手势识别为整页翻页，与底部 ▲▼ 按钮同一动作
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        itemsIndexed(display, key = { _, (_, chapter) -> chapter.url }) { _, (realIndex, chapter) ->
            ChapterItem(
                chapter = chapter,
                isCurrent = realIndex == state.currentChapterIndex,
                // 本地书与卷章节视为已缓存（与 View 版一致）
                cached = state.isLocalBook
                        || chapter.isVolume
                        || state.cachedFileNames.contains(chapter.fileName),
                onClick = { onChapterClick(realIndex) }
            )
        }
    }
}

/**
 * 章节条目：按压瞬时反色（规范 §35）。
 *
 * 当前阅读章节为长列表持久选中态：不整行反色（大面积持久反色退出时
 * 残影重，规范 §42），改用左侧实心标记 + 标题加粗 + "在读"标签
 * （additive inking，"加黑"比"去黑"可靠）。
 * 未缓存章节显示云端图标。
 */
@Composable
private fun ChapterItem(
    chapter: ChapterUiModel,
    isCurrent: Boolean,
    cached: Boolean,
    onClick: () -> Unit
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    // 标记/标签随按压反色；标题：当前章加深，其余为次级色
    val markColor = if (press.isPressed) scheme.surface else scheme.onSurface
    val titleColor = when {
        press.isPressed -> colors.contentColor
        isCurrent -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .size(width = CurrentMarkWidth, height = CurrentMarkHeight)
                    .background(markColor)
            )
        }
        EInkText(
            text = chapter.title,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!cached) {
            // 未下载缓存标记（云图标，与 View 版一致），颜色随按压反色
            Image(
                painter = painterResource(id = R.drawable.eink_ic_outline_cloud_24),
                contentDescription = "未缓存",
                modifier = Modifier.size(IconSize),
                colorFilter = ColorFilter.tint(titleColor),
            )
        }
        if (isCurrent) {
            EInkText(
                text = "在读",
                style = EInkTheme.typography.labelMedium,
                color = markColor,
            )
        }
    }
}


// ====================================================================
// 书签 Tab
// ====================================================================

/**
 * 书签列表 pane：同款固定页分页（无滑动手柄），分页状态与目录列表
 * 各自独立（由承载层分别传入）。
 */
@Composable
private fun BookmarkListPane(
    state: TocUiState,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onBookmarkClick: (Long) -> Unit,
) {
    if (state.bookmarks.isEmpty()) {
        CenterMessage("暂无书签\n阅读页下拉或点角标添加")
        return
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier.fillMaxSize().EInkPageSwipe(onPageUp = onPageUp, onPageDown = onPageDown),
    ) {
        itemsIndexed(state.bookmarks, key = { _, b -> b.id }) { _, bookmark ->
            BookmarkItem(
                bookmark = bookmark,
                isCurrent = bookmark.chapterIndex == state.currentChapterIndex,
                onClick = { onBookmarkClick(bookmark.id) },
            )
        }
    }
}

/**
 * 书签条目：章节名 + 页面文本摘录 + 笔记文本（按压瞬时反色，规范 §35）。
 * 当前阅读章节为左侧实心标记 + 章名加粗（规范 §42 列表行持久选中，
 * additive inking）。
 */
@Composable
private fun BookmarkItem(
    bookmark: BookmarkUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s)) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(width = CurrentMarkWidth, height = CurrentMarkHeight)
                        .background(if (press.isPressed) scheme.surface else scheme.onSurface)
                )
            }
            EInkText(
                text = bookmark.chapterName,
                style = EInkTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
            )
        }
        if (bookmark.bookText.isNotBlank()) {
            EInkText(
                text = bookmark.bookText,
                style = EInkTheme.typography.bodyLarge,
                color = if (press.isPressed) colors.contentColor else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (bookmark.content.isNotBlank()) {
            EInkText(
                text = bookmark.content,
                style = EInkTheme.typography.bodyMedium,
                color = if (press.isPressed) colors.contentColor else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


// ====================================================================
// 右侧快速滑动手柄
// ====================================================================

/**
 * 快速滑动手柄：右侧窄条，上下拖动按比例快速定位列表位置。
 *
 * 拖动中自由滚动到目标项（即时反馈），拖动结束回调 [onScrubEnd]
 * 由调用方对齐到完整页边界。滑块位置指示当前列表位置。
 */
@Composable
private fun FastScrollHandle(
    listState: LazyListState,
    totalItems: Int,
    onScrub: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightPx = with(LocalDensity.current) { HandleThumbHeight.toPx() }
    val lastIndex = totalItems.coerceAtLeast(1) - 1
    val fraction = if (lastIndex > 0) {
        listState.firstVisibleItemIndex.toFloat() / lastIndex
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HandleTouchWidth)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(totalItems) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val usable = trackHeightPx - thumbHeightPx
                        if (usable > 0 && lastIndex > 0) {
                            val current = listState.firstVisibleItemIndex.toFloat() / lastIndex
                            val target =
                                ((current + dragAmount / usable).coerceIn(0f, 1f) * lastIndex)
                                    .roundToInt()
                            onScrub(target)
                        }
                    },
                    onDragEnd = {
                        onScrubEnd(listState.firstVisibleItemIndex)
                    },
                )
            },
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(2.dp)
                .background(scheme.outline)
        )
        // 滑块（当前位置指示）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (fraction * (trackHeightPx - thumbHeightPx)).roundToInt()) }
                .width(HandleThumbWidth)
                .height(HandleThumbHeight)
                .background(scheme.onSurfaceVariant, EInkShapes.small)
        )
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EInkText(text = message, style = EInkTheme.typography.bodyLarge)
    }
}
