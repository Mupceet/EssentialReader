package io.legado.app.eink.reader

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.engine.EinkEngineRegistry
import io.legado.app.eink.engine.EinkPageContent
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkTheme
import android.view.WindowManager

/**
 * 阅读 Route — ViewModel 感知层。
 *
 * 全屏说明：窗口在 Activity 层始终 Edge-to-Edge（进出阅读无布局跳动）；
 * 阅读时不收起系统状态栏，正常显示原生内容。
 *
 * 职责：
 * - 按设置保持屏幕常亮；
 * - 返回键：面板 → 控件 → 退出阅读 的逐级回退；
 * - 一次性消息 → Toast；
 * - 面板开关为 UI 局部状态（remember），排版数据来自 [ReaderUiState]。
 */
@Composable
fun ReaderRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onOpenToc: (String) -> Unit,
    onChangeSource: (String) -> Unit,
    onOpenDetail: (name: String, author: String, bookUrl: String) -> Unit,
    viewModel: ReaderViewModel = viewModel(),
    pageRenderer: @Composable (page: EinkPageContent?, pageVersion: Int, modifier: Modifier) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var showMarginDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookUrl) {
        viewModel.attach(bookUrl)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg.format(context), Toast.LENGTH_SHORT).show()
        }
    }

    // 保持屏幕常亮
    DisposableEffect(uiState.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (uiState.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { }
    }

    // 返回键逐级回退：边距弹框 → 设置面板 → 收起操作条 → 退出阅读
    // （边距弹框期间排版面板保留，返回即回到排版展开态）
    BackHandler {
        when {
            showMarginDialog -> showMarginDialog = false
            panel != null -> panel = null
            uiState.controlsVisible -> viewModel.hideControls()
            else -> onBack()
        }
    }

    // 操作条返回图标：关闭设置面板 → 退出阅读。
    // 边距弹框期间操作条整体隐藏（保证边距实时可见），其首级返回
    // 由系统返回键/点击弹框外区域承担，关闭后回到排版展开态
    val onBarBack = {
        if (panel != null) panel = null else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderScreen(
            state = uiState,
            // 绘制叶子（引擎排版产物的画布）由宿主注入
            pageRenderer = pageRenderer,
            // 顶栏在设置面板打开期间隐藏（保持页眉等顶部调参预览不被遮挡）；
            // 底部操作条常驻可见，承载面板期间的返回与选中态；
            // 边距调整弹框例外：操作条隐藏，保证正文四周边距实时可见
            topBarVisible = uiState.controlsVisible && panel == null,
            bottomBarVisible = uiState.controlsVisible && !showMarginDialog,
            onPrevPage = viewModel::prevPage,
            onNextPage = viewModel::nextPage,
            onCenterTap = viewModel::toggleControls,
            onContentSized = viewModel::updateViewSize,
            onBack = onBack,
            onBarBack = onBarBack,
            onOpenToc = { onOpenToc(bookUrl) },
            onToggleAutoPlay = viewModel::toggleAutoPlay,
            onChangeSource = { onChangeSource(bookUrl) },
            onOpenDetail = {
                // 换源后以引擎当前持有的书为准（bookUrl 与路由参数可能不同）
                if (uiState.bookUrl.isNotEmpty()) {
                    onOpenDetail(uiState.bookName, uiState.bookAuthor, uiState.bookUrl)
                }
            },
            onRefresh = viewModel::refreshChapter,
            onOpenCachePanel = { panel = ReaderPanel.CACHE },
            onAddToBookshelf = viewModel::addToBookshelf,
            selectedPanel = panel,
            onOpenPanel = { target ->
                // 再次点击已打开的面板按钮 = 关闭（取消选中）；
                // 边距弹框打开时点击则先收回弹框、回到面板
                val toggleOff = panel == target && !showMarginDialog
                showMarginDialog = false
                panel = if (toggleOff) null else target
            },
            onRetry = { viewModel.attach(bookUrl) },
        )

        // 设置面板与阅读内容对齐（Edge-to-Edge 下避免被系统栏遮挡），
        // 底部避开常驻操作条，保持其可见可点；
        // 边距弹框期间面板隐藏（panel 状态保留），关闭弹框后回到展开态
        panel?.takeIf { !showMarginDialog }?.let { current ->
            val onClose = { panel = null }
            // 面板与阅读内容对齐（Edge-to-Edge 下避免被系统栏遮挡），
            // 底部避开常驻操作条，保持其可见可点
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(bottom = ReaderBottomBarInset)
            ) {
                when (current) {
                ReaderPanel.LAYOUT -> ReaderPanelContainer(title = "排版设置", onClose = onClose) {
                    ReaderLayoutPanel(
                        style = uiState.style,
                        onSetTextSize = viewModel::setTextSize,
                        onSetLetterSpacing = viewModel::setLetterSpacing,
                        onSetIndent = viewModel::setIndent,
                        onSetLineSpacing = viewModel::setLineSpacing,
                        onSetParagraphSpacing = viewModel::setParagraphSpacing,
                        // 排版面板保留（不置空 panel），返回键回到排版展开态
                        onOpenMargins = { showMarginDialog = true },
                    )
                }

                    ReaderPanel.OTHER -> ReaderPanelContainer(title = "其它设置", onClose = onClose) {
                    ReaderOtherPanel(
                        state = uiState,
                        onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                        onToggleTextBold = viewModel::toggleTextBold,
                        onAdjustAutoInterval = viewModel::adjustAutoPlayInterval,
                    )
                    }

                    ReaderPanel.CACHE -> ReaderPanelContainer(title = "缓存", onClose = onClose) {
                        ReaderCachePanel(
                            onCache = { count ->
                                viewModel.cacheChapters(count)
                                panel = null
                            }
                        )
                    }
                }
            }
        }

        // 边距调整弹框：屏幕居中、四周透明，内含 正文/页眉/页脚 三 Tab，
        // 调整时页眉/页脚/正文边距实时可见。弹框期间操作条隐藏；
        // 关闭后回到排版展开态（面板保留），操作条恢复显示
        if (showMarginDialog) {
            ReaderMarginDialog(
                style = uiState.style,
                onSetPaddingTop = viewModel::setPaddingTop,
                onSetPaddingBottom = viewModel::setPaddingBottom,
                onSetPaddingLeft = viewModel::setPaddingLeft,
                onSetPaddingRight = viewModel::setPaddingRight,
                onSetHeaderPaddingTop = viewModel::setHeaderPaddingTop,
                onSetHeaderPaddingBottom = viewModel::setHeaderPaddingBottom,
                onSetHeaderPaddingLeft = viewModel::setHeaderPaddingLeft,
                onSetHeaderPaddingRight = viewModel::setHeaderPaddingRight,
                onSetFooterPaddingTop = viewModel::setFooterPaddingTop,
                onSetFooterPaddingBottom = viewModel::setFooterPaddingBottom,
                onSetFooterPaddingLeft = viewModel::setFooterPaddingLeft,
                onSetFooterPaddingRight = viewModel::setFooterPaddingRight,
                onClose = { showMarginDialog = false },
            )
        }
    }
}

