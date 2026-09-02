package io.legado.app.eink.feature.changesource

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.ChangeSourceResultUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.launch

/** 当前源左侧实心标记尺寸（▮，规范 §42 列表行持久选中，同目录页“在读”标记）。 */
private val CurrentMarkWidth = 4.dp
private val CurrentMarkHeight = 16.dp

/**
 * 换源 Route — ViewModel 感知层。
 *
 * 骨架对齐搜索页（固定页分页 + 底部操作条）：
 *  - 顶栏动作区为刷新/中止图标按钮（对齐主项目换源弹层 startOrStopSearch），
 *    搜索中图标切换为中止；进度以顶栏被动文本呈现，不再作为按钮；
 *  - 结果列表固定页分页（[rememberEInkListPagerState]），
 *    上下滑动手势与底部 ▲▼ 翻页同一动作；
 *  - 底部操作栏：左侧返回按钮，右侧上下翻页箭头。
 */
@Composable
fun ChangeSourceRoute(
    bookUrl: String,
    onBack: () -> Unit,
    viewModel: ChangeSourceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(bookUrl) {
        viewModel.load(bookUrl)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg.format(context), Toast.LENGTH_SHORT).show()
        }
    }

    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val totalItems = uiState.results.size

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

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），在 Route 作用域读取会让整个换源页随每次翻页重组；
    // 收敛到槽内读取，翻页只重组箭头两个图标
    val pageArrows: @Composable () -> Unit = {
        EInkPageArrows(
            pageUpEnabled = pager.canPageUp(),
            pageDownEnabled = pager.canPageDown(totalItems),
            onPageUp = pageUp,
            onPageDown = pageDown
        )
    }

    // 顶栏刷新/中止按钮：重新发起搜索时列表会被清空，只重置分页计数，
    // 不在数据切换期滚动（scrollToItem 会与测量竞争，同搜索页 triggerSearch）
    val onRefreshToggle: () -> Unit = {
        if (!uiState.isSearching) {
            pager.resetPaging()
        }
        viewModel.startOrStopSearch()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(
            title = "换源 - ${uiState.book?.name ?: ""}",
            actionsFillMax = true,
            actions = {
                // 搜索进度为被动文本（非按钮），中止态时让位给图标按钮
                if (uiState.isSearching) {
                    EInkText(
                        text = "搜索中 ${uiState.searchedCount}/${uiState.totalSourceCount}",
                        style = EInkTheme.typography.labelMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = EInkSpacing.s),
                    )
                }
                EInkOperationBarIcon(
                    icon = painterResource(
                        if (uiState.isSearching) R.drawable.eink_ic_stop_circle
                        else R.drawable.eink_ic_refresh_black_24dp
                    ),
                    contentDescription = if (uiState.isSearching) "中止搜索" else "重新搜索",
                    onClick = onRefreshToggle,
                )
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            ChangeSourceScreen(
                state = uiState,
                pagerListState = pager.listState,
                onPageUp = pageUp,
                onPageDown = pageDown,
                onPick = { searchBook ->
                    viewModel.changeTo(searchBook, onBack)
                }
            )
            if (uiState.isChanging) {
                // 不透明 surface 遮盖列表（同目录页初始定位遮盖），
                // 避免“正在换源”文字与列表重叠
                EInkLoading(
                    text = "正在换源…",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EInkTheme.colorScheme.surface),
                )
            }
        }
        // 底部操作栏：返回 居左 + 翻页胶囊（与其它界面统一的 EInkOperationBar）
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

/**
 * 无状态换源 Screen。
 */
@Composable
internal fun ChangeSourceScreen(
    state: ChangeSourceUiState,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onPick: (ChangeSourceResultUiModel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.error != null -> CenterMessage(state.error)
            state.isSearching && state.results.isEmpty() -> {
                EInkLoading(
                    text = "正在搜索书源…",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.isEmpty -> CenterMessage("未找到其它书源")
            else -> SourceList(
                state = state,
                pagerListState = pagerListState,
                onPageUp = onPageUp,
                onPageDown = onPageDown,
                onPick = onPick,
            )
        }
    }
}

/**
 * 结果列表（固定页分页 + 手势整页翻页）。
 */
@Composable
private fun SourceList(
    state: ChangeSourceUiState,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onPick: (ChangeSourceResultUiModel) -> Unit,
) {
    val currentOrigin = state.book?.origin

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
        items(state.results, key = { it.primary }) { searchBook ->
            SourceItem(
                searchBook = searchBook,
                isCurrent = searchBook.origin == currentOrigin,
                onClick = { onPick(searchBook) }
            )
            EInkHorizontalDivider()
        }
    }
}

/**
 * 书源条目：按压瞬时反色（规范 §35）。
 *
 * 当前书源为长列表持久选中态：不整行反色（大面积持久反色退出时残影重，
 * 规范 §42），改用左侧实心标记 + 名称加粗 + “当前源”标签（additive inking）。
 * 内容为 书源名称 / 最新章节 / 作者（对齐主项目换源列表字段）。
 */
@Composable
private fun SourceItem(
    searchBook: ChangeSourceResultUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    // 标记/标签随按压反色；名称：当前源加深，其余为次级色；
    // 信息行（最新章节/作者）同步反色，避免深色底上仍是深灰字
    val markColor = if (press.isPressed) scheme.surface else scheme.onSurface
    val titleColor = if (press.isPressed) colors.contentColor else scheme.onSurface
    val infoColor = if (press.isPressed) colors.contentColor else scheme.onSurfaceVariant
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
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
            ) {
                EInkText(
                    text = searchBook.originName.ifBlank { searchBook.origin },
                    modifier = Modifier.weight(1f),
                    style = EInkTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else null,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    EInkText(
                        text = "当前源",
                        style = EInkTheme.typography.labelMedium,
                        color = markColor,
                    )
                }
            }
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_book_last,
                text = searchBook.latestChapter?.takeIf { it.isNotBlank() } ?: "无最新章节",
                style = EInkTheme.typography.bodySmall,
                contentColor = infoColor,
            )
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_author,
                text = searchBook.author.ifBlank { "佚名" },
                style = EInkTheme.typography.bodySmall,
                contentColor = infoColor,
            )
        }
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EInkText(text = message, style = EInkTheme.typography.bodyLarge)
    }
}
