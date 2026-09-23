package io.legado.app.eink.feature.bookdetail

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.BookDetailUiModel
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrowsWidth
import io.legado.app.eink.designsystem.navigation.OperationBarIconButtonMaxWidth
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 详情页封面宽高比。 */
private const val CoverAspectRatio = 0.75f

/** 封面页封面区目标高度占内容区视口的比例（旧版固定比例，观感基准）。 */
private const val CoverAreaFraction = 0.65f

/** 跨页重叠量：简介翻页步进 = 简介区高 - 该值，保证跨页处的文字在上下两页都完整可见。 */
private val PageOverlap = 56.dp

/** 底栏槽位最小宽度（触控目标下限，与 EInkOperationBarIcon 的自适应注释一致）。 */
private val CompactBottomSlotMinWidth = 48.dp

/** 信息区动作按钮（目录/换源）高度：最小样式（用户决策 2026-09-22）。 */
private val DetailActionButtonHeight = 32.dp

/** 信息区动作按钮图标尺寸：与 32dp 高的最小样式配套。 */
private val DetailActionButtonIconSize = 16.dp

/**
 * 书籍详情 Route — ViewModel 感知层。
 *
 * 布局：无顶栏。内容为单列连续滚动流——封面区（靠顶，约 0.65 视口，
 * 封面区内居中）→ 基础信息 → 简介（至少一屏）；底部操作栏 返回 /
 * 阅读 / 加架(移出) / 目录 / 换源 居左连续 + 翻页胶囊。
 *
 * 多设备布局：屏宽足以让底栏容纳全部 7 个功能（返回/阅读/加书架/目录/
 * 换源/上翻/下翻，5 个图标槽均分「屏宽 - 胶囊 - 右缘距」≥ 48dp）时，
 * 目录与换源并入底栏；否则底栏只留 返回/阅读/加书架，目录与换源以
 * 「图标 + 文字」按钮横向排布在作者信息区（章节行）之下。
 *
 * 翻页（整页跳转，禁止自由滑动）：第一次下翻跳到「封面移出」锚点——
 * 基础信息对齐视口顶，简介紧随其后；之后按整页步进（视口高 - 重叠量）
 * 继续翻，跨页重叠保证文字不被截断。底部 ▲▼ 与上下滑动手势统一触发
 * 同一翻页动作。
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
    // 单列连续滚动：封面（靠顶）→ 基础信息 → 简介为同一滚动流，
    // 单列连续滚动：封面（靠顶）→ 信息区 → 简介为同一滚动流，
    // 禁止自由滑动，按钮与滑动手势统一整页跳转。
    // 翻页锚点：第一次下翻直接跳到「封面移出」位置（信息区对齐视口顶）；
    // 之后信息区钉住视口顶，简介按整页步进（视口高 - 重叠量）继续翻，
    // 简介不足一页时锚点即最后一页。
    // 各尺寸/滚动位置用 saveable：进出换源/目录页返回时 Route 重建，
    // remember 会重置——视口高归零会让封面区经历「0 高 → 撑开」的
    // 从无到有跳动，滚动位置也随用户翻页处丢失
    val detailScrollState = rememberSaveable(saver = ScrollState.Saver) {
        ScrollState(0)
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var detailViewportHeightPx by rememberSaveable { mutableIntStateOf(0) }
    // 信息区/简介高参与尾部垫高计算（垫高在简介之后，首帧 0 不影响
    // 首屏布局，次帧就绪只影响翻页可用性）
    var infoHeightPx by rememberSaveable { mutableIntStateOf(0) }
    var introHeightPx by rememberSaveable { mutableIntStateOf(0) }
    val overlapPx = with(density) { PageOverlap.toPx() }.roundToInt()
    // 封面锚点 = 封面区占视口比例的直接推导（与封面区 height 的取整链
    // 逐位一致），不依赖帧末测量回传——首帧即正确，副本不会闪现在视口顶
    val coverAnchorPx =
        (detailViewportHeightPx * CoverAreaFraction).roundToInt()
    val detailStep = (detailViewportHeightPx - overlapPx).coerceAtLeast(1)
    // 尾部垫高（DetailContent 的 Spacer）：保证「信息区之后的内容 ≥ 一屏」
    // （锚点可达、封面能整块移出）且 ≥ 信息区高（钉顶不被列表末尾顶出、
    // 简介尾部可达）。简介不足时它就是锚点后的空白，不再产生多余翻页。
    val tailSpacerPx = maxOf(
        infoHeightPx,
        detailViewportHeightPx - infoHeightPx - introHeightPx
    ).coerceAtLeast(0)
    // 最后一个翻页落点：留出垫高的空白，翻完恰好停在简介尾部
    val lastTargetPx =
        (detailScrollState.maxValue - tailSpacerPx).coerceAtLeast(0)

    // 书籍未就绪（加载中/未找到）时两向都禁用，避免空内容翻页
    val canPageUp = state.book != null && detailScrollState.value > 0
    val canPageDown = state.book != null && detailScrollState.value <
        maxOf(coverAnchorPx, lastTargetPx)
    // 翻页后上报 PageTurn 意图（规范 §26/§40）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = {
        if (canPageUp) {
            scope.launch {
                // 锚点页上翻 → 回封面（封面顶部对齐视口顶）；锚点之后
                // 逐页回翻简介，落点钳到锚点——末落点与锚点间距不足一
                // 整步（末页被钳到简介结尾）时也不会一步跨回封面、
                // 跳过简介首页
                detailScrollState.scrollTo(
                    if (detailScrollState.value <= coverAnchorPx) {
                        0
                    } else {
                        (detailScrollState.value - detailStep)
                            .coerceAtLeast(coverAnchorPx)
                    }
                )
            }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = {
        if (canPageDown) {
            scope.launch {
                // 未越过封面锚点：跳到信息区顶部（封面移出）；
                // 已越过：整页步进，落点钳到最后翻页位（maxValue 由
                // scrollTo 自身钳制）
                val target = if (detailScrollState.value < coverAnchorPx) {
                    coverAnchorPx
                } else {
                    (detailScrollState.value + detailStep)
                        .coerceAtMost(lastTargetPx)
                }
                detailScrollState.scrollTo(target)
            }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 切换到另一本书（换源重建/长按另一本进详情复用 VM）时回到第一页；
    // 同书静默刷新（从阅读/目录页返回）保持当前翻页位置
    var pagedBookUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.book?.bookUrl) {
        val url = state.book?.bookUrl ?: return@LaunchedEffect
        if (pagedBookUrl != null && pagedBookUrl != url) {
            detailScrollState.scrollTo(0)
        }
        pagedBookUrl = url
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        // 多设备布局：底栏 7 功能（返回/阅读/加书架/目录/换源/上翻/下翻）需要
        // 5 个图标槽 + 翻页胶囊。5 槽均分「布局宽 - 胶囊宽 - 底栏右缘距」不低于
        // 48dp 触控目标时目录与换源并入底栏，否则它们移入信息区按钮行。
        // 槽宽封顶与图标组件的收敛上限同源，宽屏上不留过高过宽的槽。
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
            // 内容区整体接管翻页手势；实测高即翻页视口（锚点/步进基准）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { detailViewportHeightPx = it.height }
                    .EInkPageSwipe(onPageUp = pageUp, onPageDown = pageDown)
            ) {
                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EInkLoading(textStyle = EInkTheme.typography.titleLarge)
                        }
                    }

                    state.isEmpty -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        DetailContent(
                            book = book,
                            viewportHeight = with(density) {
                                detailViewportHeightPx.toDp()
                            },
                            tailSpacerHeight = with(density) {
                                tailSpacerPx.toDp()
                            },
                            showInfoActions = !useCompactBottomBar,
                            onOpenToc = onOpenToc,
                            onChangeSource = onChangeSource,
                            scrollState = detailScrollState,
                            coverAnchorPx = coverAnchorPx,
                            onInfoHeightChanged = { infoHeightPx = it },
                            onIntroHeightChanged = { introHeightPx = it }
                        )
                    }
                }
            }
            // 底部操作栏：返回 / 阅读 / 加架(移出) /（紧凑）目录 / 换源
            // 居左连续 + 翻页胶囊（按钮与滑动手势统一）
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
                        icon = painterResource(R.drawable.eink_ic_play_outline_24dp),
                        contentDescription = "阅读",
                        width = bottomSlotWidth,
                        onClick = onRead
                    )
                    if (state.book != null) {
                        EInkOperationBarIcon(
                            icon = painterResource(
                                // 书页 + 加/减号图标对（源：ic_jiarushujia.svg 及其派生）：
                                // 未加架 = 加号，已在书架 = 减号（点击移出）
                                if (state.isInBookshelf) R.drawable.eink_ic_book_remove
                                else R.drawable.eink_ic_book_add
                            ),
                            contentDescription = if (state.isInBookshelf) "移出书架" else "加入书架",
                            width = bottomSlotWidth,
                            onClick = if (state.isInBookshelf) onRemoveFromShelf else onAddToShelf
                        )
                        if (useCompactBottomBar) {
                            EInkOperationBarIcon(
                                icon = painterResource(R.drawable.eink_ic_toc),
                                contentDescription = "目录",
                                width = bottomSlotWidth,
                                onClick = onOpenToc
                            )
                            EInkOperationBarIcon(
                                icon = painterResource(R.drawable.eink_ic_exchange),
                                contentDescription = "切换书源",
                                width = bottomSlotWidth,
                                onClick = onChangeSource
                            )
                        }
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
 * 详情内容（单列连续滚动流 + 信息区钉顶）：
 *
 * 滚动列：封面区（靠顶，占视口 [CoverAreaFraction]，封面在区内等比居中）
 * → 信息区本尊 → 简介 → 尾部垫高（见 [tailSpacerHeight]）。
 * 信息区高度由内容自撑（不依赖测量回传），首次进入首帧布局即稳定，
 * 不出现「占位 0 高 → 撑开」的跳动。
 *
 * 钉顶：同内容副本画在覆盖层，位置公式与流内本尊的视口投影恒等
 * （锚点 - 滚动值），两者任何时刻重合——未过锚点时副本盖在本尊上
 * （视觉一份）；滚动越过锚点后本尊随流滚出，副本钉在视口顶，简介从
 * 其下滚过，无论翻多少页书名/作者始终可见。副本高度实测仅用于
 * 尾部垫高计算。滚动关闭手势，仅 ▲▼/滑动经锚点/步进驱动。
 */
