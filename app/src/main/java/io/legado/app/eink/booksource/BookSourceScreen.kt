package io.legado.app.eink.booksource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkLoading
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.EInkTopBar
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * 书源管理 Route。
 */
@Composable
fun BookSourceRoute(
    onBack: () -> Unit,
    viewModel: BookSourceViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(
            title = "书源 ${uiState.enabledCount}/${uiState.totalCount}",
            onBack = onBack,
            actions = {
                EInkText(
                    text = "全启",
                    modifier = Modifier
                        .clickable { viewModel.enableAll(true) }
                        .padding(horizontal = EInkSpacing.s),
                    style = eInkTypography().labelLarge
                )
                EInkText(
                    text = "全禁",
                    modifier = Modifier
                        .clickable { viewModel.enableAll(false) }
                        .padding(horizontal = EInkSpacing.s),
                    style = eInkTypography().labelLarge
                )
            }
        )
        BookSourceScreen(
            state = uiState,
            onSearch = viewModel::search,
            onToggleSource = viewModel::toggleSource
        )
    }
}

/**
 * 无状态书源 Screen。
 */
@Composable
internal fun BookSourceScreen(
    state: BookSourceUiState,
    onSearch: (String) -> Unit,
    onToggleSource: (BookSourcePart) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        SourceSearchBar(searchKey = state.searchKey, onSearch = onSearch)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                state.isEmpty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EInkText("无匹配书源", style = eInkTypography().bodyLarge)
                }
                else -> SourceList(state, onToggleSource)
            }
        }
    }
}

@Composable
private fun SourceSearchBar(searchKey: String, onSearch: (String) -> Unit) {
    BasicTextField(
        value = searchKey,
        onValueChange = onSearch,
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = eInkColorScheme().onSurface),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = EInkSpacing.xs),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchKey.isEmpty()) {
                        EInkText(
                            text = "搜索书源名称/URL",
                            fontSize = 16.sp,
                            color = eInkColorScheme().outline
                        )
                    }
                    innerTextField()
                }
                if (searchKey.isNotEmpty()) {
                    EInkText(
                        text = "清除",
                        modifier = Modifier
                            .clickable { onSearch("") }
                            .padding(start = EInkSpacing.s),
                        style = eInkTypography().labelLarge
                    )
                }
            }
        }
    )
    EInkHorizontalDivider()
}

@Composable
private fun SourceList(
    state: BookSourceUiState,
    onToggleSource: (BookSourcePart) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.sources, key = { it.bookSourceUrl }) { source ->
            SourceItem(source = source, onToggle = { onToggleSource(source) })
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun SourceItem(source: BookSourcePart, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(
                text = source.bookSourceName.ifBlank { source.bookSourceUrl },
                style = eInkTypography().bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            EInkText(
                text = source.bookSourceGroup ?: "",
                style = eInkTypography().bodySmall,
                color = eInkColorScheme().onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 启用状态（静态方框指示，符合 E-Ink 无动画规范）
        EInkText(
            text = if (source.enabled) "启用" else "禁用",
            style = eInkTypography().labelMedium,
            color = if (source.enabled) {
                eInkColorScheme().onSurface
            } else {
                eInkColorScheme().outline
            },
            modifier = Modifier.padding(start = EInkSpacing.s)
        )
    }
}
