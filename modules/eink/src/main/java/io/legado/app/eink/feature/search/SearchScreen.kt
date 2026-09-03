package io.legado.app.eink.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.SearchBookUiModel
import io.legado.app.eink.contract.SearchHistoryUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkSearchInputBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.coverTargetSizePx
import io.legado.app.eink.feature.common.prefetchCovers
import kotlinx.coroutines.launch

/**
 * 搜索 Route — ViewModel 感知层。
 *
 * 骨架（参考微信读书墨水屏版）：
 *  - 顶部搜索条与首页完全同款（位置/尺寸/样式不变），进入即聚焦拉起输入法；
 *  - 点击"搜索"才发起搜索（输入过程不触发）；搜索中按钮切换为"停止"；
 *  - 结果/历史列表为固定页分页（[rememberEInkListPagerState]），
 *    滑动手势与底部 ▲▼ 翻页行为一致；
 *  - 底部操作栏：左侧返回按钮，右侧上下翻页箭头。
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onBookClick: (SearchBookUiModel) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun triggerSearch(key: String) {
        if (key.isBlank()) return
        keyboard?.hide()
        viewModel.search(key)
        // 只重置分页计数；列表被清空后会自然回到首页，不要在数据切换期滚动
        //（scrollToItem 会与测量竞争，导致越界或 layout state is not idle 崩溃）。
        pager.resetPaging()
    }

    // 历史删除态：纯界面本地状态（随导航栈保存恢复）；历史清空
    //（全部删除/逐条删光）后自动退出，避免下次进入残留删除模式
    var isDeletingHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.history.isEmpty()) {
        if (uiState.history.isEmpty()) isDeletingHistory = false
    }

    val isResultListVisible = uiState.results.isNotEmpty() || uiState.isSearching

    // 外层用 BoxWithConstraints 提供实测宽度：历史 chip 分行依赖真实可用宽
    //（Configuration 屏宽在 configChanges 下旋转不刷新，见 EInkOperationBar 说明）
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // maxWidth 先捕获为局部值：remember 计算块等非组合 lambda 内
        // 不能经隐式接收者读取 BoxWithConstraintsScope 属性
        val viewWidth = maxWidth
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val chipStyle = EInkTheme.typography.labelMedium
        val showHistoryDeleteMode = isDeletingHistory && uiState.history.isNotEmpty()
        // 历史 chip 贪心分行（一行 chip = LazyColumn 一项），固定页分页与
        // 手势整页翻页语义和结果列表一致；实测宽含删除态 ✕ 后缀，切换即重排
        val historyRows = remember(
            uiState.history,
            showHistoryDeleteMode,
            viewWidth,
            density,
            textMeasurer,
            chipStyle,
        ) {
            val rowWidthPx = with(density) { viewWidth.toPx() - 2 * EInkSpacing.m.toPx() }
            val maxChipWidthPx = (rowWidthPx * HistoryMaxChipWidthFraction).toInt()
            val chipChromePx =
                with(density) { (HistoryChipContentPadding * 2 + HistoryChipChromeAllowance).toPx() }.toInt()
            chunkHistoryRows(
                history = uiState.history,
                rowWidth = rowWidthPx.toInt(),
                spacing = with(density) { HistoryChipSpacing.toPx() }.toInt(),
                labelWidth = { keyword ->
                    val label = keyword.word +
                        if (showHistoryDeleteMode) HistoryDeleteMarkSuffix else ""
                    (textMeasurer.measure(label, chipStyle).size.width + chipChromePx)
                        .coerceAtMost(maxChipWidthPx)
                },
            )
        }

        // 历史视图的 LazyColumn 项数 = 头部 1 项 + chip 行数
        val totalItems = if (isResultListVisible) uiState.results.size else historyRows.size + 1
        val canPage = isResultListVisible || uiState.history.isNotEmpty()

        // 翻页动作 remember 稳定实例：下传后接收方（列表 / EInkPageSwipe）
        // 不因 lambda 逐次更换而被迫重组；翻页后上报 PageTurn 意图（规范 §26/§40）
        val refresh = LocalEInkRefreshController.current
        val pageUp: () -> Unit = remember(pager, refresh, scope) {
            {
                scope.launch { pager.pageUp() }
                refresh.requestRefresh(EInkRefreshIntent.PageTurn)
            }
        }
        val pageDown: () -> Unit = remember(pager, totalItems, refresh, scope) {
            {
                scope.launch { pager.pageDown(totalItems) }
                refresh.requestRefresh(EInkRefreshIntent.PageTurn)
            }
        }

        // 结果列表封面预取：当前页落定后预热下一页（同首页说明，翻页时
        // EInkAsyncImage 同步命中内存缓存，零占位帧、单次绘制）
        val prefetchContext = LocalContext.current
        val prefetchDensity = LocalDensity.current
        LaunchedEffect(uiState.results) {
            val (coverWidthPx, coverHeightPx) =
                coverTargetSizePx(EInkCoverWidth, EInkCoverHeight, prefetchDensity)
            snapshotFlow { pager.pageStart to pager.pageItemCount }
                .collect { page ->
                    val start = page.first
                    val pageSize = page.second
                    if (pageSize <= 0) return@collect
                    prefetchCovers(
                        context = prefetchContext,
                        items = uiState.results.drop(start + pageSize).take(pageSize),
                        widthPx = coverWidthPx,
                        heightPx = coverHeightPx,
                        coverUrl = { it.coverUrl },
                        sourceOrigin = { it.origin },
                    )
                }
        }

        // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
        // mutableStateOf），在 Route 作用域读取会让整个搜索页随每次翻页重组；
        // 收敛到槽内读取，翻页只重组箭头两个图标
        val pageArrows: @Composable () -> Unit = {
            EInkPageArrows(
                pageUpEnabled = canPage && pager.canPageUp(),
                pageDownEnabled = canPage && pager.canPageDown(totalItems),
                onPageUp = pageUp,
                onPageDown = pageDown
            )
        }

        // 历史删除/删除态切换都会重排行（条目变少、chip 加 ✕ 变宽）：把实际
        // 滚动位置拉回当前页首；结果列表只增不减，沿用原实现不 realign
        LaunchedEffect(historyRows, isResultListVisible) {
            if (!isResultListVisible) pager.realignToPageStart(historyRows.size + 1)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EInkTheme.colorScheme.background)
        ) {
            EInkSearchInputBar(
                value = uiState.searchKey,
                onValueChange = viewModel::updateKey,
                onImeAction = { triggerSearch(uiState.searchKey) },
                autoFocus = true,
                action = {
                    EInkText(
                        text = if (uiState.isSearching) "停止" else "搜索",
                        style = EInkTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable {
                                if (uiState.isSearching) viewModel.stopSearch()
                                else triggerSearch(uiState.searchKey)
                            }
                            .padding(vertical = EInkSpacing.m)
                    )
                }
            )
            EInkHorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isResultListVisible -> ResultList(
                        state = uiState,
                        isInBookshelf = viewModel::isInBookshelf,
                        onBookClick = onBookClick,
                        pagerListState = pager.listState,
                        onPageUp = pageUp,
                        onPageDown = pageDown
                    )

                    uiState.showEmpty -> CenterMessage("无搜索结果")
                    uiState.history.isNotEmpty() -> HistoryList(
                        rows = historyRows,
                        isDeleting = showHistoryDeleteMode,
                        maxChipWidth = viewWidth * HistoryMaxChipWidthFraction,
                        pagerListState = pager.listState,
                        onDeleteModeChange = { isDeletingHistory = it },
                        onClearHistory = viewModel::clearHistory,
                        onRemoveHistory = viewModel::removeHistory,
                        onSearch = { triggerSearch(it) },
                        onPageUp = pageUp,
                        onPageDown = pageDown
                    )

                    else -> CenterMessage("输入书名开始搜索")
                }
            }

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
                pageArrows = pageArrows
            )
        }
    }
}

/**
 * 无状态结果列表（固定页分页 + 手势整页翻页）。
 */
