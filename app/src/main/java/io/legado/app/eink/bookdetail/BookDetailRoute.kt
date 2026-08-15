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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.widget.EInkBookCover
import io.legado.app.eink.widget.EInkInfoRow

/** 详情页封面尺寸（与 View 版 activity_book_info.xml 一致：110 × 160dp）。 */
private val DetailCoverWidth = 110.dp
private val DetailCoverHeight = 160.dp

/**
 * 书籍详情 Route — ViewModel 感知层。
 *
 * 布局（用户确认）：无顶栏；封面在内容区顶部，下方书名/作者、操作按钮一排
 * （图标上文字下）、信息区（当前进度/最新章节/标签/书源/简介完整显示）；
 * 返回按钮放底部操作栏。
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
    onOpenToc: () -> Unit,
    onRead: () -> Unit,
    onChangeSource: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        when {
            state.isLoading -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EInkLoading()
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 封面（顶部居中）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = EInkSpacing.l),
                        contentAlignment = Alignment.Center
                    ) {
                        EInkBookCover(
                            url = book.getDisplayCover(),
                            name = book.name,
                            author = book.getRealAuthor(),
                            modifier = Modifier
                                .width(DetailCoverWidth)
                                .height(DetailCoverHeight),
                            width = DetailCoverWidth,
                            height = DetailCoverHeight
                        )
                    }
                    // 书名
                    EInkText(
                        text = book.name,
                        style = EInkTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 作者
                    EInkText(
                        text = book.getRealAuthor(),
                        style = EInkTheme.typography.bodyMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = EInkSpacing.xs)
                    )
                    // 操作按钮一排：加入书架 / 查看目录 / 切换书源 / 阅读
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.l),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionButton(
                            iconRes = if (state.isInBookshelf) R.drawable.ic_book_has else R.drawable.ic_add,
                            label = if (state.isInBookshelf) "已在书架" else "加入书架",
                            enabled = !state.isInBookshelf,
                            onClick = onAddToShelf
                        )
                        ActionButton(
                            iconRes = R.drawable.ic_toc,
                            label = "查看目录",
                            onClick = onOpenToc
                        )
                        ActionButton(
                            iconRes = R.drawable.ic_swap_horiz,
                            label = "切换书源",
                            onClick = onChangeSource
                        )
                        ActionButton(
                            iconRes = R.drawable.ic_play_outline_24dp,
                            label = "阅读",
                            onClick = onRead
                        )
                    }
                    EInkHorizontalDivider()
                    // 信息区
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m)
                    ) {
                        // 当前进度章节
                        book.durChapterTitle?.takeIf { it.isNotBlank() }?.let {
                            EInkInfoRow(
                                iconRes = R.drawable.ic_history,
                                text = it,
                                style = EInkTheme.typography.labelMedium
                            )
                        }
                        // 最新章节
                        book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                            EInkInfoRow(
                                iconRes = R.drawable.ic_book_last,
                                text = it,
                                style = EInkTheme.typography.labelMedium
                            )
                        }
                        // 标签/字数
                        book.getKindList().takeIf { it.isNotEmpty() }?.let { kinds ->
                            EInkText(
                                text = kinds.joinToString(" / "),
                                style = EInkTheme.typography.labelMedium,
                                color = EInkTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = EInkSpacing.xxs)
                            )
                        }
                        // 书源
                        book.originName.takeIf { it.isNotBlank() }?.let {
                            EInkInfoRow(
                                iconRes = R.drawable.ic_web_outline,
                                text = it,
                                style = EInkTheme.typography.labelMedium
                            )
                        }
                        // 简介（完整显示）
                        EInkText(
                            text = "简介：${book.getDisplayIntro()}",
                            style = EInkTheme.typography.bodyMedium,
                            color = EInkTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = EInkSpacing.m)
                        )
                    }
                }
            }
        }
        // 底部操作栏：左侧返回；详情页无翻页，箭头置灰
        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = { EInkBackButton(onClick = onBack) }
        )
    }
}

/**
 * 操作按钮：图标在上、文字在下（触控目标 ≥48dp，禁用时置灰）。
 */
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = EInkTheme.colorScheme
    val contentColor = if (enabled) colors.onSurface else colors.disabledContent
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(contentColor)
        )
        Spacer(modifier = Modifier.height(EInkSpacing.xs))
        EInkText(
            text = label,
            style = EInkTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}