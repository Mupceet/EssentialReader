package io.legado.app.eink.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkTypographySystem

/** 单条排版样式展示项：中文名 + 该级实际 TextStyle。 */
private data class TypographyRow(
    val label: String,
    val style: TextStyle,
)

/** 15 级排版样式清单（名称顺序与 EInkTypographySystem 一致）。 */
private fun typographyRows(typography: EInkTypographySystem): List<TypographyRow> = listOf(
    TypographyRow("展示 大", typography.displayLarge),
    TypographyRow("展示 中", typography.displayMedium),
    TypographyRow("展示 小", typography.displaySmall),
    TypographyRow("标题 大", typography.headlineLarge),
    TypographyRow("标题 中", typography.headlineMedium),
    TypographyRow("标题 小", typography.headlineSmall),
    TypographyRow("副标题 大", typography.titleLarge),
    TypographyRow("副标题 中", typography.titleMedium),
    TypographyRow("副标题 小", typography.titleSmall),
    TypographyRow("正文 大", typography.bodyLarge),
    TypographyRow("正文 中", typography.bodyMedium),
    TypographyRow("正文 小", typography.bodySmall),
    TypographyRow("标签 大", typography.labelLarge),
    TypographyRow("标签 中", typography.labelMedium),
    TypographyRow("标签 小", typography.labelSmall),
)

/** 字号数值文案，如 "40sp"；非 sp 单位时原样输出。 */
private fun TextUnit.sizeLabel(): String =
    if (isSp) "${value.toInt()}sp" else toString()

/**
 * 排版样式调试页 — 逐级展示 EInkTheme.typography 全部 15 级样式。
 *
 * 每行左侧用该级样式本身渲染中文名（直观对照实际效果），
 * 右侧数值列显示字号，如「标题 大 …… 40sp」；
 * display/headline/title/body/label 五组之间加分隔线。
 */
@Composable
fun ThemeDebugRoute(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(title = "排版样式调试", onBack = onBack)
        ThemeDebugScreen()
    }
}

/** 无状态排版样式清单。 */
@Composable
internal fun ThemeDebugScreen() {
    val rows = typographyRows(EInkTheme.typography)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, row -> row.label }) { index, row ->
            TypographyRowItem(row = row)
            // 每组 3 行，仅组间加分隔线
            if (index % 3 == 2 && index != rows.lastIndex) {
                EInkHorizontalDivider()
            }
        }
    }
}

@Composable
private fun TypographyRowItem(row: TypographyRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EInkText(text = row.label, style = row.style)
        Spacer(modifier = Modifier.weight(1f))
        EInkText(
            text = row.style.fontSize.sizeLabel(),
            style = EInkTheme.typography.bodyMedium,
            color = EInkTheme.colorScheme.onSurfaceVariant
        )
    }
}
