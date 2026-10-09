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
import io.legado.app.eink.app.EInkPagerInput
import io.legado.app.eink.contract.ChangeSourceBookUiModel
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
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.launch

/** 当前源左侧实心标记尺寸（▮，规范 §42 列表行持久选中）。高度与行内
 *  文字块三行同高（26+18+18=62dp，bodyLarge 名称 + 两行 bodySmall 信息
 *  行、行高由 18dp 图标撑起），同字体选择页 66dp 修正先例：16dp 是
 *  单行时代尺寸，三行行内只占中间一小截，宽 4dp 不变。 */
private val CurrentMarkWidth = 4.dp
private val CurrentMarkHeight = 62.dp

/** 常驻首项「当前源」行的 LazyColumn key（deduplicationKey 为书源维度
 *  组合值，不会再以此字面量出现，不会撞 key）。 */
private const val CurrentItemKey = "eink_change_source_current"

/**
 * 换源 Route — ViewModel 感知层。
 *
 * 骨架对齐搜索页（固定页分页 + 底部操作条）：
 *  - 顶栏动作区为刷新/中止图标按钮（对齐主项目换源弹层 startOrStopSearch），
 *    搜索中图标切换为中止；进度以顶栏被动文本呈现，不再作为按钮；
 *  - 结果列表固定页分页（[rememberEInkListPagerState]），
 *    上下滑动手势与底部 ▲▼ 翻页同一动作；当前源常驻首项计入分页总数；
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
    // 当前源常驻首项与其它源结果一起计入分页总数（pager 按项下标翻页）
    val totalItems = uiState.results.size + if (uiState.current != null) 1 else 0

    // 翻页动作 remember 稳定实例：下传后接收方（列表 / 分页输入）
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

    // 顶栏刷新/中止按钮：重新搜索时列表会被清空，只重置分页计数，
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
                },
                onCurrentClick = viewModel::selectCurrentSource,
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
 *
 * 当前源常驻首项（进入即见，重搜不清空）：搜索期不再有全屏“正在搜索
 * 书源…”遮盖——顶栏已有搜索进度文本，遮盖反而藏掉首项当前源；无其它
 * 源结果时当前源行保留，说明文案居中补位。
 */
@Composable
internal fun ChangeSourceScreen(
    state: ChangeSourceUiState,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onPick: (ChangeSourceResultUiModel) -> Unit,
    onCurrentClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.error != null -> CenterMessage(state.error)
            state.isEmpty -> EmptyWithCurrent(
                current = state.current,
                onCurrentClick = onCurrentClick,
            )
            else -> SourceList(
                state = state,
                pagerListState = pagerListState,
                onPageUp = onPageUp,
                onPageDown = onPageDown,
                onPick = onPick,
                onCurrentClick = onCurrentClick,
            )
        }
    }
}

/**
 * 无其它书源结果：常驻当前源行 + 居中说明（说明不顶掉当前源行）。
 */
@Composable
private fun EmptyWithCurrent(
    current: ChangeSourceBookUiModel?,
    onCurrentClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (current != null) {
            CurrentSourceItem(current = current, onClick = onCurrentClick)
            EInkHorizontalDivider()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(text = "未找到其它书源", style = EInkTheme.typography.bodyLarge)
        }
    }
}

/**
 * 结果列表（固定页分页 + 手势整页翻页）。首项为常驻当前源行。
 */
@Composable
private fun SourceList(
    state: ChangeSourceUiState,
    pagerListState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onPick: (ChangeSourceResultUiModel) -> Unit,
    onCurrentClick: () -> Unit,
) {
    LazyColumn(
        state = pagerListState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPagerInput(
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
    ) {
        state.current?.let { current ->
            item(key = CurrentItemKey) {
                CurrentSourceItem(current = current, onClick = onCurrentClick)
                EInkHorizontalDivider()
            }
        }
        items(state.results, key = { it.deduplicationKey }) { searchBook ->
            SourceItem(
                searchBook = searchBook,
                onClick = { onPick(searchBook) }
            )
            EInkHorizontalDivider()
        }
    }
}

/**
 * 结果条目（其它书源）：按压瞬时反色（规范 §35）。
 */
@Composable
private fun SourceItem(
    searchBook: ChangeSourceResultUiModel,
    onClick: () -> Unit,
) {
    SourceRow(
        title = searchBook.originName.ifBlank { searchBook.origin },
        latestChapter = searchBook.latestChapter,
        author = searchBook.author,
        isCurrent = false,
        onClick = onClick,
    )
}

/**
 * 常驻首项「当前源」条目：与结果行同布局，持久选中态；点击不换源
 * （换到当前源等于换到原书），仅提示（见 VM selectCurrentSource）。
 */
@Composable
private fun CurrentSourceItem(
    current: ChangeSourceBookUiModel,
    onClick: () -> Unit,
) {
    SourceRow(
        title = current.originName.ifBlank { current.origin },
        latestChapter = current.latestChapterTitle,
        author = current.author,
        isCurrent = true,
        onClick = onClick,
    )
}

/**
 * 书源行：按压瞬时反色（规范 §35）。
 *
 * 当前源为长列表持久选中态：不整行反色（大面积持久反色退出时残影重，
 * 规范 §42），改用左侧实心标记 + 名称加粗 + “当前源”标签（additive inking）。
 * 内容为 书源名称 / 最新章节 / 作者（对齐主项目换源列表字段）。
 */
@Composable
private fun SourceRow(
    title: String,
    latestChapter: String?,
    author: String,
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
                    text = title,
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
                text = latestChapter?.takeIf { it.isNotBlank() } ?: "无最新章节",
                style = EInkTheme.typography.bodySmall,
                contentColor = infoColor,
            )
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_author,
                text = author.ifBlank { "佚名" },
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
