package io.legado.app.eink.feature.toc

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkFlowPagerState
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

/** 标题下三段切换的段高（与顶栏动作按钮同档，触控目标 ≥44dp）。 */
private val TocTabHeight = 44.dp

/** 选中下划线（black indicator，规范 §14）高度。 */
private val TocTabIndicatorHeight = 3.dp

/** Tab 行底结构线高度：1dp 实灰（规范 §11），给下方列表一个视觉起点。 */
private val TocTabRuleHeight = 1.dp

/**
 * 卡片左缩进：比章节头（[EInkSpacing.m]）多一档，卡片在视觉上"从属"于该
 * 章节头（层级 + 关联感）；右缘保持 m，按压底色仍铺满整行（点击区域不缩）。
 */
private val CardStartPadding = EInkSpacing.l

/** 卡片内块间距：首行元信息 ↔ 第一个内容块。 */
private val CardBlockGap = EInkSpacing.s

/** 正文级内容块（想法内容 / 书签笔记）与前一块的额外间距：把引用与想法拉开。 */
private val CardBodyGap = EInkSpacing.s

/**
 * 目录 Route — ViewModel 感知层。
 *
 * 列表为固定页分页（翻页按钮与上下滑动手势一致），
 * 右侧滑动手柄支持快速定位；进入/切换排序时定位到当前阅读章节。
 *
 * 三段（目录 / 书签 / 笔记，marksEngine 缺失时仅目录）：三个 Tab 各自独立
 * 分页状态——目录为定高章节列表（计数分页 + 右侧快速滑动手柄），
 * 书签/笔记为**按章节聚合的卡片列表**，卡片高度随内容变化，用变高分页
 * （[rememberEInkFlowPagerState]）：每页从「上一条完整展示完」处接着走，
 * 不裁半截、不漏条目，章节头与其卡片同页。跳转目标（书签 / 划线 / 想法）
 * 由本层执行引擎动作（有会话即时跳章 / 无会话落进度）后交
 * [onJumpToLocation] 做纯导航；笔记导出经 SAF 取 uri 交 ViewModel。
 */
