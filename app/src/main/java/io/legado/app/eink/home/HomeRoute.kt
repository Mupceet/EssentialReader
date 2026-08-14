package io.legado.app.eink.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.bookshelf.BookshelfScreen
import io.legado.app.eink.bookshelf.BookshelfViewModel
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkOperationBar
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography
import kotlinx.coroutines.launch

/** 首页 Tab 下标。 */
internal object HomeTabs {
    const val BOOKSHELF = 0
    const val MINE = 1
}

/** 首页 Tab 文案（从左到右）。 */
private val HomeTabLabels = listOf("书架", "我的")

/**
 * 首页 Route — ViewModel 感知层。
 *
 * 结构参考微信读书墨水屏版：
 *  - 顶部固定搜索框（点击进入搜索页）；
 *  - 中间内容区：书架 / 我的 两个 Tab；
 *  - 底部通用操作栏（[EInkOperationBar]）：左侧 Tab 切换，
 *    右侧上/下箭头对书架列表整页翻页，不可翻页时置灰。
 */
@Composable
fun HomeRoute(
    onBookClick: (String) -> Unit,
    onSearch: () -> Unit,
    onBookSource: () -> Unit,
    onSettings: () -> Unit,
    viewModel: BookshelfViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 仅切换 UI 局部状态（当前 Tab），按 UDF 约定保留在 composable
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTabs.BOOKSHELF) }

    // 书架列表状态提升到首页，供底部操作栏翻页箭头驱动
    val bookshelfListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val isBookshelfTab = selectedTab == HomeTabs.BOOKSHELF
    val canPageUp by remember(isBookshelfTab) {
        derivedStateOf { isBookshelfTab && bookshelfListState.canScrollBackward }
    }
    val canPageDown by remember(isBookshelfTab) {
        derivedStateOf { isBookshelfTab && bookshelfListState.canScrollForward }
    }

    // 翻页动作：底部操作栏 ▲▼ 与列表滑动手势共用（同一行为，零动画整页跳转）
    val pageUp: () -> Unit = {
        scope.launch {
            bookshelfListState.scrollBy(-pageScrollAmount(bookshelfListState))
        }
    }
    val pageDown: () -> Unit = {
        scope.launch {
            bookshelfListState.scrollBy(pageScrollAmount(bookshelfListState))
        }
    }

    HomeScreen(
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        canPageUp = canPageUp,
        canPageDown = canPageDown,
        onPageUp = pageUp,
        onPageDown = pageDown,
        onSearchClick = onSearch,
        bookshelf = {
            BookshelfScreen(
                state = uiState,
                onBookClick = onBookClick,
                listState = bookshelfListState,
                onPageUp = pageUp,
                onPageDown = pageDown
            )
        },
        mine = {
            MineScreen(
                onBookSource = onBookSource,
                onSettings = onSettings
            )
        }
    )
}

/**
 * 无状态首页外壳 — 顶部搜索框 + 内容区 + 底部操作栏。
 *
 * 内容通过 [bookshelf] / [mine] 槽位注入，外壳只负责布局。
 */
@Composable
internal fun HomeScreen(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    canPageUp: Boolean,
    canPageDown: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onSearchClick: () -> Unit,
    bookshelf: @Composable () -> Unit,
    mine: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(eInkColorScheme().background)
    ) {
        SearchHintBar(onClick = onSearchClick)
        EInkHorizontalDivider()
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
 * 顶部固定搜索框（提示样式，点击进入搜索页）。
 */
@Composable
private fun SearchHintBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchBarHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SearchInputHeight)
                .border(
                    width = 1.dp,
                    color = eInkColorScheme().outline,
                    shape = EInkShapes.small
                )
                .padding(horizontal = EInkSpacing.m),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "搜索书名 / 作者",
                style = eInkTypography().bodyMedium,
                color = eInkColorScheme().onSurfaceVariant
            )
        }
    }
}

/**
 * 一页的滚动距离 = 列表视口高度（点击时实时读取）。
 */
private fun pageScrollAmount(listState: LazyListState): Float {
    val info = listState.layoutInfo
    return (info.viewportEndOffset - info.viewportStartOffset).toFloat()
}

private val SearchBarHeight = 64.dp
private val SearchInputHeight = 44.dp