@Composable
private fun ResultList(
    state: SearchUiState,
    isInBookshelf: (SearchBookUiModel) -> Boolean,
    onBookClick: (SearchBookUiModel) -> Unit,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
) {
    LazyColumn(
        state = pagerListState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        items(state.results, key = { it.resultKey }) { book ->
            ResultItem(book = book, inShelf = isInBookshelf(book), onClick = { onBookClick(book) })
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun ResultItem(book: SearchBookUiModel, inShelf: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        // 封面（与首页书架一致；无封面时显示文字占位封面）
        EInkBookCover(
            url = book.coverUrl,
            name = book.name,
            author = book.author,
            sourceOrigin = book.origin,
            modifier = Modifier
                .width(EInkCoverWidth)
                .height(EInkCoverHeight)
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = EInkSpacing.xxs)
        ) {
            // 标题行：标题 + 右侧"已在书架"
            Row(verticalAlignment = Alignment.CenterVertically) {
                EInkText(
                    text = book.name,
                    modifier = Modifier.weight(1f),
                    style = EInkTheme.typography.titleMedium,
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
            // 作者（移除书源信息，与首页一致）
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_author,
                text = book.author.ifBlank { "佚名" },
                style = EInkTheme.typography.bodySmall
            )
            // 最新章节（有则显示）
            book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                EInkInfoRow(
                    iconRes = R.drawable.eink_ic_book_last,
                    text = title,
                    style = EInkTheme.typography.labelMedium
                )
            }

            // 简介（参考 View 版 tv_introduce；无简介显示"暂无简介"）
            EInkText(
                text = book.intro,
                style = EInkTheme.typography.bodySmall,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = EInkSpacing.xxs)
            )
        }
    }
}

