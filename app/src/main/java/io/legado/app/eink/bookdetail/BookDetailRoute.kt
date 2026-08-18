package io.legado.app.eink.bookdetail

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.eink.component.EInkBackButton
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.component.EInkOperationBar
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.modifier.EInkPageSwipe
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.widget.EInkBookCover
import io.legado.app.eink.widget.EInkInfoRow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 详情页封面尺寸（130 × 182dp）。 */
private val DetailCoverWidth = 130.dp
private val DetailCoverHeight = 182.dp

/** 跨页重叠量：翻页步进 = 视口高 - 该值，保证跨页处的文字在上下两页都完整可见。 */
private val PageOverlap = 56.dp

/**
 * 书籍详情 Route — ViewModel 感知层。
 *
 * 布局（用户确认）：无顶栏；顶部封面在左、书名/作者在右；下方操作按钮一排
 * （图标上文字下，横向均分）；信息区 字数/标签 → 最新章节 → 当前进度章节 →
 * 书源 → 简介；返回按钮放底部操作栏。
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
    viewModel: BookDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
        onRemoveFromShelf = viewModel::removeFromBookshelf,
        onOpenToc = { onOpenToc(effectiveBookUrl) },
        onRead = { onRead(effectiveBookUrl) },
        onChangeSource = viewModel::changeSource,
    )
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
    val pageUp: () -> Unit = {
        scope.launch { scrollState.scrollTo((scrollState.value - pageStep).coerceAtLeast(0)) }
    }
    val pageDown: () -> Unit = {
        scope.launch { scrollState.scrollTo((scrollState.value + pageStep).coerceAtMost(scrollState.maxValue)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
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
                        text = LocalContext.current.getString(R.string.eink_book_not_found),
                        style = EInkTheme.typography.bodyLarge
                    )
                }
            }

            else -> {
                val book = state.book ?: return
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { viewportHeightPx = it.height }
                        .EInkPageSwipe(onPageUp = pageUp, onPageDown = pageDown)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState, enabled = false)
                    ) {
                        // 顶部信息：封面在左，书名/作者在右（充分利用空间）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = EInkSpacing.l,
                                    end = EInkSpacing.l,
                                    top = 60.dp,
                                    bottom = EInkSpacing.l
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EInkBookCover(
                                url = book.getDisplayCover(),
                                name = book.name,
                                author = book.getRealAuthor(),
                                sourceOrigin = book.origin,
                                modifier = Modifier
                                    .width(DetailCoverWidth)
                                    .height(DetailCoverHeight),
                                width = DetailCoverWidth,
                                height = DetailCoverHeight
                            )
                            Spacer(modifier = Modifier.width(EInkSpacing.m))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = EInkSpacing.xs)
                            ) {
                                // 书名
                                EInkText(
                                    text = book.name,
                                    style = EInkTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 作者
                                EInkText(
                                    text = book.getRealAuthor(),
                                    style = EInkTheme.typography.bodyMedium,
                                    color = EInkTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 字数/标签
                                book.getKindList().takeIf { it.isNotEmpty() }?.let { kinds ->
                                    EInkText(
                                        text = kinds.joinToString(" / "),
                                        style = EInkTheme.typography.labelMedium,
                                        color = EInkTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = EInkSpacing.s)
                                    )
                                }
                                // 最新章节
                                book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                                    EInkInfoRow(
                                        iconRes = R.drawable.ic_book_last,
                                        text = it,
                                        style = EInkTheme.typography.labelMedium,
                                        modifier = Modifier.padding(top = EInkSpacing.s)
                                    )
                                }
                                // 当前进度章节
                                book.durChapterTitle?.takeIf { it.isNotBlank() }?.let {
                                    EInkInfoRow(
                                        iconRes = R.drawable.ic_history,
                                        text = it,
                                        style = EInkTheme.typography.labelMedium,
                                        modifier = Modifier.padding(top = EInkSpacing.s)
                                    )
                                }
                                // 书源
                                book.originName.takeIf { it.isNotBlank() }?.let {
                                    EInkInfoRow(
                                        iconRes = R.drawable.ic_web_outline,
                                        text = it,
                                        style = EInkTheme.typography.labelMedium,
                                        modifier = Modifier.padding(top = EInkSpacing.s)
                                    )
                                }
                            }
                        }
                        // 操作按钮一排：加入书架/移出书架 / 查看目录 / 切换书源 / 阅读
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionButton(
                                iconRes = if (state.isInBookshelf) R.drawable.ic_outline_delete else R.drawable.ic_add,
                                label = if (state.isInBookshelf) "移出书架" else "加入书架",
                                onClick = if (state.isInBookshelf) onRemoveFromShelf else onAddToShelf,
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                iconRes = R.drawable.ic_toc,
                                label = "查看目录",
                                onClick = onOpenToc,
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                iconRes = R.drawable.ic_swap_horiz,
                                label = "切换书源",
                                onClick = onChangeSource,
                                modifier = Modifier.weight(1f)
                            )
                            ActionButton(
                                iconRes = R.drawable.ic_play_outline_24dp,
                                label = "阅读",
                                onClick = onRead,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        EInkHorizontalDivider()
                        // 简介区（信息已并入顶部，下方仅保留简介）
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.m)
                        ) {
                            EInkText(
                                text = book.getDisplayIntro()?.takeIf { it.isNotBlank() } ?: "暂无简介",
                                style = EInkTheme.typography.bodyMedium,
                                color = EInkTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        // 底部操作栏：返回 + 翻页（按钮与滑动手势统一）
        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = { EInkBackButton(onClick = onBack) },
            pageUpEnabled = canPageUp,
            pageDownEnabled = canPageDown,
            onPageUp = pageUp,
            onPageDown = pageDown
        )
    }
}

/**
 * 操作按钮：图标在上、文字在下（触控目标 ≥48dp）。
 */
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(colors.onSurface)
        )
        Spacer(modifier = Modifier.height(EInkSpacing.xs))
        EInkText(
            text = label,
            style = EInkTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1
        )
    }
}