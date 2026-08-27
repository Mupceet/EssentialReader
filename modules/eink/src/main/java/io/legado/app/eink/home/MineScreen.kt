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
import io.legado.app.eink.BuildConfig
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 无状态「我的」页 — 首页第二个 Tab。
 *
 * 承载进入完整模式的入口（导入导出等管理功能在完整模式中完成），
 * 内容为静态入口列表，无列表翻页（操作栏箭头置灰）。
 */
@Composable
internal fun MineScreen(
    onOpenFullMode: () -> Unit = {},
    onOpenThemeDebug: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MineEntry(label = "完整模式", onClick = onOpenFullMode)
        EInkHorizontalDivider()
        if (BuildConfig.DEBUG) {
            // 排版样式调试入口仅 debug 变体展示（编译期常量，release 中整个分支被移除）
            MineEntry(label = "排版样式调试", onClick = onOpenThemeDebug)
            EInkHorizontalDivider()
        }
    }
}

@Composable
private fun MineEntry(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
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