/**
 * 无状态历史列表（chip 流式布局，同样固定页分页 + 手势翻页）。
 *
 * 每个搜索词为一枚 chip 样式 [EInkButton]，一行 chip =
 * LazyColumn 一项（行内容由上层预计算，见 [chunkHistoryRows]）。
 * 交互（参考 JBusDriver 搜索历史）：
 *  - 常态：点 chip 即搜索该词；
 *  - 删除态：chip 文字尾部附加 ✕，整枚 chip 点击删除该条（E-Ink 上
 *    不拆分独立 ✕ 小热区，整枚 chip 都是删除触控目标）；头部右侧
 *    变为「全部删除 / 完成」。
 */
@Composable
private fun HistoryList(
    rows: List<List<SearchHistoryUiModel>>,
    isDeleting: Boolean,
    maxChipWidth: Dp,
    pagerListState: LazyListState,
    onDeleteModeChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onSearch: (String) -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
) {
    LazyColumn(
        state = pagerListState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        item(key = "history_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
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
                if (isDeleting) {
                    EInkText(
                        text = "全部删除",
                        style = EInkTheme.typography.labelMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            onClearHistory()
                            onDeleteModeChange(false)
                        }
                    )
                    Spacer(modifier = Modifier.width(EInkSpacing.m))
                    EInkText(
                        text = "完成",
                        style = EInkTheme.typography.labelMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onDeleteModeChange(false) }
                    )
                } else {
                    EInkText(
                        text = "删除",
                        style = EInkTheme.typography.labelMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onDeleteModeChange(true) }
                    )
                }
            }
        }
        items(rows, key = { it.first().word }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(HistoryChipSpacing),
            ) {
                row.forEach { keyword ->
                    EInkButton(
                        text = keyword.word + if (isDeleting) HistoryDeleteMarkSuffix else "",
                        onClick = {
                            if (isDeleting) onRemoveHistory(keyword.word)
                            else onSearch(keyword.word)
                        },
                        height = HistoryChipHeight,
                        style = EInkTheme.typography.labelMedium,
                        contentPadding = PaddingValues(horizontal = HistoryChipContentPadding),
                        onClickLabel = if (isDeleting) "删除此搜索记录" else null,
                        modifier = Modifier.widthIn(max = maxChipWidth),
                    )
                }
            }
        }
    }
}

/** 历史 chip 高度（行距 2×4dp，触控行高 44dp，与整行文本时期相当）。 */
private val HistoryChipHeight = 36.dp

/** 历史 chip 之间与行内的水平间距。 */
private val HistoryChipSpacing = EInkSpacing.s

/** 历史 chip 文字两侧内边距。 */
private val HistoryChipContentPadding = EInkSpacing.s

/** 预测量附加余量：左右 1dp 描边 + 测量与实际渲染的差值兜底。 */
private val HistoryChipChromeAllowance = 4.dp

/** 单枚 chip 最大占宽比例（超长词在按钮内 Ellipsis 截断）。 */
private const val HistoryMaxChipWidthFraction = 0.7f

/** 删除态 chip 尾部打叉标记（[EInkButton] 仅接收文本，图标以字形表达）。 */
private const val HistoryDeleteMarkSuffix = " ✕"

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EInkText(text = message, style = EInkTheme.typography.bodyLarge)
    }
}
