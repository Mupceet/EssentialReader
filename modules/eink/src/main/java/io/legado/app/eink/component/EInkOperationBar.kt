package io.legado.app.eink.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * 底部通用操作栏（参考微信读书墨水屏版）。
 *
 * 结构：左侧为页面 Tab（选中项以反白色块表达，无动画），右侧固定两个
 * 上/下箭头按钮，用于对当前界面的列表做整页翻页。
 *
 * E-Ink 约束：
 *  - 不可翻页（列表已到顶/到底、或当前内容不可翻页）时箭头置灰
 *    （[io.legado.app.eink.theme.EInkColorScheme.disabledContent] 中灰，
 *    非 alpha 混合，避免残影）；
 *  - 零动画：Tab 切换与翻页均为状态直接替换；
 *  - 触控目标 ≥48dp（边缘区域规范）。
 *
 * 该操作栏在各界面普遍存在；无 Tab 的界面传 [tabs] 为空列表即可，
 * 此时左侧不渲染 Tab，仅保留右侧翻页按钮。
 *
 * @param tabs Tab 标签文案列表（从左到右）
 * @param selectedTabIndex 当前选中的 Tab 下标
 * @param onTabSelect 点击 Tab 回调，参数为下标
 * @param pageUpEnabled 上翻（向列表上方翻一页）是否可用
 * @param pageDownEnabled 下翻是否可用
 * @param onPageUp 点击上翻箭头
 * @param onPageDown 点击下翻箭头
 */
@Composable
fun EInkOperationBar(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageUpEnabled: Boolean = false,
    pageDownEnabled: Boolean = false,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        EInkHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .padding(horizontal = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
        ) {
            tabs.forEachIndexed { index, label ->
                TabItem(
                    label = label,
                    selected = index == selectedTabIndex,
                    onClick = { onTabSelect(index) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            PageArrow(
                glyph = ArrowUpGlyph,
                enabled = pageUpEnabled,
                onClickLabel = "上一页",
                onClick = onPageUp
            )
            PageArrow(
                glyph = ArrowDownGlyph,
                enabled = pageDownEnabled,
                onClickLabel = "下一页",
                onClick = onPageDown
            )
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = eInkColorScheme()
    val backgroundColor = if (selected) colors.primary else Color.Transparent
    val contentColor = if (selected) colors.onPrimary else colors.onSurface

    Box(
        modifier = Modifier
            .background(color = backgroundColor, shape = EInkShapes.small)
            .clickable(
                enabled = !selected,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = eInkTypography().labelLarge.copy(color = contentColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PageArrow(
    glyph: String,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit
) {
    val scheme = eInkColorScheme()
    val color = if (enabled) scheme.onSurface else scheme.disabledContent

    Box(
        modifier = Modifier
            .size(ArrowTouchTarget)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = glyph,
            style = eInkTypography().titleLarge.copy(color = color)
        )
    }
}

/** 操作栏高度（触控目标 ≥48dp + 上下留白）。 */
private val BarHeight = 56.dp

/** 箭头按钮触控目标（边缘区域规范：48dp）。 */
private val ArrowTouchTarget = 48.dp

private const val ArrowUpGlyph = "▲"
private const val ArrowDownGlyph = "▼"
