package io.legado.app.eink.feature.bookdetail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrowsWidth
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.navigation.OperationBarIconButtonMaxWidth
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 详情页封面宽高比。 */
private const val CoverAspectRatio = 0.75f

/** 跨页重叠量：翻页步进 = 视口高 - 该值，保证跨页处的文字在上下两页都完整可见。 */
private val PageOverlap = 56.dp

/** 底栏槽位最小宽度（触控目标下限，与 EInkOperationBarIcon 的自适应注释一致）。 */
private val CompactBottomSlotMinWidth = 48.dp

/**
 * 书籍详情 Route — ViewModel 感知层。
 *
 * 布局：顶部操作条（书名动态显隐——翻到简介区后显示，首页不显示；
 * 加入/移出书架、切换书源图标按钮居右连续排列、贴右屏，参考阅读页
 * ReaderTopBar）；内容区 封面（上 1/2 视口，等比居中）→ 基础信息
 * （横向居中）→ 简介（总高至少一屏，翻页到底时整屏均为简介）；
 * 底部操作栏 返回 / 目录 / 阅读 居左连续 + 翻页胶囊。
 *
 * 多设备布局：屏宽足以让底栏容纳全部 7 个功能（返回/目录/阅读/加书架/
 * 换源/上翻/下翻，5 个图标槽均分「屏宽 - 胶囊 - 右缘距」≥ 48dp）时，
 * 加入/移除书架与换源并入底栏、顶栏整体移除；否则维持上述原布局。
 *
 * 翻页：本页非列表，采用接近全屏的步进（视口高 - 重叠量），禁止自由滑动，
 * 底部 ▲▼ 与上下滑动手势统一触发同一翻页动作；跨页重叠保证文字不被截断。
 */