@Composable
private fun DetailContent(
    book: BookDetailUiModel,
    viewportHeight: Dp,
    tailSpacerHeight: Dp,
    showInfoActions: Boolean,
    onOpenToc: () -> Unit,
    onChangeSource: () -> Unit,
    scrollState: ScrollState,
    coverAnchorPx: Int,
    onInfoHeightChanged: (Int) -> Unit,
    onIntroHeightChanged: (Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = false)
        ) {
            // 封面区：固定高度占位（高度取视口比例直接推导，与翻页锚点
            // 同源），封面等比居中
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewportHeight * CoverAreaFraction)
                    .padding(EInkSpacing.m)
            ) {
                // BoxWithConstraints 默认 TopStart，须显式居中，否则封面贴区左上角；
                // 高度约束来自外层固定 height（滚动列子项约束为无穷大，不能在
                // 滚动列根上取视口高——视口高经 [viewportHeight] 传入）
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val coverHeight = maxHeight
                    val coverWidth = minOf(
                        coverHeight * CoverAspectRatio,
                        maxWidth
                    )
                    // 视口高经 onSizeChanged 状态传入，首帧组合时可能为 0：
                    // 尺寸未就绪跳过封面（Coil 请求拒绝 0px），次帧上报后
                    // 重组补上
                    if (coverWidth > 0.dp) {
                        val resolvedHeight = coverWidth / CoverAspectRatio
                        EInkBookCover(
                            url = book.displayCover,
                            name = book.name,
                            author = book.displayAuthor,
                            sourceOrigin = book.origin,
                            modifier = Modifier
                                .width(coverWidth)
                                .height(resolvedHeight),
                            width = coverWidth,
                            height = resolvedHeight
                        )
                    }
                }
            }
            // 信息区本尊：高度由内容自撑（首帧即稳定）
            BookInfoBlock(
                book = book,
                showActions = showInfoActions,
                onOpenToc = onOpenToc,
                onChangeSource = onChangeSource
            )
            // 简介
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m)
                    .onSizeChanged { onIntroHeightChanged(it.height) }
            ) {
                EInkText(
                    text = book.displayIntro?.takeIf { it.isNotBlank() }
                        ?: "暂无简介",
                    style = EInkTheme.typography.bodyLarge,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = EInkSpacing.s)
                )
            }
            // 尾部垫高（Screen 层按「信息区+简介 ≥ 一屏 且 ≥ 信息区高」算出）
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tailSpacerHeight)
            )
        }
        // 钉顶副本（覆盖层，后声明者画在上层）：位置公式「锚点 - 滚动值」
        // 与本尊的视口投影恒等、任何时刻重合——副本不透明背景盖住本尊，
        // 视觉始终一份；过锚点后本尊滚出、副本钉顶，背景遮住滚过的简介
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (coverAnchorPx - scrollState.value).coerceAtLeast(0)) }
                .background(EInkTheme.colorScheme.background)
                .onSizeChanged { onInfoHeightChanged(it.height) }
        ) {
            BookInfoBlock(
                book = book,
                showActions = showInfoActions,
                onOpenToc = onOpenToc,
                onChangeSource = onChangeSource
            )
        }
    }
}

