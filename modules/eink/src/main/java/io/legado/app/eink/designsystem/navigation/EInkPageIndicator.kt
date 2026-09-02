package io.legado.app.eink.designsystem.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 数字式页码指示器（规范 §27）：静态文本「第 X / Y 页」。
 *
 * 低视觉成本组件：无点阵、无进度动画、无滑动指示——每次翻页只发生
 * 一次文本替换，E-Ink 控制器单次刷新即可呈现。页数不大于 1 时不渲染。
 *
 * 页码换算约定：调用方由 [EInkPageController] 的 pageStart/pageItemCount
 * 计算（pageStart / pageItemCount + 1 为当前页，总数向上取整），
 * 本组件只负责展示。
 *
 * @param currentPage 当前页（1 起始）
 * @param pageCount 总页数
 * @param modifier Modifier for the indicator text
 */
@Composable
fun EInkPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return

    EInkText(
        text = "第 $currentPage / $pageCount 页",
        style = EInkTheme.typography.labelLarge,
        color = EInkTheme.colorScheme.onSurface,
        modifier = modifier.padding(EInkSpacing.s)
    )
}
