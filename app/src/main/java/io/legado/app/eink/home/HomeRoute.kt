package io.legado.app.eink.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.eink.bookshelf.BookshelfScreen
import io.legado.app.eink.bookshelf.BookshelfViewModel
import io.legado.app.eink.bookshelf.ShelfBookUiModel
import io.legado.app.eink.component.EInkPageController
import io.legado.app.eink.component.EInkOperationBar
import io.legado.app.eink.component.EInkOperationBarIcon
import io.legado.app.eink.component.EInkOperationTab
import io.legado.app.eink.component.EInkPageArrows
import io.legado.app.eink.component.EInkSearchHintBar
import io.legado.app.eink.component.EInkTopActionBar

import io.legado.app.eink.component.rememberEInkGridPagedListState
import io.legado.app.eink.component.rememberEInkPagedListState
import io.legado.app.eink.theme.EInkTheme
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
    R.drawable.ic_bottom_books_e to R.drawable.ic_bottom_books_s,
    R.drawable.ic_bottom_person_e to R.drawable.ic_bottom_person_s
)

/**
 * 首页 Route — ViewModel 感知层。
 *
 * 结构参考微信读书墨水屏版：
 *  - 顶部固定搜索框（点击进入搜索页）；
 *  - 搜索框下方一行头部（放大标题 + 右侧动作）：书架 Tab 显示刷新按钮
 *    （行为对齐 View 版下拉刷新）与列表/网格布局切换按钮，"我的" Tab
 *    无动作；
 *  - 中间内容区：书架 / 我的 两个 Tab；
 *  - 底部通用操作栏（[EInkOperationBar]）：左侧 Tab 切换，
 *    右侧上/下箭头对书架整页翻页，不可翻页时置灰。
 *
 * 书架布局由 [BookshelfUiState.isGridLayout] 驱动，列表与网格各持一套
 * 固定页分页状态（[rememberEInkPagedListState] /
 * [rememberEInkGridPagedListState]），经 [EInkPageController] 统一驱动
 * 底部操作栏翻页与页首对齐。
 */
@Composable
fun HomeRoute(
    onBookClick: (String) -> Unit,
    onBookLongClick: (ShelfBookUiModel) -> Unit,
    onSearch: () -> Unit,
    onOpenFullMode: () -> Unit = {},
    onOpenThemeDebug: () -> Unit = {},
    viewModel: BookshelfViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 仅切换 UI 局部状态（当前 Tab），按 UDF 约定保留在 composable
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTabs.BOOKSHELF) }

    // 书架固定页分页：首次布局测出一页项数，之后按该项数整页跳转；
    // 列表与网格各一套状态，切换布局后各自停在离开时的页
    val listPager = rememberEInkPagedListState()
    val gridPager = rememberEInkGridPagedListState()
    val pager: EInkPageController = if (uiState.isGridLayout) gridPager else listPager
    val scope = rememberCoroutineScope()
    val totalBooks = uiState.books.size

    // 翻页动作：底部操作栏 ▲▼ 与列表/网格滑动手势共用（固定页项数，零动画整页跳转）。
    // remember 稳定实例：下传后接收方（BookshelfScreen / EInkPageSwipe）不因
    // lambda 逐次更换而被迫重组
    val pageUp: () -> Unit = remember(pager, scope) {
        { scope.launch { pager.pageUp() } }
    }
    val pageDown: () -> Unit = remember(pager, totalBooks, scope) {
        { scope.launch { pager.pageDown(totalBooks) } }
    }

    // 数据变化（最后阅读排序更新/增删）后把实际滚动对齐回页首：
    // 列表原地重排后首可见项可能与 pageStart 脱钩。分页状态随导航栈
    // 保存恢复，从阅读页/搜索页返回时恢复离开时的 pageStart（不回
    // 第一页），本 realign 负责把恢复的滚动位置对齐到该页首；
    // 布局切换也走此处（对新分页状态对齐，未测量前 no-op）
    LaunchedEffect(uiState.books, uiState.isGridLayout) {
        pager.realignToPageStart(uiState.books.size)
    }

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个首页随每次翻页重组；
    // 收敛到本槽内读取，翻页只重组箭头两个图标。Tab 非书架时箭头置灰
    val pageArrows: @Composable () -> Unit = {
        val isBookshelfTab = selectedTab == HomeTabs.BOOKSHELF
        EInkPageArrows(
            pageUpEnabled = isBookshelfTab && pager.canPageUp(),
            pageDownEnabled = isBookshelfTab && pager.canPageDown(totalBooks),
            onPageUp = pageUp,
            onPageDown = pageDown
        )
    }

    HomeScreen(
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        headerTitle = HomeTabLabels[selectedTab],
        showRefresh = selectedTab == HomeTabs.BOOKSHELF,
        isRefreshing = uiState.isRefreshing,
        onRefresh = viewModel::refresh,
        isGridLayout = uiState.isGridLayout,
        onToggleLayout = viewModel::toggleGridLayout,
        onSearchClick = onSearch,
        pageArrows = pageArrows,
        bookshelf = {
            BookshelfScreen(
                state = uiState,
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
                onOpenFullMode = onOpenFullMode,
                onOpenThemeDebug = onOpenThemeDebug
            )
        }
    )
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
        EInkTopActionBar(
            title = headerTitle,
            actions = {
                if (showRefresh) {
                    RefreshAction(
                        isRefreshing = isRefreshing,
                        onClick = onRefresh
                    )
                    LayoutToggleAction(
                        isGridLayout = isGridLayout,
                        onClick = onToggleLayout
                    )
                }
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            // 两个 Tab 常驻组合（E-Ink 零动画规范）：切换只翻转各自 Pane 的
            // 可见性（小作用域），不销毁/重建组合树——书架整页条目（含
            // Glide 封面请求）在 Tab 往返时零重组；隐藏期间数据更新照常
            // 预热，切回即时可见
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
 * 常驻组合的内容 Pane：可见时 zIndex 置顶承接触摸与绘制；隐藏时跳过
 * 绘制（不产生任何绘制开销）并从语义树移除。不做销毁/重建。
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
                    Modifier
                        .drawWithContent {
                            // 隐藏页不绘制：跳过整棵子树的 draw 阶段
                        }
                        .clearAndSetSemantics { }
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
        icon = painterResource(R.drawable.ic_refresh_black_24dp),
        contentDescription = if (isRefreshing) "刷新中" else "刷新",
        enabled = !isRefreshing,
        onClick = onClick,
    )
}

/**
 * 头部布局切换按钮：显示目标布局图标（当前列表 → 网格图标，当前网格
 * → 列表图标），点击切换书架布局（[BookshelfViewModel.toggleGridLayout]）。
 *
 * 无选中态（selected 恒 false）：图标本身即当前状态的镜像，切换后
 * 即时整体替换为新目标图标，零动画。
 */
@Composable
private fun LayoutToggleAction(isGridLayout: Boolean, onClick: () -> Unit) {
    EInkOperationBarIcon(
        icon = painterResource(
            if (isGridLayout) R.drawable.list_view_24px else R.drawable.grid_view_24px
        ),
        contentDescription = if (isGridLayout) "切换为列表布局" else "切换为网格布局",
        onClick = onClick,
    )
}