/**
 * 无状态阅读 Screen — 纯渲染。
 *
 * 结构：页眉（书名/进度）→ 正文 Canvas（引擎排版区域）→ 页脚（章节/页码）。
 * 操作条覆盖在正文之上，不改变排版区域尺寸（布局稳定，规范 §15）。
 *
 * 操作条可见性：设置面板打开期间底部操作条保持可见（承载分层返回
 * [onBarBack] 与面板选中态 [selectedPanel]），顶栏隐藏以保持顶部调参预览。
 *
 * 系统栏避让由本界面自管（[safeDrawingPadding]）：窗口 Edge-to-Edge、
 * 状态栏阅读时保持可见，页眉紧贴状态栏下方；后续若支持收起状态栏，
 * 状态栏区域转为页眉区域（页眉上移占位），正文始终从页眉之下开始排版。
 *
 * 手势（规范 §16）：
 * - 操作条可见时：点/滑动正文任意处收起操作条；
 * - 操作条隐藏时：点中间 40% 唤出操作条，点其余区域下一页；
 * - 水平滑动翻页，判定对齐 View 版：触发距离读引擎 pageTouchSlop（AppConfig.pageTouchSlop 经端口）
 *   （完整版设置"翻页触发距离"，0 = 系统 slop，Compose 版只读不设），
 *   松手前反向回拖取消；无跟手移动，翻页整页立即替换。
 */
