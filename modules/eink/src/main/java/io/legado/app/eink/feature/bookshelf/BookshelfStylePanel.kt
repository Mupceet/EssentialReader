package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.control.EInkSliderRow
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 书架个性化配置面板（决策补充 7）：EInkDialog 居中卡片、关 scrim——书架
 * 实时可见，每档改动即提交（[onStyleChange] 传改后完整快照，VM 乐观层
 * 承接）。样式参考阅读页排版面板：档位滑条 + 选中态按钮行，零动画。
 *
 * 行构成：布局（网格/列表）、未读角标 / 新章高亮 / 最新章节（开/关）、
 * 封面宽（40..150dp 步进 5）、书名行数（1..5 步进 1）。
 */
@Composable
fun BookshelfStylePanel(
    style: BookshelfStyle,
    onStyleChange: (BookshelfStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    EInkDialog(
        onDismiss = onDismiss,
        title = "书架样式",
        showActions = false,
    ) {
        BinaryRow(
            label = "布局",
            value = style.isGridLayout,
            onChange = { onStyleChange(style.copy(isGridLayout = it)) },
            positiveText = "网格",
            negativeText = "列表",
        )
        BinaryRow("未读角标", style.showUnreadBadge, { onStyleChange(style.copy(showUnreadBadge = it)) })
        BinaryRow("新章高亮", style.highlightNewChapter, { onStyleChange(style.copy(highlightNewChapter = it)) })
        BinaryRow("最新章节", style.showLatestChapter, { onStyleChange(style.copy(showLatestChapter = it)) })
        EInkSliderRow(
            label = "封面宽",
            value = style.gridCoverWidth,
            valueRange = 40..150,
            thumbLabel = { "${it}dp" },
            tickStep = 5,
            onSetValue = { onStyleChange(style.copy(gridCoverWidth = it)) },
        )
        EInkSliderRow(
            label = "书名行数",
            value = style.titleMaxLines,
            valueRange = 1..5,
            thumbLabel = { "${it}行" },
            tickStep = 1,
            onSetValue = { onStyleChange(style.copy(titleMaxLines = it)) },
        )
    }
}

/**
 * 布尔配置行：标签 + 双枚按钮，当前态实心反白（[EInkButton.selected]）。
 * 按钮文案默认开/关，二选一语义行（如布局）经 [positiveText]/[negativeText]
 * 自定义（网格/列表）。
 */
@Composable
private fun BinaryRow(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    positiveText: String = "开",
    negativeText: String = "关",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        EInkButton(
            text = positiveText,
            selected = value,
            onClick = { onChange(true) },
        )
        EInkButton(
            text = negativeText,
            selected = !value,
            onClick = { onChange(false) },
        )
    }
}
