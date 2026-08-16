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
import io.legado.app.eink.theme.EInkTheme

/**
 * 底部通用操作栏（参考微信读书墨水屏版）。
 *
 * 结构：左侧为页面 Tab（选中项以反白色块表达，无动画），右侧固定
 * 上/下翻页箭头。上下箭头统一使用 [EInkPageArrows]，包在胶囊边框内，
 * 中间以竖线分隔。
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
 * @param navigationIcon 最左侧导航槽（如返回按钮），非首页常用；与 Tab 可并存
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
    navigationIcon: (@Composable () -> Unit)? = null,
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
                .padding(horizontal = EInkSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
        ) {
            navigationIcon?.invoke()
            if (tabs.isEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                tabs.forEachIndexed { index, label ->
                    TabItem(
                        label = label,
                        selected = index == selectedTabIndex,
                        onClick = { onTabSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            EInkPageArrows(
                pageUpEnabled = pageUpEnabled,
                pageDownEnabled = pageDownEnabled,
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    val backgroundColor = if (selected) colors.primary else Color.Transparent
    val contentColor = if (selected) colors.onPrimary else colors.onSurface

    Box(
        modifier = modifier
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
            style = EInkTheme.typography.labelLarge.copy(color = contentColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 操作栏高度（触控目标 ≥48dp + 上下留白）。 */
private val BarHeight = 56.dp
