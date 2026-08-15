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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
 * 职责：
 * - 全屏（隐藏系统栏，退出时恢复）；按设置保持屏幕常亮；
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

    LaunchedEffect(bookUrl) {
        viewModel.attach(bookUrl)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg.format(context), Toast.LENGTH_SHORT).show()
        }
    }

    // 阅读全屏：进入隐藏系统栏，退出恢复（零动画，规范 §15）
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
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

    // 返回键逐级回退：面板 → 操作条 → 退出阅读
    BackHandler {
        when {
            panel != null -> panel = null
            uiState.controlsVisible -> viewModel.hideControls()
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderScreen(
            state = uiState,
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
            when (current) {
                ReaderPanel.LAYOUT -> ReaderPanelContainer(title = "排版设置", onClose = onClose) {
                    ReaderLayoutPanel(
                        style = uiState.style,
                        onAdjustTextSize = viewModel::adjustTextSize,
                        onAdjustLetterSpacing = viewModel::adjustLetterSpacing,
                        onAdjustLineSpacing = viewModel::adjustLineSpacing,
                        onAdjustParagraphSpacing = viewModel::adjustParagraphSpacing,
                        onSetIndent = viewModel::setIndent,
                        onAdjustPaddingH = viewModel::adjustPaddingH,
                        onAdjustPaddingV = viewModel::adjustPaddingV,
                        onAdjustHeaderPadding = viewModel::adjustHeaderPadding,
                        onAdjustFooterPadding = viewModel::adjustFooterPadding,
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
}

/**
 * 无状态阅读 Screen — 纯渲染。
 *
 * 结构：页眉（书名/进度）→ 正文 Canvas（引擎排版区域）→ 页脚（章节/页码）。
 * 操作条覆盖在正文之上，不改变排版区域尺寸（布局稳定，规范 §15）。
 *
 * 手势（规范 §16）：
 * - 操作条可见时：点正文任意处收起操作条；
 * - 操作条隐藏时：点左 30% 上一页 / 右 30% 下一页 / 中间 40% 唤出操作条。
 */
@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
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
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.showHeader) {
                ReaderHeader(
                    state = state,
                    padding = state.style.headerPadding.dp,
                )
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
            ReaderFooter(
                state = state,
                padding = state.style.footerPadding.dp,
            )
        }

        if (state.controlsVisible) {
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

/** 页眉：书名（左）+ 阅读进度（右）。 */
@Composable
private fun ReaderHeader(state: ReaderUiState, padding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = padding + 8.dp,
                vertical = 4.dp,
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

/** 页脚：章节标题（左）+ 页码（右）。 */
@Composable
private fun ReaderFooter(state: ReaderUiState, padding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = padding + 8.dp,
                vertical = 4.dp,
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
