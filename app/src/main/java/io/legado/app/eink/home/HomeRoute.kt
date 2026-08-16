package io.legado.app.eink.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.eink.bookshelf.BookshelfScreen
import io.legado.app.eink.bookshelf.BookshelfViewModel
import io.legado.app.eink.component.EInkIconButton
import io.legado.app.eink.component.EInkOperationBar
import io.legado.app.eink.component.EInkSearchHintBar
import io.legado.app.eink.component.EInkTopBar

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

/**
 * 首页 Route — ViewModel 感知层。
 *
 * 结构参考微信读书墨水屏版：
 *  - 顶部固定搜索框（点击进入搜索页）；
 *  - 搜索框下方一行头部（放大标题 + 右侧动作）：书架 Tab 显示刷新按钮
 *    （行为对齐 View 版下拉刷新），"我的" Tab 无动作；
 *  - 中间内容区：书架 / 我的 两个 Tab；
 *  - 底部通用操作栏（[EInkOperationBar]）：左侧 Tab 切换，
 *    右侧上/下箭头对书架列表整页翻页，不可翻页时置灰。
 */
@Composable
fun HomeRoute(
    onBookClick: (String) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onSearch: () -> Unit,
    onOpenFullMode: () -> Unit = {},
    viewModel: BookshelfViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 仅切换 UI 局部状态（当前 Tab），按 UDF 约定保留在 composable
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTabs.BOOKSHELF) }

    // 书架固定页分页：首次布局测出一页项数，之后按该项数整页跳转
    val pager = rememberEInkPagedListState()
    val scope = rememberCoroutineScope()
    val totalBooks = uiState.books.size

    val isBookshelfTab = selectedTab == HomeTabs.BOOKSHELF
    val canPageUp = isBookshelfTab && pager.canPageUp()
    val canPageDown = isBookshelfTab && pager.canPageDown(totalBooks)

    // 翻页动作：底部操作栏 ▲▼ 与列表滑动手势共用（固定页项数，零动画整页跳转）
    val pageUp: () -> Unit = {
        scope.launch { pager.pageUp() }
    }
    val pageDown: () -> Unit = {
        scope.launch { pager.pageDown(totalBooks) }
    }

    // 数据变化（最后阅读排序更新/增删）后把实际滚动对齐回页首：
    // 列表原地重排后首可见项可能与 pageStart 脱钩。分页状态随导航栈
    // 保存恢复，从阅读页/搜索页返回时恢复离开时的 pageStart（不回
    // 第一页），本 realign 负责把恢复的滚动位置对齐到该页首
    LaunchedEffect(uiState.books) {
        pager.realignToPageStart(uiState.books.size)
    }

    HomeScreen(
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        headerTitle = HomeTabLabels[selectedTab],
        showRefresh = isBookshelfTab,
        isRefreshing = uiState.isRefreshing,
        onRefresh = viewModel::refresh,
        canPageUp = canPageUp,
        canPageDown = canPageDown,
        onPageUp = pageUp,
        onPageDown = pageDown,
        onSearchClick = onSearch,
        bookshelf = {
            BookshelfScreen(
                state = uiState,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
                listState = pager.listState,
                onPageUp = pageUp,
                onPageDown = pageDown
            )
        },
        mine = {
            MineScreen(
                onOpenFullMode = onOpenFullMode
            )
        }
    )
}

/**
 * 无状态首页外壳 — 顶部搜索框 + 头部标题行 + 内容区 + 底部操作栏。
 *
 * 内容通过 [bookshelf] / [mine] 槽位注入，外壳只负责布局。
 */
@Composable
internal fun HomeScreen(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    headerTitle: String,
    showRefresh: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    canPageUp: Boolean,
    canPageDown: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onSearchClick: () -> Unit,
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
        //（自带底部分隔线，取代原独立分隔线）
        EInkTopBar(
            title = headerTitle,
            onBack = null,
            titleStyle = EInkTheme.typography.titleLarge,
            actions = {
                if (showRefresh) {
                    RefreshAction(
                        isRefreshing = isRefreshing,
                        onClick = onRefresh
                    )
                }
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == HomeTabs.BOOKSHELF) {
                bookshelf()
            } else {
                mine()
            }
        }
        EInkOperationBar(
            tabs = HomeTabLabels,
            selectedTabIndex = selectedTab,
            onTabSelect = onSelectTab,
            pageUpEnabled = canPageUp,
            pageDownEnabled = canPageDown,
            onPageUp = onPageUp,
            onPageDown = onPageDown
        )
    }
}

/**
 * 头部刷新按钮：标准 [EInkIconButton]，按压反色反馈由组件内置（规范 §35）。
 * 刷新中图标置灰（onSurfaceVariant），仍可点击重新发起刷新。
 */
@Composable
private fun RefreshAction(isRefreshing: Boolean, onClick: () -> Unit) {
    val colors = EInkTheme.colorScheme
    EInkIconButton(
        onClick = onClick,
        painter = painterResource(R.drawable.ic_refresh_black_24dp),
        contentDescription = "刷新",
        tint = if (isRefreshing) colors.onSurfaceVariant else colors.onSurface
    )
}