@Composable
fun TocRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit = {},
    onJumpToLocation: (JumpResolution.Located) -> Unit = {},
    viewModel: TocViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pager = rememberEInkListPagerState()
    val pagerBookmarks = rememberEInkFlowPagerState()
    val pagerNotes = rememberEInkFlowPagerState()
    val scope = rememberCoroutineScope()
    val displayCount = uiState.displayChapters.size
    // 章节聚合行：Tab 渲染与分页共用同一份（下标口径一致）；
    // keep-with-next = 章节头必须与它的卡片同页
    val bookmarkRows = remember(uiState.bookmarks) { bookmarkRows(uiState.bookmarks) }
    val markingRows = remember(uiState.markings) { markingRows(uiState.markings) }
    val bookmarkKeepWithNext: (Int) -> Boolean = remember(bookmarkRows) {
        { index -> bookmarkRows.getOrNull(index) is TocMarkRow.ChapterHeader }
    }
    val markingKeepWithNext: (Int) -> Boolean = remember(markingRows) {
        { index -> markingRows.getOrNull(index) is TocMarkRow.ChapterHeader }
    }

    // 导出：SAF uri 回投；pendingExport 防 launcher 复用/重建结果误触发
    var pendingExport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null && pendingExport) {
            viewModel.exportMarkdown(bookUrl, uri.toString())
        }
        pendingExport = false
    }

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

    // 书签 Tab 翻页动作（变高分页：页首按布局实测推进；分页状态独立）
    val bookmarkPageUp: () -> Unit = remember(pagerBookmarks, refresh, scope) {
        {
            scope.launch { pagerBookmarks.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val bookmarkPageDown: () -> Unit =
        remember(pagerBookmarks, bookmarkRows, bookmarkKeepWithNext, refresh, scope) {
            {
                scope.launch {
                    pagerBookmarks.pageDown(bookmarkRows.size, bookmarkKeepWithNext)
                }
                refresh.requestRefresh(EInkRefreshIntent.PageTurn)
            }
        }

    // 笔记 Tab 翻页动作（同上，分页状态与书签 Tab 独立）
    val notePageUp: () -> Unit = remember(pagerNotes, refresh, scope) {
        {
            scope.launch { pagerNotes.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val notePageDown: () -> Unit =
        remember(pagerNotes, markingRows, markingKeepWithNext, refresh, scope) {
            {
                scope.launch { pagerNotes.pageDown(markingRows.size, markingKeepWithNext) }
                refresh.requestRefresh(EInkRefreshIntent.PageTurn)
            }
        }

    // 数据原地变化（新增/删除标记、书签重排）后把页首拉回，列表缩短时收敛
    LaunchedEffect(bookmarkRows.size) {
        pagerBookmarks.realignToPageStart(bookmarkRows.size)
    }
    LaunchedEffect(markingRows.size) {
        pagerNotes.realignToPageStart(markingRows.size)
    }

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个目录页随每次翻页/滑块
    // 定位重组；收敛到槽内读取，翻页只重组箭头两个图标。按当前 Tab
    // 读对应分页器
    val pageArrows: @Composable () -> Unit = {
        when (uiState.selectedTab) {
            TocTab.Bookmarks -> EInkPageArrows(
                pageUpEnabled = pagerBookmarks.canPageUp(),
                pageDownEnabled = pagerBookmarks.canPageDown(
                    bookmarkRows.size, bookmarkKeepWithNext,
                ),
                onPageUp = bookmarkPageUp,
                onPageDown = bookmarkPageDown,
            )

            TocTab.Notes -> EInkPageArrows(
                pageUpEnabled = pagerNotes.canPageUp(),
                pageDownEnabled = pagerNotes.canPageDown(
                    markingRows.size, markingKeepWithNext,
                ),
                onPageUp = notePageUp,
                onPageDown = notePageDown,
            )

            TocTab.Chapters -> EInkPageArrows(
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
        noteListState = pagerNotes.listState,
        bookmarkRows = bookmarkRows,
        markingRows = markingRows,
        pageArrows = pageArrows,
        onPageUp = pageUp,
        onPageDown = pageDown,
        onBookmarkPageUp = bookmarkPageUp,
        onBookmarkPageDown = bookmarkPageDown,
        onNotePageUp = notePageUp,
        onNotePageDown = notePageDown,
        onScrub = onScrub,
        onScrubEnd = onScrubEnd,
        onBack = onBack,
        // 回到当前/去底部按 Tab 分派（点击时读最新状态）：
        // 书签/笔记 Tab = 定位到当前阅读章的聚合头 / 列表末条
        onBackToCurrent = {
            scope.launch {
                when (uiState.selectedTab) {
                    TocTab.Bookmarks -> pagerBookmarks.jumpToItem(
                        currentChapterRowIndex(bookmarkRows, uiState.currentChapterIndex) ?: 0,
                        bookmarkRows.size,
                    )

                    TocTab.Notes -> pagerNotes.jumpToItem(
                        currentChapterRowIndex(markingRows, uiState.currentChapterIndex) ?: 0,
                        markingRows.size,
                    )

                    TocTab.Chapters -> pager.jumpToItemAligned(displayIndexOfCurrent())
                }
            }
        },
        onGoToBottom = {
            scope.launch {
                when (uiState.selectedTab) {
                    TocTab.Bookmarks -> pagerBookmarks.jumpToItem(
                        bookmarkRows.lastIndex.coerceAtLeast(0),
                        bookmarkRows.size,
                    )

                    TocTab.Notes -> pagerNotes.jumpToItem(
                        markingRows.lastIndex.coerceAtLeast(0),
                        markingRows.size,
                    )

                    TocTab.Chapters -> pager.jumpToItemAligned((displayCount - 1).coerceAtLeast(0))
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
        onMarkingClick = viewModel::onMarkingClick,
        onExport = {
            pendingExport = true
            exportLauncher.launch("${uiState.book?.name.orEmpty()}-笔记.md")
        },
        onConfirmJump = viewModel::confirmPendingJump,
        onDismissJump = viewModel::dismissPendingJump,
    )
}

/**
 * 无状态目录 Screen。
 *
 * 结构：顶栏（书名 + Tab 相关动作按钮：目录 Tab 为正/倒序、笔记 Tab 为
 * 导出）→ **标题下三段切换（目录 / 书签 / 笔记）** → 内容区
 *（目录 Tab：章节列表定高计数分页 + 右侧快速滑动手柄；
 *  书签 / 笔记 Tab：按章节聚合的卡片列表，**变高分页**
 *  [io.legado.app.eink.designsystem.pager.EInkFlowPagerState]）→
 * 底部操作栏（返回 / 回到当前 / 去底部 居左连续 + 翻页胶囊；Tab 已上移到
 * 标题下，底栏不再放 Tab）。
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
    noteListState: LazyListState,
    bookmarkRows: List<TocMarkRow>,
    markingRows: List<TocMarkRow>,
    pageArrows: @Composable () -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onBookmarkPageUp: () -> Unit,
    onBookmarkPageDown: () -> Unit,
    onNotePageUp: () -> Unit,
    onNotePageDown: () -> Unit,
    onScrub: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onBack: () -> Unit,
    onBackToCurrent: () -> Unit,
    onGoToBottom: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onToggleReverse: () -> Unit,
    onTabSelect: (TocTab) -> Unit,
    onBookmarkClick: (Long) -> Unit,
    onMarkingClick: (String) -> Unit,
    onExport: () -> Unit,
    onConfirmJump: () -> Unit,
    onDismissJump: () -> Unit,
) {
    // 降级宿主（marksEngine 缺失）：Tab 行不渲染，内容也强制回目录——
    // 避免状态里残留的书签/笔记 Tab 形成无路可退的死屏
    val tab = if (state.marksAvailable) state.selectedTab else TocTab.Chapters
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：书名居左，动作按钮随 Tab 切换（新规格：撑满顶栏高、贴右屏）
            EInkTopBar(
                // 书名缺失（极端态）时用当前 Tab 名兜底，与标题下三段一致
                title = state.book?.name ?: when (tab) {
                    TocTab.Chapters -> "目录"
                    TocTab.Bookmarks -> "书签"
                    TocTab.Notes -> "笔记"
                },
                actionsFillMax = true,
                actions = {
                    when (tab) {
                        // 目录 Tab：正/倒序按钮（图标随状态互换，asc/desc 成对素材）
                        TocTab.Chapters -> EInkOperationBarIcon(
                            icon = painterResource(
                                if (state.isReversed) R.drawable.eink_ic_toc_sort_desc
                                else R.drawable.eink_ic_toc_sort_asc
                            ),
                            contentDescription = if (state.isReversed) "倒序" else "正序",
                            onClick = onToggleReverse,
                        )

                        // 笔记 Tab：导出 Markdown（无笔记或导出中置灰）
                        TocTab.Notes -> EInkOperationBarIcon(
                            icon = painterResource(R.drawable.eink_ic_note_export),
                            contentDescription = "导出笔记",
                            enabled = state.canExport,
                            onClick = onExport,
                        )

                        // 书签 Tab：无附加动作
                        TocTab.Bookmarks -> Unit
                    }
                }
            )
            // 标题下三段切换：marksEngine 缺失（降级宿主）时整体不渲染，
            // 只剩目录列表（契约 §3.3 降级语义，不留假死入口）
            if (state.marksAvailable) {
                TocTabRow(selected = tab, onSelect = onTabSelect)
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                    state.error != null -> CenterMessage(state.error)
                    tab == TocTab.Bookmarks -> MarksPane(
                        rows = bookmarkRows,
                        listState = bookmarkListState,
                        currentChapterIndex = state.currentChapterIndex,
                        emptyText = "暂无书签\n阅读页下拉或点页角标添加",
                        onPageUp = onBookmarkPageUp,
                        onPageDown = onBookmarkPageDown,
                        onBookmarkClick = onBookmarkClick,
                        onMarkingClick = onMarkingClick,
                    )
                    tab == TocTab.Notes -> MarksPane(
                        rows = markingRows,
                        listState = noteListState,
                        currentChapterIndex = state.currentChapterIndex,
                        emptyText = "暂无笔记\n选中正文后可画线或写想法",
                        onPageUp = onNotePageUp,
                        onPageDown = onNotePageDown,
                        onBookmarkClick = onBookmarkClick,
                        onMarkingClick = onMarkingClick,
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
            // 底部操作栏：返回 / 回到当前 / 去底部 居左连续 + 翻页胶囊
            // （Tab 已上移到标题下，底栏不带 Tab）
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
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
// 标题下三段切换
// ====================================================================

/**
 * 标题下三段切换（目录 / 书签 / 笔记）：**无边框文字 Tab + 黑色下划线**。
 *
 * 规范 §14「Tab: black indicator」：选中用黑色指示条表达，不用大面积反色。
 * 旧版一体化分段控件（1dp 外框 + 内分隔线 + 选中段实心反白）真机反馈
 * 「实现方式太重」，故整组去边框、去底色：三个 Tab 等宽，常态文字次级灰、
 * 选中文字正文黑 + 2dp 黑色下划线（宽度随标签文字，长度贴合文字而非整段，
 * 观感更轻）；按压仍是统一的反色反馈（§35），零动画。
 *
 * 行底留一条 1dp 实灰结构线（§11）给下方列表一个视觉起点——它不是按钮
 * 边框，而是 Tab 行与内容之间的分界。选中下划线**贴在这条线的位置上**
 * （下沿对齐行底，自然盖住那 1dp 灰线）：整行只读成一条线，选中段为黑，
 * 其余为灰——下划线若漂在文字正下方，就会和结构线构成"两道分隔"的观感
 * （真机反馈"离这个黑条太远，看起来变成了好几条分隔的感觉"）。
 */
@Composable
private fun TocTabRow(
    selected: TocTab,
    onSelect: (TocTab) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // 结构线先画：垫在 Tab 之下，选中下划线覆盖它
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(TocTabRuleHeight)
                .background(EInkTheme.colorScheme.divider),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TocTabItem(
                text = "目录",
                selected = selected == TocTab.Chapters,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TocTab.Chapters) },
            )
            TocTabItem(
                text = "书签",
                selected = selected == TocTab.Bookmarks,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TocTab.Bookmarks) },
            )
            TocTabItem(
                text = "笔记",
                selected = selected == TocTab.Notes,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(TocTab.Notes) },
            )
        }
    }
}

/**
 * 单个文字 Tab：无边框、无底色、**按压无视觉反馈**，文字 + 其下 2dp 指示条。
 *
 * 选中不只是加一条黑线，文字本身也进选中态：正文黑 + 加粗（未选中为次级灰 +
 * 常规字重）——与列表行持久选中的表达同源（§42：实心标记 + 标题加粗，
 * additive inking 在墨水屏上比"去黑"可靠）。两个字重差在墨水屏上是一笔
 * 明显的加黑，不必动字号，切换时行盒与基线都不变。
 *
 * 按压不反色（真机反馈"反色动作太大"）：规范 §13 的整块反色针对的是小面积
 * 控件，这里一枚 Tab 横跨 1/3 屏宽、高 44dp，按压整块翻黑在墨水屏上过重；
 * 点 Tab 的反馈由内容区即时整页切换承担（点按后当帧换列表，本身就是最强
 * 反馈），所以这一类大组件只保留 Selected 的黑色下划线，不做 Pressed 态。
 *
 * 指示条**不进布局**：用文字自身的实测宽度在 `drawBehind` 里画（不占位、
 * 不挤压文字），三个 Tab 的文字基线因此恒定对齐、切换不跳字。这里刻意
 * 不用 `Modifier.width(IntrinsicSize.Min)`：CJK 的 min intrinsic 是"最短可
 * 断行宽度"= 一个字宽，会把标签压成逐字竖排（真机反馈"文字是竖向的，
 * 也看不到黑线"）。
 */
@Composable
private fun TocTabItem(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val contentColor = if (selected) scheme.onBackground else scheme.secondaryContent
    val density = LocalDensity.current
    val tabHeightPx = with(density) { TocTabHeight.toPx() }
    val indicatorHeightPx = with(density) { TocTabIndicatorHeight.toPx() }
    Box(
        modifier = modifier
            .height(TocTabHeight)
            .semantics { this.selected = selected }
            .einkClickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.drawBehind {
                if (!selected) return@drawBehind
                // 文字盒垂直居中 → 段底（行底结构线所在处）= 文字盒顶 + (段高 + 文字高)/2
                val bottom = (tabHeightPx + size.height) / 2f
                drawRect(
                    color = scheme.onBackground,
                    topLeft = Offset(0f, bottom - indicatorHeightPx),
                    size = Size(size.width, indicatorHeightPx),
                )
            },
        )
    }
}

// ====================================================================
// 书签 / 笔记 Tab（按章节聚合的卡片列表）
// ====================================================================

/**
 * 书签/笔记列表 pane：章节聚合头 + 卡片，**变高分页**（卡片高度随内容行数
 * 变化，由承载层的 [io.legado.app.eink.designsystem.pager.EInkFlowPagerState]
 * 按布局实测翻页）。两个 Tab 各自的列表状态与分页状态独立。
 */
@Composable
private fun MarksPane(
    rows: List<TocMarkRow>,
    listState: LazyListState,
    currentChapterIndex: Int,
    emptyText: String,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onBookmarkClick: (Long) -> Unit,
    onMarkingClick: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        CenterMessage(emptyText)
        return
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier.fillMaxSize().EInkPageSwipe(onPageUp = onPageUp, onPageDown = onPageDown),
    ) {
        items(rows, key = { it.rowKey() }) { row ->
            when (row) {
                is TocMarkRow.ChapterHeader -> MarkChapterHeader(
                    header = row,
                    isCurrent = row.chapterIndex == currentChapterIndex,
                )

                is TocMarkRow.Bookmark -> BookmarkCard(
                    bookmark = row.bookmark,
                    onClick = { onBookmarkClick(row.bookmark.id) },
                )

                is TocMarkRow.Marking -> MarkingCard(
                    marking = row.marking,
                    onClick = { onMarkingClick(row.marking.id) },
                )
            }
        }
    }
}

/** 列表项稳定键：章节头用章下标、卡片用各自 id（跨 Tab 前缀区分）。 */
private fun TocMarkRow.rowKey(): String = when (this) {
    is TocMarkRow.ChapterHeader -> "chapter:$chapterIndex"
    is TocMarkRow.Bookmark -> "bookmark:${bookmark.id}"
    is TocMarkRow.Marking -> "marking:${marking.id}"
}

/**
 * 章节聚合头：章名 + 条目数。当前阅读章用左侧实心标记 + 章名加粗
 * （规范 §42：长列表持久选中用 additive inking，不整行反色）；
 * 底色用 surfaceVariant 与卡片区隔，翻页时一组头 + 卡片同页
 * （承载层 keep-with-next）。
 */
@Composable
private fun MarkChapterHeader(
    header: TocMarkRow.ChapterHeader,
    isCurrent: Boolean,
) {
    val scheme = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .size(width = CurrentMarkWidth, height = CurrentMarkHeight)
                    .background(scheme.onSurface)
            )
        }
        // 章节头 = 最强一级：正文色 + 加粗（墨水上"加黑"比换浅灰可靠）；
        // 当前阅读章再加左侧实心标记区分
        EInkText(
            text = header.chapterName,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 条数与卡片首行同属元信息：最弱一级
        EInkText(
            text = "${header.itemCount} 条",
            style = EInkTheme.typography.labelMedium,
            color = scheme.tertiaryContent,
        )
    }
}

