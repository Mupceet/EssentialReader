package io.legado.app.eink.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.designsystem.control.EInkSearchHintBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkOperationTab
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageController
import io.legado.app.eink.designsystem.pager.rememberEInkGridPagerState
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.bookshelf.BookshelfScreen
import io.legado.app.eink.feature.bookshelf.BookshelfViewModel
import io.legado.app.eink.feature.bookshelf.adaptiveGridColumns
import io.legado.app.eink.feature.bookshelf.bookshelfGridCellWidth
import io.legado.app.eink.feature.bookshelf.bookshelfListRowHeight
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.coverTargetSizePx
import io.legado.app.eink.feature.common.prefetchCovers
import kotlinx.coroutines.launch

/** 首页 Tab 下标。 */
internal object HomeTabs {
    const val BOOKSHELF = 0
    const val MINE = 1
}

/** 首页 Tab 文案（从左到右），同时作为头部标题。 */
private val HomeTabLabels = listOf("书架", "我的")

/** 首页 Tab 图标素材对（从左到右）：线性（未选中）+ 填充（选中），沿用 View 版底栏图标。 */
private val HomeTabIcons = listOf(
    R.drawable.eink_ic_bottom_books_e to R.drawable.eink_ic_bottom_books_s,
    R.drawable.eink_ic_bottom_person_e to R.drawable.eink_ic_bottom_person_s
)

/**
 * 首页 Route — ViewModel 感知层。
 *
 * 结构参考微信读书墨水屏版：
 *  - 顶部固定搜索框（点击进入搜索页）；
 *  - 搜索框下方一行头部（放大标题 + 右侧动作）：书架 Tab 显示刷新按钮
 *    （行为对齐 View 版下拉刷新），"我的" Tab 无动作；
 *  - 中间内容区：书架 / 我的 两个 Tab；
 *  - 底部通用操作栏（[EInkOperationBar]）：左侧 Tab 切换，
 *    右侧上/下箭头按当前 Tab 整页翻页（书架条目 /「我的」条目，
 *    零动画整页跳转），不可翻页时置灰。
 *
 * 书架布局默认网格、由 [BookshelfUiState.isGridLayout] 驱动，列表与网格
 * 各持一套固定页分页状态（[rememberEInkListPagerState] /
 * [rememberEInkGridPagerState]），经 [EInkPageController] 统一驱动
 * 底部操作栏翻页与页首对齐。orientation 与书架列表行高为分页几何键：
 * 旋转或字体缩放改变行高后，分页状态重建、页首回第一页并重新实测页项数。
 */
