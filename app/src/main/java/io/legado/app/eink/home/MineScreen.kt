package io.legado.app.eink.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.legado.app.eink.component.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 无状态「我的」页 — 首页第二个 Tab。
 *
 * 承载原先书架顶栏的入口（书源管理、阅读设置），
 * 内容为静态入口列表，无列表翻页（操作栏箭头置灰）。
 */
@Composable
internal fun MineScreen(
    onBookSource: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EInkText(
            text = "我的",
            style = EInkTheme.typography.titleLarge,
            modifier = Modifier.padding(
                horizontal = EInkSpacing.m,
                vertical = EInkSpacing.m
            )
        )
        EInkHorizontalDivider()
        MineEntry(label = "书源管理", onClick = onBookSource)
        EInkHorizontalDivider()
        MineEntry(label = "阅读设置", onClick = onSettings)
        EInkHorizontalDivider()
    }
}

@Composable
private fun MineEntry(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.screenHorizontal, vertical = EInkSpacing.l),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EInkText(text = label, style = EInkTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        EInkText(
            text = ">",
            style = EInkTheme.typography.titleMedium,
            color = EInkTheme.colorScheme.onSurfaceVariant
        )
    }
}