/**
 * 基础信息区：书名 → 作者（均居中）→ 章节两行（最新/在读，组件整体
 * 居中）→（非紧凑）目录/换源最小按钮行（宽度随内容收缩、整行居中，
 * 两侧留白）。作为详情滚动页的头部，随滚动流整体翻页。
 */
@Composable
private fun BookInfoBlock(
    book: BookDetailUiModel,
    showActions: Boolean,
    onOpenToc: () -> Unit,
    onChangeSource: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 书名
        EInkText(
            text = book.name,
            style = EInkTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        // 作者
        EInkText(
            text = book.displayAuthor,
            style = EInkTheme.typography.titleSmall,
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

        // 非紧凑布局：目录/换源不进底栏。最小号「图标 + 文字」按钮宽度
        // 随内容收缩，行不占满全宽、在父容器中居中，两侧留白
        if (showActions) {
            Row(
                modifier = Modifier.padding(top = EInkSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
            ) {
                DetailActionButton(
                    iconRes = R.drawable.eink_ic_toc,
                    label = "查看目录",
                    onClick = onOpenToc
                )
                DetailActionButton(
                    iconRes = R.drawable.eink_ic_exchange,
                    label = "切换书源",
                    onClick = onChangeSource
                )
            }
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
                style = EInkTheme.typography.titleSmall,
                modifier = Modifier.padding(top = EInkSpacing.s)
            )
        }
        currentChapterTitle?.takeIf { it.isNotBlank() }?.let {
            EInkInfoRow(
                iconRes = R.drawable.eink_ic_history,
                text = it,
                style = EInkTheme.typography.titleSmall,
                modifier = Modifier.padding(top = EInkSpacing.s)
            )
        }
    }
}

/**
 * 信息区动作按钮：图标在左、文案在右的最小样式（labelSmall + 32dp 高；
 * EInkButton 无图标槽，此为详情页私有形态；按压瞬时反色 + 描边常态
 * 与 DS 按钮语言一致）。
 */
@Composable
private fun DetailActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    // 描边取舍与 EInkButton 同源：常态轮廓线，按压（实心反色）时边框取
    // 容器色与色块融合
    val borderColor = if (colors.containerColor != Color.Transparent) {
        colors.containerColor
    } else {
        EInkTheme.colorScheme.outline
    }
    Row(
        modifier = modifier
            .height(DetailActionButtonHeight)
            .then(press.modifier)
            .background(colors.containerColor, EInkShapes.small)
            .border(1.dp, borderColor, EInkShapes.small)
            .einkClickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            // 宽度随内容收缩后需要内边距，避免图标/文字贴描边
            .padding(horizontal = EInkSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(DetailActionButtonIconSize),
            colorFilter = ColorFilter.tint(colors.contentColor)
        )
        EInkText(
            text = label,
            style = EInkTheme.typography.labelSmall,
            color = colors.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