@Composable
fun HomeRoute(
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
    onSearch: () -> Unit,
    onOpenFullMode: () -> Unit = {},
    onOpenFontScale: () -> Unit = {},
    onOpenThemeDebug: () -> Unit = {},
    onOpenComponentGallery: () -> Unit = {},
    viewModel: BookshelfViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 分页几何键（旋转/分屏改变列数或行高）：变化后书架分页状态整体重建，
    // 页首回第一页并按新布局重新实测页项数
    val orientation = LocalConfiguration.current.orientation

    // 仅切换 UI 局部状态（当前 Tab），按 UDF 约定保留在 composable
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTabs.BOOKSHELF) }

    // 列表封面尺寸单点解析（同网格格宽约定：显示与预取共用同一 Dp 值，
    // 封面缓存键逐字节一致）：行高取基础封面高与字体缩放下文字实需高的
    // 较大值（bookshelfListRowHeight KDoc），宽按 66:90 等比随行高伸缩
    val typography = EInkTheme.typography
    val listCoverHeight = bookshelfListRowHeight(
        density = LocalDensity.current,
        titleLineHeight = typography.titleMedium.lineHeight,
        authorLineHeight = typography.bodySmall.lineHeight,
        chapterLineHeight = typography.labelMedium.lineHeight,
        showLatestChapter = uiState.style.showLatestChapter
    )
    val listCoverWidth = listCoverHeight * (EInkCoverWidth / EInkCoverHeight)

    // 书架固定页分页：首次布局测出一页项数，之后按该项数整页跳转；
    // 列表与网格各一套状态，切换布局后各自停在离开时的页。
    // 行高是列表分页几何键（行高随字体缩放伸缩，不再恒定）：改变后
    // 分页状态重建、页首回第一页并按新行高重新实测页项数
    val listPager = rememberEInkListPagerState(orientation, listCoverHeight)
    val gridPager = rememberEInkGridPagerState(orientation)
    val pager: EInkPageController = if (uiState.isGridLayout) gridPager else listPager
    // 「我的」页独立分页状态（条目整页翻页，对齐书架约定；行高与方向
    // 无关，保持无参——不作为几何键）
    val minePager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val totalBooks = uiState.books.size

    // 翻页动作：底部操作栏 ▲▼ 与列表/网格滑动手势共用（固定页项数，零动画整页跳转）。
    // remember 稳定实例：下传后接收方（BookshelfScreen / EInkPageSwipe）不因
    // lambda 逐次更换而被迫重组；翻页后上报 PageTurn 意图（规范 §26/§40，
    // NoOp 控制器下零行为，设备 Adapter 接入后由 Policy 决定档位）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, totalBooks, refresh, scope) {
        {
            scope.launch { pager.pageDown(totalBooks) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    // 「我的」页翻页动作（与书架同款零动画整页跳转；条目总数由列表
    // 布局信息提供，箭头槽内读取）
    val minePageUp: () -> Unit = remember(minePager, refresh, scope) {
        {
            scope.launch { minePager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val minePageDown: () -> Unit = remember(minePager, refresh, scope) {
        {
            scope.launch {
                minePager.pageDown(minePager.listState.layoutInfo.totalItemsCount)
            }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 数据变化（最后阅读排序更新/增删）后把实际滚动对齐回页首：
    // 列表原地重排后首可见项可能与 pageStart 脱钩。分页状态随导航栈
    // 保存恢复，从阅读页/搜索页返回时恢复离开时的 pageStart（不回
    // 第一页），本 realign 负责把恢复的滚动位置对齐到该页首；
    // 布局切换也走此处（对新分页状态对齐，未测量前 no-op）
    LaunchedEffect(uiState.books, uiState.isGridLayout) {
        pager.realignToPageStart(uiState.books.size)
    }

    // 预取所需 Context 与 Density（Route 作用域取一次，effect 内不重复解析）
    val prefetchContext = LocalContext.current
    val prefetchDensity = LocalDensity.current

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个首页随每次翻页重组；
    // 收敛到本槽内读取，翻页只重组箭头两个图标。按当前 Tab 分派：
    // 书架 Tab 驱动书架分页（不可翻页时置灰），「我的」Tab 驱动其条目
    // 分页（条目未满一页时两箭头同样置灰）
    val pageArrows: @Composable () -> Unit = {
        val isBookshelfTab = selectedTab == HomeTabs.BOOKSHELF
        val activePager: EInkPageController = if (isBookshelfTab) pager else minePager
        val activeTotal = if (isBookshelfTab) totalBooks
        else minePager.listState.layoutInfo.totalItemsCount
        EInkPageArrows(
            pageUpEnabled = activePager.canPageUp(),
            pageDownEnabled = activePager.canPageDown(activeTotal),
            onPageUp = { if (isBookshelfTab) pageUp() else minePageUp() },
            onPageDown = { if (isBookshelfTab) pageDown() else minePageDown() }
        )
    }

    // 格宽单点解析：显示（BookshelfScreen）与预取共用同一 Dp，封面缓存
    // 键逐字节一致（bookshelfGridCellWidth KDoc）。Route 级一次
    // BoxWithConstraints，不做逐项测量
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridColumns = adaptiveGridColumns(maxWidth, uiState.style.gridCoverWidth.dp)
        val gridCellWidth = bookshelfGridCellWidth(maxWidth, gridColumns)

        // 封面预取：当前页落定后预热下一页封面进内存缓存。加格宽/列表
        // 行高键：列数/屏宽/字体缩放变化时按新尺寸重新预热
        LaunchedEffect(uiState.books, uiState.isGridLayout, gridCellWidth, listCoverHeight) {
            val activePager = if (uiState.isGridLayout) gridPager else listPager
            // 与显示严格同源：网格用 bookshelfGridCellWidth、列表用
            // bookshelfListRowHeight 的同一 Dp 值（coverTargetSizePx 单点
            // 换算），预取键与显示键逐字节一致
            val (coverWidthPx, coverHeightPx) = if (uiState.isGridLayout) {
                coverTargetSizePx(
                    gridCellWidth,
                    gridCellWidth * (EInkCoverHeight / EInkCoverWidth),
                    prefetchDensity
                )
            } else {
                coverTargetSizePx(listCoverWidth, listCoverHeight, prefetchDensity)
            }
            snapshotFlow { activePager.pageStart to activePager.pageItemCount }
                .collect { page ->
                    val start = page.first
                    val pageSize = page.second
                    if (pageSize <= 0) return@collect
                    prefetchCovers(
                        context = prefetchContext,
                        items = uiState.books.drop(start + pageSize).take(pageSize),
                        widthPx = coverWidthPx,
                        heightPx = coverHeightPx,
                        coverUrl = { it.coverUrl },
                        sourceOrigin = { it.origin },
                    )
                }
        }

        HomeScreen(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            headerTitle = HomeTabLabels[selectedTab],
            showRefresh = selectedTab == HomeTabs.BOOKSHELF,
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            isGridLayout = uiState.isGridLayout,
            onToggleLayout = viewModel::toggleLayout,
            onSearchClick = onSearch,
            pageArrows = pageArrows,
            bookshelf = {
                BookshelfScreen(
                    state = uiState,
                    gridCellWidth = gridCellWidth,
                    gridColumns = gridColumns,
                    listCoverWidth = listCoverWidth,
                    listCoverHeight = listCoverHeight,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    listState = listPager.listState,
                    gridState = gridPager.gridState,
                    onPageUp = pageUp,
                    onPageDown = pageDown
                )
            },
            mine = {
                MineScreen(
                    pager = minePager,
                    onPageUp = minePageUp,
                    onPageDown = minePageDown,
                    onOpenFontScale = onOpenFontScale,
                    onOpenFullMode = onOpenFullMode,
                    onOpenThemeDebug = onOpenThemeDebug,
                    onOpenComponentGallery = onOpenComponentGallery
                )
            }
        )
    }
}

/**
 * 无状态首页外壳 — 顶部搜索框 + 头部标题行 + 内容区 + 底部操作栏。
 *
 * 内容通过 [bookshelf] / [mine] 槽位注入，外壳只负责布局。
 *
 * [pageArrows] 为翻页箭头槽：由承载层在其中读取分页状态并组合
 * [EInkPageArrows]，使翻页可用状态的读取收敛到箭头叶作用域。
 */
@Composable
internal fun HomeScreen(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    headerTitle: String,
    showRefresh: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onSearchClick: () -> Unit,
    pageArrows: @Composable () -> Unit,
    bookshelf: @Composable () -> Unit,
    mine: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        EInkSearchHintBar(onClick = onSearchClick)
        // 搜索栏与内容区之间的一行头部：左侧放大标题，右侧动作区
        //（动作按钮新规格：撑满顶栏高、贴右屏，自带底部分隔线）
        EInkTopBar(
            title = headerTitle,
            actionsFillMax = true,
            actions = {
                if (showRefresh) {
                    RefreshAction(
                        isRefreshing = isRefreshing,
                        onClick = onRefresh
                    )
                    LayoutToggleAction(isGridLayout, onToggleLayout)
                }
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            // 两个 Tab 常驻组合（E-Ink 零动画规范）：切换只翻转各自 Pane 的
            // 放置状态（小作用域），不销毁/重建组合树——书架整页条目（含
            // 封面请求）的组合与滚动/分页状态在 Tab 往返时保留；隐藏页不
            // 放置 = 不绘制、不命中、不进语义，触摸不会穿透到下层书架
            HomePane(visible = selectedTab == HomeTabs.BOOKSHELF) { bookshelf() }
            HomePane(visible = selectedTab != HomeTabs.BOOKSHELF) { mine() }
        }
        EInkOperationBar(
            tabs = HomeTabLabels.mapIndexed { index, label ->
                val (iconRes, selectedIconRes) = HomeTabIcons[index]
                EInkOperationTab(
                    icon = painterResource(iconRes),
                    selectedIcon = painterResource(selectedIconRes),
                    contentDescription = label
                )
            },
            selectedTabIndex = selectedTab,
            onTabSelect = onSelectTab,
            pageArrows = pageArrows
        )
    }
}

/**
 * 常驻组合的内容 Pane：可见时 zIndex 置顶承接触摸与绘制；隐藏时「测量但
 * 不放置」——未放置的子树不绘制、不参与命中测试（Compose 命中遍历只走
 * isPlaced 的子节点）、不进入语义树，触摸不会穿透到底层 Pane（否则点击
 * 隐藏页空白会命中下层书架条目）；组合本身不销毁（页面状态与 ViewModel
 * 保留），切回仅触发一次重布局。
 */
@Composable
private fun HomePane(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .then(
                if (visible) {
                    Modifier
                } else {
                    Modifier.layout { measurable, constraints ->
                        // 测量保组合活跃（Lazy 列表预热），但不放置：零绘制/零命中/零语义
                        measurable.measure(constraints)
                        layout(0, 0) {}
                    }
                }
            )
    ) {
        content()
    }
}

/**
 * 头部刷新按钮：空闲时显示刷新图标；刷新中置灰并禁用，避免重复点击。
 *
 * 与 View 版对齐：下拉刷新和菜单“更新目录”都不支持停止，只做排队去重，
 * 因此 E-Ink 这里同样不提供停止图标，而是刷新期间禁用按钮。
 */
@Composable
private fun RefreshAction(isRefreshing: Boolean, onClick: () -> Unit) {
    // 刷新中禁用置灰（组件 disabledContent 中灰），避免重复点击
    EInkOperationBarIcon(
        icon = painterResource(R.drawable.eink_ic_refresh_black_24dp),
        contentDescription = if (isRefreshing) "刷新中" else "刷新",
        enabled = !isRefreshing,
        onClick = onClick,
    )
}

/**
 * 书架布局切换按钮：静态显示目标布局的图标（网格态显示列表图标，
 * 点击切列表），零动画即时替换。切换经 VM 乐观更新并反向写宿主竖屏键。
 */
@Composable
private fun LayoutToggleAction(isGridLayout: Boolean, onClick: () -> Unit) {
    EInkOperationBarIcon(
        icon = painterResource(
            if (isGridLayout) R.drawable.eink_list_view_24px else R.drawable.eink_grid_view_24px
        ),
        contentDescription = if (isGridLayout) "切换为列表布局" else "切换为网格布局",
        onClick = onClick,
    )
}