@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
    pageRenderer: @Composable (page: EinkPageContent?, pageVersion: Int, modifier: Modifier) -> Unit,
    topBarVisible: Boolean,
    bottomBarVisible: Boolean,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onContentSized: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onBarBack: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenDetail: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onAddToBookshelf: () -> Unit,
    selectedPanel: ReaderPanel?,
    onOpenPanel: (ReaderPanel) -> Unit,
    onRetry: () -> Unit,
) {
    // 纯净阅读底色/字色随日/夜间主题（决策 B1/B2 修订：仍不读取
    // bgStrEInk / textColorEInk 等用户配色配置，颜色由主题统一下发）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.headerVisible) {
                ReaderHeader(state = state)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        onContentSized(size.width, size.height)
                    }
                    .pointerInput(state.controlsVisible) {
                        detectTapGestures { offset ->
                            if (state.controlsVisible) {
                                onCenterTap() // 收起操作条
                                return@detectTapGestures
                            }
                            val width = size.width
                            if (offset.x in width * 0.3f..width * 0.7f) {
                                onCenterTap()
                            } else {
                                onNextPage()
                            }
                        }
                    }
                    .pointerInput(state.controlsVisible) {
                        // 水平滑动翻页，判定对齐 View 版：
                        // - 触发距离 = 引擎 pageTouchSlop（px），0 = 系统 touch slop
                        //   （该 slop 已由 detectHorizontalDragGestures 消费）；
                        // - 松手前最后一次增量与滑动方向相反则取消（等价 View 版 isCancel）。
                        var dragAccum = 0f
                        var lastDelta = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                            onDragEnd = {
                                if (state.controlsVisible) {
                                    onCenterTap() // 收起操作条，不翻页
                                } else {
                                    val slop = EinkEngineRegistry.readerEngine.pageTouchSlop.toFloat()
                                    when {
                                        lastDelta * dragAccum < 0f -> Unit // 回拖取消
                                        dragAccum < -slop -> onNextPage()
                                        dragAccum > slop -> onPrevPage()
                                    }
                                }
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                            onDragCancel = {
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            if (dragAmount != 0f) lastDelta = dragAmount
                        }
                    }
            ) {
                pageRenderer(
                    state.page,
                    state.pageVersion,
                    Modifier.fillMaxSize(),
                )
                if (state.isLoading && state.page == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EInkText(
                            text = "加载中…",
                            color = EInkTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.error != null) {
                    ErrorView(message = state.error, onRetry = onRetry, onBack = onBack)
                }
            }
            if (state.footerVisible) {
                ReaderFooter(state = state)
            }
        }

        if (topBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                ReaderTopBar(
                    state = state,
                    onOpenDetail = onOpenDetail,
                    onChangeSource = onChangeSource,
                    onRefresh = onRefresh,
                    onOpenCachePanel = onOpenCachePanel,
                    onAddToBookshelf = onAddToBookshelf,
                )
            }
        }
        if (bottomBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                ReaderBottomBar(
                    state = state,
                    selectedPanel = selectedPanel,
                    onBarBack = onBarBack,
                    onOpenToc = onOpenToc,
                    onToggleAutoPlay = onToggleAutoPlay,
                    onOpenPanel = onOpenPanel,
                )
            }
        }
    }
}

/** 页眉：时间（左）+ 电量%（右）。可见性与内容按 View 版 ReadTipConfig 默认规则，不开放设置。 */
@Composable
private fun ReaderHeader(state: ReaderUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = state.style.headerPaddingLeft.dp,
                top = state.style.headerPaddingTop.dp,
                end = state.style.headerPaddingRight.dp,
                bottom = state.style.headerPaddingBottom.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.headerTime.isNotEmpty()) {
            EInkText(
                text = state.headerTime,
                modifier = Modifier.weight(1f),
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        EInkText(
            text = "${state.batteryPercent}%",
            color = EInkTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 页脚：章节标题（左）+ 页数及进度（右，View 版 pageAndTotal 格式）。 */
@Composable
private fun ReaderFooter(state: ReaderUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = state.style.footerPaddingLeft.dp,
                top = state.style.footerPaddingTop.dp,
                end = state.style.footerPaddingRight.dp,
                bottom = state.style.footerPaddingBottom.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = state.chapterTitle,
            modifier = Modifier.weight(1f),
            color = EInkTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.pageAndTotal.isNotEmpty()) {
            EInkText(
                text = state.pageAndTotal,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EInkText(
            text = "加载失败",
            style = EInkTheme.typography.titleMedium,
        )
        EInkText(
            text = message,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Box(
                modifier = Modifier
                    .staticClickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                EInkText(text = "重试", color = EInkTheme.colorScheme.onSurface)
            }
            Box(
                modifier = Modifier
                    .staticClickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                EInkText(text = "返回", color = EInkTheme.colorScheme.onSurface)
            }
        }
    }
}
