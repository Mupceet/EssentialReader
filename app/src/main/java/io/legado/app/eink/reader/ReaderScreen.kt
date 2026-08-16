package io.legado.app.eink.reader

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.help.config.ReadBookConfig
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
    viewModel: ReaderViewModel = viewModel(),
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

    // 返回键逐级回退：边距弹框 → 设置面板 → 操作条 → 退出阅读
    BackHandler {
        when {
            showMarginDialog -> showMarginDialog = false
            panel != null -> panel = null
            uiState.controlsVisible -> viewModel.hideControls()
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderScreen(
            state = uiState,
            // 设置面板/边距弹框打开时收起操作条（关闭后自动恢复），
            // 排版调参时才能看清页眉、边距等顶部效果
            barsVisible = uiState.controlsVisible && panel == null && !showMarginDialog,
            onPrevPage = viewModel::prevPage,
            onNextPage = viewModel::nextPage,
            onCenterTap = viewModel::toggleControls,
            onContentSized = viewModel::updateViewSize,
            onBack = onBack,
            onOpenToc = { onOpenToc(bookUrl) },
            onToggleAutoPlay = viewModel::toggleAutoPlay,
            onChangeSource = { onChangeSource(bookUrl) },
            onRefresh = viewModel::refreshChapter,
            onOpenCachePanel = { panel = ReaderPanel.CACHE },
            onToggleBookshelf = viewModel::toggleBookshelf,
            onOpenPanel = { panel = it },
            onRetry = { viewModel.attach(bookUrl) },
        )

        panel?.let { current ->
            val onClose = { panel = null }
            // 面板与阅读内容对齐（Edge-to-Edge 下避免被系统栏遮挡）
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                when (current) {
                ReaderPanel.LAYOUT -> ReaderPanelContainer(title = "排版设置", onClose = onClose) {
                    ReaderLayoutPanel(
                        style = uiState.style,
                        onAdjustTextSize = viewModel::adjustTextSize,
                        onAdjustLetterSpacing = viewModel::adjustLetterSpacing,
                        onAdjustIndent = viewModel::adjustIndent,
                        onAdjustLineSpacing = viewModel::adjustLineSpacing,
                        onAdjustParagraphSpacing = viewModel::adjustParagraphSpacing,
                        onOpenMargins = {
                            panel = null
                            showMarginDialog = true
                        },
                    )
                }

                    ReaderPanel.OTHER -> ReaderPanelContainer(title = "其它设置", onClose = onClose) {
                        ReaderOtherPanel(
                            state = uiState,
                            onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                            onToggleShowHeader = viewModel::toggleShowHeader,
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
        // 调整时页眉/页脚/正文边距实时可见
        if (showMarginDialog) {
            ReaderMarginDialog(
                style = uiState.style,
                onAdjustPaddingTop = viewModel::adjustPaddingTop,
                onAdjustPaddingBottom = viewModel::adjustPaddingBottom,
                onAdjustPaddingLeft = viewModel::adjustPaddingLeft,
                onAdjustPaddingRight = viewModel::adjustPaddingRight,
                onAdjustHeaderPaddingTop = viewModel::adjustHeaderPaddingTop,
                onAdjustHeaderPaddingBottom = viewModel::adjustHeaderPaddingBottom,
                onAdjustHeaderPaddingLeft = viewModel::adjustHeaderPaddingLeft,
                onAdjustHeaderPaddingRight = viewModel::adjustHeaderPaddingRight,
                onAdjustFooterPaddingTop = viewModel::adjustFooterPaddingTop,
                onAdjustFooterPaddingBottom = viewModel::adjustFooterPaddingBottom,
                onAdjustFooterPaddingLeft = viewModel::adjustFooterPaddingLeft,
                onAdjustFooterPaddingRight = viewModel::adjustFooterPaddingRight,
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
 * 系统栏避让由本界面自管（[safeDrawingPadding]）：窗口 Edge-to-Edge、
 * 状态栏阅读时保持可见，页眉紧贴状态栏下方；后续若支持收起状态栏，
 * 状态栏区域转为页眉区域（页眉上移占位），正文始终从页眉之下开始排版。
 *
 * 手势（规范 §16）：
 * - 操作条可见时：点正文任意处收起操作条；
 * - 操作条隐藏时：点左 30% 上一页 / 右 30% 下一页 / 中间 40% 唤出操作条。
 */
@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
    barsVisible: Boolean,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onContentSized: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onChangeSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onToggleBookshelf: () -> Unit,
    onOpenPanel: (ReaderPanel) -> Unit,
    onRetry: () -> Unit,
) {
    val readerBg = remember {
        ReadBookConfig.durConfig.run {
            if (bgTypeEInk == 0) {
                runCatching { Color(android.graphics.Color.parseColor(bgStrEInk)) }
                    .getOrDefault(Color.White)
            } else {
                Color.White
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBg)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.showHeader) {
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
                            when {
                                offset.x < width * 0.3f -> onPrevPage()
                                offset.x > width * 0.7f -> onNextPage()
                                else -> onCenterTap()
                            }
                        }
                    }
            ) {
                ReaderPageCanvas(
                    page = state.textPage,
                    pageVersion = state.pageVersion,
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.isLoading && state.textPage == null) {
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
            ReaderFooter(state = state)
        }

        if (barsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                ReaderTopBar(
                    state = state,
                    onChangeSource = onChangeSource,
                    onRefresh = onRefresh,
                    onOpenCachePanel = onOpenCachePanel,
                    onToggleBookshelf = onToggleBookshelf,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                ReaderBottomBar(
                    state = state,
                    onBack = onBack,
                    onOpenToc = onOpenToc,
                    onToggleAutoPlay = onToggleAutoPlay,
                    onOpenPanel = onOpenPanel,
                )
            }
        }
    }
}

/** 页眉：书名（左）+ 阅读进度（右）。字号固定（与 View 版对齐），四向边距来自排版参数。 */
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
        EInkText(
            text = state.bookName,
            modifier = Modifier.weight(1f),
            color = EInkTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.readProgress.isNotEmpty()) {
            EInkText(
                text = state.readProgress,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 页脚：章节标题（左）+ 页码（右）。字号固定（与 View 版对齐），四向边距来自排版参数。 */
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
        if (state.pageIndicator.isNotEmpty()) {
            EInkText(
                text = state.pageIndicator,
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