@Composable
fun BookDetailRoute(
    name: String,
    author: String,
    bookUrl: String,
    onBack: () -> Unit,
    onOpenToc: (String) -> Unit,
    onRead: (String) -> Unit,
    onChangeSource: (String) -> Unit,
    viewModel: BookDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 移出书架二次确认（eink 误触防护）：入口按钮只打开确认框，确认后才
    // 真正执行移出（与阅读页弹窗一致，可见性为本层 remember 局部状态）
    var showRemoveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(name, author, bookUrl) {
        viewModel.loadBook(name, author, bookUrl)
    }
    // 一次性消息 → Toast
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg.format(context), Toast.LENGTH_SHORT).show()
        }
    }

    val effectiveBookUrl = uiState.book?.bookUrl ?: bookUrl
    BookDetailScreen(
        state = uiState,
        onBack = onBack,
        onAddToShelf = viewModel::addToBookshelf,
        onRemoveFromShelf = { showRemoveConfirm = true },
        onOpenToc = { onOpenToc(effectiveBookUrl) },
        onRead = { onRead(effectiveBookUrl) },
        // 换源后本页经 bookChanged 事件跟随刷新，effectiveBookUrl 即新源地址
        onChangeSource = { onChangeSource(effectiveBookUrl) },
    )

    if (showRemoveConfirm) {
        EInkDialog(
            onDismiss = { showRemoveConfirm = false },
            title = "移出书架",
            onConfirm = {
                showRemoveConfirm = false
                viewModel.removeFromBookshelf()
            },
        ) {
            EInkText(
                text = "确定要将《${uiState.book?.name.orEmpty()}》移出书架吗？",
                style = EInkTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 无状态书籍详情 Screen — 纯渲染。
 */
@Composable
internal fun BookDetailScreen(
    state: BookDetailUiState,
    onBack: () -> Unit,
    onAddToShelf: () -> Unit,
    onRemoveFromShelf: () -> Unit,
    onOpenToc: () -> Unit,
    onRead: () -> Unit,
    onChangeSource: () -> Unit,
) {
    // 分页：接近全屏步进 + 跨页重叠；禁止自由滑动，按钮与滑动手势统一翻页。
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val overlapPx = with(density) { PageOverlap.toPx() }.roundToInt()
    val pageStep = (viewportHeightPx - overlapPx).coerceAtLeast(1)

    val canPageUp = scrollState.value > 0
    val canPageDown = viewportHeightPx > 0 && scrollState.value < scrollState.maxValue
    // 翻页后上报 PageTurn 意图（规范 §26/§40）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = {
        scope.launch { scrollState.scrollTo((scrollState.value - pageStep).coerceAtLeast(0)) }
        refresh.requestRefresh(EInkRefreshIntent.PageTurn)
    }
    val pageDown: () -> Unit = {
        scope.launch { scrollState.scrollTo((scrollState.value + pageStep).coerceAtMost(scrollState.maxValue)) }
        refresh.requestRefresh(EInkRefreshIntent.PageTurn)
    }
    // 顶栏书名动态显隐：首页（封面 + 基础信息，界面已有书名）不显示；
    // 翻页进入简介区（书名滚出视口）后显示。翻页为整页跳转（无自由滑动），
    // 滚动值只在 0 与翻页落点间切换，故以"不在顶部"判断；
    // 落点可能被 maxValue 截短，不能用 pageStep 作阈值
    val showTitleInBar = scrollState.value > 0

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        // 多设备布局：底栏 7 功能（返回/目录/阅读/加书架/换源/上翻/下翻）需要
        // 5 个图标槽 + 翻页胶囊。5 槽均分「布局宽 - 胶囊宽 - 底栏右缘距」不低于
        // 48dp 触控目标时启用紧凑底栏（加书架/换源并入底栏、整体移除顶栏），
        // 否则维持 3 槽底栏 + 顶栏动作按钮的原布局。槽宽封顶与图标组件的
        // 收敛上限同源，宽屏上不留过高过宽的槽。
        // 宽度取 BoxWithConstraints 实测约束而非 Configuration.screenWidthDp：
        // 入口 Activity 声明了 configChanges（含 orientation|screenSize），旋转
        // 不重建 Activity，Configuration 可能滞后于实际窗口——旋转后以旧宽
        // 排布会溢出，实测约束随布局即时更新
        val screenWidth = maxWidth
        val compactSlotWidth = ((screenWidth - EInkPageArrowsWidth - EInkSpacing.m) / 5)
            .coerceAtMost(OperationBarIconButtonMaxWidth)
        val useCompactBottomBar = compactSlotWidth >= CompactBottomSlotMinWidth
        // 紧凑模式下各槽显式等宽；常规模式传 null 回落组件自身的自适应规则
        val bottomSlotWidth: Dp? = if (useCompactBottomBar) compactSlotWidth else null

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部操作条（仅非紧凑布局）：书名动态显隐（翻到简介区后显示），
            // 加入/移出书架、切换书源动作按钮贴右屏；紧凑布局全部功能并入
            // 底栏，顶栏整体移除
            if (!useCompactBottomBar) {
                EInkTopBar(
                    title = if (showTitleInBar) state.book?.name.orEmpty() else "",
                    actionsFillMax = true,
                    actions = {
                        if (state.book != null) {
                            EInkOperationBarIcon(
                                icon = painterResource(
                                    // 书页 + 加/减号图标对（源：ic_jiarushujia.svg 及其派生）：
                                    // 未加架 = 加号，已在书架 = 减号（点击移出）
                                    if (state.isInBookshelf) R.drawable.eink_ic_book_remove
                                    else R.drawable.eink_ic_book_add
                                ),
                                contentDescription = if (state.isInBookshelf) "移出书架" else "加入书架",
                                onClick = if (state.isInBookshelf) onRemoveFromShelf else onAddToShelf
                            )
                            EInkOperationBarIcon(
                                icon = painterResource(R.drawable.eink_ic_exchange),
                                contentDescription = "切换书源",
                                onClick = onChangeSource
                            )
                        }
                    }
                )
            }
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        EInkLoading(textStyle = EInkTheme.typography.titleLarge)
                    }
                }

                state.isEmpty -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EInkText(
                            text = stringResource(R.string.eink_book_not_found),
                            style = EInkTheme.typography.bodyLarge
                        )
                    }
                }

                else -> {
                    // ?: return 在非 inline 的 content lambda 中不合法，
                    // 以 lambda 标签提前结束本层组合（book 为空属防御分支）
                    val book = state.book ?: return@BoxWithConstraints
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .onSizeChanged { viewportHeightPx = it.height }
                            .EInkPageSwipe(onPageUp = pageUp, onPageDown = pageDown)
                    ) {
                        // 内容三段：封面（上 1/2 视口）→ 基础信息（横向居中）→
                        // 简介（总高至少一屏，翻页到底时整屏均为简介）
                        val viewportHeight = maxHeight
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState, enabled = false)
                        ) {
                            // 封面
                            val coverAreaHeight = viewportHeight * 0.65f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(coverAreaHeight)
                                    .padding(EInkSpacing.m),
                                contentAlignment = Alignment.Center
                            ) {
                                val coverHeight = coverAreaHeight - EInkSpacing.m * 2
                                val coverWidth = coverHeight * CoverAspectRatio
                                EInkBookCover(
                                    url = book.displayCover,
                                    name = book.name,
                                    author = book.displayAuthor,
                                    sourceOrigin = book.origin,
                                    modifier = Modifier
                                        .width(coverWidth)
                                        .height(coverHeight),
                                    width = coverWidth,
                                    height = coverHeight
                                )
                            }
                            // 基础信息：横向居中
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 书名
                                EInkText(
                                    text = book.name,
                                    style = EInkTheme.typography.headlineMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // 作者
                                EInkText(
                                    text = book.displayAuthor,
                                    style = EInkTheme.typography.labelLarge,
                                    color = EInkTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = EInkSpacing.xs)
                                )

                                // 最新章节 + 当前进度章节：两行左对齐、整体居中
                                ChapterRows(
                                    latestChapterTitle = book.latestChapterTitle,
                                    currentChapterTitle = book.currentChapterTitle
                                )
                            }
                            // 简介：区域总高度至少一屏（heightIn 在 padding 外层）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = viewportHeight)
                                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m)
                            ) {
                                EInkText(
                                    text = book.displayIntro?.takeIf { it.isNotBlank() }
                                        ?: "暂无简介",
                                    style = EInkTheme.typography.bodyLarge,
                                    color = EInkTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = EInkSpacing.s)
                                )
                            }
                        }
                    }
                }
            }
            // 底部操作栏：返回 / 目录 / 阅读 居左连续 + 翻页胶囊（按钮与滑动手势统一）。
            // 紧凑布局（宽度足够）追加 加入/移出书架、切换书源，并显式等分槽宽
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
                navigationIcon = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_arrow_back),
                        contentDescription = "返回",
                        width = bottomSlotWidth,
                        onClick = onBack
                    )
                },
                actions = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_toc),
                        contentDescription = "目录",
                        width = bottomSlotWidth,
                        onClick = onOpenToc
                    )
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_play_outline_24dp),
                        contentDescription = "阅读",
                        width = bottomSlotWidth,
                        onClick = onRead
                    )
                    if (useCompactBottomBar && state.book != null) {
                        EInkOperationBarIcon(
                            icon = painterResource(
                                if (state.isInBookshelf) R.drawable.eink_ic_book_remove
                                else R.drawable.eink_ic_book_add
                            ),
                            contentDescription = if (state.isInBookshelf) "移出书架" else "加入书架",
                            width = bottomSlotWidth,
                            onClick = if (state.isInBookshelf) onRemoveFromShelf else onAddToShelf
                        )
                        EInkOperationBarIcon(
                            icon = painterResource(R.drawable.eink_ic_exchange),
                            contentDescription = "切换书源",
                            width = bottomSlotWidth,
                            onClick = onChangeSource
                        )
                    }
                },
                pageUpEnabled = canPageUp,
                pageDownEnabled = canPageDown,
                onPageUp = pageUp,
                onPageDown = pageDown
            )
        }
    }
}

/**
 * 章节信息组件：最新章节 + 当前进度章节两行。行内左对齐（两行图标与
 * 文字左边缘对齐），组件宽度随最长行收缩、在父容器中整体居中，
 * 避免逐行各自居中时长短参差。标题为空/空白时跳过对应行。
 */
@Composable
private fun ChapterRows(
    latestChapterTitle: String?,
    currentChapterTitle: String?
) {
    Column(horizontalAlignment = Alignment.Start) {
        latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_book_last,
                text = it,
                style = EInkTheme.typography.labelSmall,
                modifier = Modifier.padding(top = EInkSpacing.s)
            )
        }
        currentChapterTitle?.takeIf { it.isNotBlank() }?.let {
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_history,
                text = it,
                style = EInkTheme.typography.labelSmall,
                modifier = Modifier.padding(top = EInkSpacing.s)
            )
        }
    }
}

