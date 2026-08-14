package io.legado.app.eink.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import io.legado.app.eink.component.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme

/**
 * 阅读 Route — ViewModel 感知层。
 */
@Composable
fun ReaderRoute(
    bookUrl: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // 首次进入时加载书籍，传递屏幕尺寸给排版引擎
    LaunchedEffect(bookUrl) {
        val widthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
        val heightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
        viewModel.loadBook(bookUrl, widthPx, heightPx)
    }

    ReaderScreen(
        state = uiState,
        onNextPage = viewModel::nextPage,
        onPrevPage = viewModel::prevPage,
        onBack = onBack
    )
}

/**
 * 无状态阅读 Screen — 纯渲染。
 *
 * 手势策略（规范 §16）:
 * - 点击左半屏 → 上一页
 * - 点击右半屏 → 下一页
 * - 点击中间区域 → 返回（简化版，后续可改为 toggle controls）
 *
 * 翻页直接替换页面内容，无动画（规范 §15）。
 */
@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val width = size.width
                    when {
                        offset.x < width * 0.3f -> onPrevPage()
                        offset.x > width * 0.7f -> onNextPage()
                        else -> onBack()
                    }
                }
            }
    ) {
        when {
            state.isLoading && state.pageText.isEmpty() -> {
                EInkLoading(modifier = Modifier.fillMaxSize())
            }
            state.error != null -> {
                ErrorView(message = state.error, onBack = onBack)
            }
            else -> {
                ReaderContent(state = state)
            }
        }
    }
}

@Composable
private fun ReaderContent(state: ReaderUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = EInkSpacing.m,
                vertical = EInkSpacing.s
            )
    ) {
        // 正文区域（占据主要空间）
        EInkText(
            text = state.pageText,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            style = TextStyle(
                fontSize = 18.sp,
                lineHeight = 32.sp,
                color = eInkColorScheme().onSurface
            )
        )

        // 底部信息栏（规范 §15: minimal UI chrome）
        ReaderFooter(state = state)
    }
}

@Composable
private fun ReaderFooter(state: ReaderUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EInkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 章节标题（左）
        EInkText(
            text = state.chapterTitle,
            style = TextStyle(
                fontSize = 12.sp,
                color = eInkColorScheme().onSurfaceVariant
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        // 页码（右）
        if (state.pageIndicator.isNotEmpty()) {
            EInkText(
                text = state.pageIndicator,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = eInkColorScheme().onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(EInkSpacing.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EInkText(
            text = "加载失败",
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
        )
        EInkText(
            text = message,
            style = TextStyle(fontSize = 14.sp, color = eInkColorScheme().onSurfaceVariant),
            modifier = Modifier.padding(vertical = EInkSpacing.s)
        )
        EInkText(
            text = "[ 返回 ]",
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .padding(top = EInkSpacing.m)
                .pointerInput(Unit) {
                    detectTapGestures { onBack() }
                }
        )
    }
}