/**
 * 卡片外壳：整卡可点（按压瞬时反色，规范 §35），内容经 [content] 拿到
 * 当前配色——第一行图标 + 基础信息（**不再带章节名**，章名已在聚合头），
 * 下面才是具体内容（高度随内容行数变化，翻页由变高分页保证完整展示）。
 */
@Composable
private fun MarkCard(
    onClick: () -> Unit,
    content: @Composable (primary: Color, secondary: Color, meta: Color) -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    val primary = if (press.isPressed) colors.contentColor else scheme.onSurface
    val secondary = if (press.isPressed) colors.contentColor else scheme.onSurfaceVariant
    // 元信息（首行时间）最弱一级：按压时随整卡反色
    val meta = if (press.isPressed) colors.contentColor else scheme.tertiaryContent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            // 左缩进多一档（卡片从属于章节头）；点击区域与底色仍是整行
            .padding(start = CardStartPadding, end = EInkSpacing.m, top = EInkSpacing.s, bottom = EInkSpacing.s),
        verticalArrangement = Arrangement.spacedBy(CardBlockGap),
    ) {
        content(primary, secondary, meta)
    }
}

/** 卡片第一行：小图标 + 基础信息（时间，最弱一级元信息色）；时间不可信时只留图标。 */
@Composable
private fun MarkCardHead(
    iconRes: Int,
    timeMillis: Long,
    meta: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(IconSize),
            colorFilter = ColorFilter.tint(meta),
        )
        formatMarkTime(timeMillis)?.let { time ->
            EInkText(
                text = time,
                style = EInkTheme.typography.labelMedium,
                color = meta,
            )
        }
    }
}

