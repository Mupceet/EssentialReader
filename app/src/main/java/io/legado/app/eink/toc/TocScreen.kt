package io.legado.app.eink.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.BookChapter
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * 目录 Route。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocRoute(
    bookUrl: String,
    onBack: () -> Unit,
    viewModel: TocViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(bookUrl) {
        viewModel.loadBook(bookUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.book?.name ?: "目录",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Text(
                        text = if (uiState.isReversed) "倒序" else "正序",
                        modifier = Modifier
                            .clickable { viewModel.toggleReverse() }
                            .padding(horizontal = EInkSpacing.m),
                        style = eInkTypography().labelLarge
                    )
                }
            )
        }
    ) { innerPadding ->
        TocScreen(
            state = uiState,
            onChapterClick = { index -> viewModel.openChapter(index) },
            contentPadding = innerPadding
        )
    }
}

/**
 * 无状态目录 Screen。
 */
@Composable
internal fun TocScreen(
    state: TocUiState,
    onChapterClick: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
            state.error != null -> CenterMessage(state.error)
            state.isEmpty -> CenterMessage("无章节")
            else -> ChapterList(state = state, onChapterClick = onChapterClick, contentPadding = contentPadding)
        }
    }
}

@Composable
private fun ChapterList(
    state: TocUiState,
    onChapterClick: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    // 展示项携带真实索引，避免倒序/过滤后索引错位
    val display: List<Pair<Int, BookChapter>> = state.displayChapters
        .mapIndexed { index, chapter -> index to chapter }
        .let { if (state.isReversed) it.asReversed() else it }
    val listState = rememberLazyListState()

    // 定位到当前阅读章节
    LaunchedEffect(state.chapters) {
        if (state.chapters.isNotEmpty() && !state.isReversed && state.searchKey.isBlank()) {
            val target = state.durChapterIndex.coerceIn(0, display.lastIndex)
            listState.scrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        itemsIndexed(display, key = { _, (_, chapter) -> chapter.url }) { _, (realIndex, chapter) ->
            ChapterItem(
                chapter = chapter,
                isCurrent = realIndex == state.durChapterIndex,
                onClick = { onChapterClick(realIndex) }
            )
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun ChapterItem(chapter: BookChapter, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chapter.title,
            modifier = Modifier.weight(1f),
            style = eInkTypography().bodyMedium.copy(
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (isCurrent) {
                eInkColorScheme().onSurface
            } else {
                eInkColorScheme().onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isCurrent) {
            Text(
                text = "在读",
                style = eInkTypography().labelMedium,
                color = eInkColorScheme().onSurfaceVariant,
                modifier = Modifier.padding(start = EInkSpacing.s)
            )
        }
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, style = eInkTypography().bodyLarge)
    }
}
