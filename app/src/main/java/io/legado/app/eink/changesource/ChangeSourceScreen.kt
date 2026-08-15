package io.legado.app.eink.changesource

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.SearchBook
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.EInkTopBar
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.help.book.primaryStr

/**
 * 换源 Route — ViewModel 感知层。
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

    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(
            title = "换源 - ${uiState.book?.name ?: ""}",
            onBack = onBack,
            actions = {
                Column(horizontalAlignment = Alignment.End) {
                    EInkText(
                        text = if (uiState.isSearching) {
                            "搜索中 ${uiState.searchedCount}/${uiState.totalSourceCount}"
                        } else {
                            "重新搜索"
                        },
                        modifier = Modifier
                            .staticClickable(role = Role.Button, onClick = viewModel::startSearch)
                            .padding(horizontal = EInkSpacing.m),
                        style = EInkTheme.typography.labelLarge,
                    )
                }
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            ChangeSourceScreen(
                state = uiState,
                onPick = { searchBook ->
                    viewModel.changeTo(searchBook, onBack)
                }
            )
            if (uiState.isChanging) {
                EInkLoading(
                    text = "正在换源…",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 无状态换源 Screen。
 */
@Composable
internal fun ChangeSourceScreen(
    state: ChangeSourceUiState,
    onPick: (SearchBook) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.error != null -> CenterMessage(state.error)
            state.isSearching && state.results.isEmpty() -> {
                EInkLoading(
                    text = "搜索中 0/${state.totalSourceCount}",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.isEmpty -> CenterMessage("未找到其它书源")
            else -> SourceList(state = state, onPick = onPick)
        }
    }
}

@Composable
private fun SourceList(state: ChangeSourceUiState, onPick: (SearchBook) -> Unit) {
    val listState = rememberLazyListState()
    val currentOrigin = state.book?.origin

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.results, key = { it.primaryStr() }) { searchBook ->
            SourceItem(
                searchBook = searchBook,
                isCurrent = searchBook.origin == currentOrigin,
                onClick = { onPick(searchBook) }
            )
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun SourceItem(
    searchBook: SearchBook,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staticClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(
                text = searchBook.originName.ifBlank { searchBook.origin },
                style = EInkTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EInkText(
                text = "${searchBook.name} · ${searchBook.author}",
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            EInkText(
                text = "当前源",
                style = EInkTheme.typography.labelMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = EInkSpacing.s),
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