/**
 * 书签卡：第一行 = 书签图标 + 时间；内容 = 页面摘录（正文级）+
 * （完整模式编辑过的）书签笔记文本（次级色）。
 */
@Composable
private fun BookmarkCard(
    bookmark: BookmarkUiModel,
    onClick: () -> Unit,
) {
    MarkCard(onClick = onClick) { primary, secondary, meta ->
        MarkCardHead(
            iconRes = R.drawable.eink_ic_bookmark_s,
            timeMillis = bookmark.id,
            meta = meta,
        )
        if (bookmark.bookText.isNotBlank()) {
            EInkText(
                text = bookmark.bookText,
                style = EInkTheme.typography.bodyLarge,
                color = primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (bookmark.content.isNotBlank()) {
            EInkText(
                text = bookmark.content,
                // 与前一块（页面摘录）再拉开一档：两者语气不同（摘录 vs 自己的笔记）
                modifier = Modifier.padding(top = CardBodyGap),
                style = EInkTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 笔记卡：第一行 = 图标（划线 / 想法两种）+ 时间；内容 = 划线原文
 * **引用态弱化**（左侧细线 + 次级色），想法再叠一行想法内容（正文级强调）。
 */
@Composable
private fun MarkingCard(
    marking: MarkingUiModel,
    onClick: () -> Unit,
) {
    MarkCard(onClick = onClick) { primary, secondary, meta ->
        MarkCardHead(
            iconRes = if (marking.thought) {
                R.drawable.eink_ic_selection_thought
            } else {
                R.drawable.eink_ic_selection_line
            },
            timeMillis = marking.createdAt,
            meta = meta,
        )
        if (marking.selectedText.isNotBlank()) {
            QuotedText(text = marking.selectedText, color = secondary)
        }
        if (marking.thought && marking.note.isNotBlank()) {
            EInkText(
                text = marking.note,
                // 与引用态划线再拉开一档：引用是"别人的原文"，想法是自己写的
                modifier = Modifier.padding(top = CardBodyGap),
                style = EInkTheme.typography.bodyLarge,
                color = primary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 引用态文本：左侧 2dp 细线（随文本高度拉伸，[IntrinsicSize.Min] 让细线
 * 与文本同高）+ 缩进，文字弱化（次级色）——划线原文比想法内容"退后一层"。
 */
@Composable
private fun QuotedText(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(color)
        )
        EInkText(
            text = text,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
