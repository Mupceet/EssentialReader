package io.legado.app.eink.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.roundToInt

/**
 * 更新弹层内嵌进度行（独立组件，[EInkApp] 根层更新弹框消费）：
 * 1dp 细线常驻于行高中点，进度以反色百分比小牌（黑底白字「N%」）
 * 对称跨线居中显示，位置按进度在内容轨内钳制两端。
 *
 * 行高由小牌真实文本测量撑起（非固定值/公式推导，任意 fontScale
 * 下都完整包含小牌）；未下载/总长未知（[percent] < 0）时以不可见
 * 占位牌（同尺寸文本）保持行高，小牌出现/移动不引起布局跳动。
 * 纯静态呈现（无动画，eink 刷新按上游状态粒度触发）。
 *
 * 组合契约：必须放入 [io.legado.app.eink.designsystem.control.EInkDialog]
 * 的 belowTitle 面板级插槽（无横向内边距），细线才能通到面板左右
 * 边缘；小牌行程自行内缩 [EInkSpacing.m] 与正文对齐。
 */
@Composable
internal fun EInkAppUpdateProgressRow(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val visible = percent >= 0
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = EInkSpacing.s)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.CenterStart)
                .background(EInkTheme.colorScheme.outline)
        )
        // 不可见占位与实牌同测量（等高文本）：行高恒定且小牌完整居中
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = EInkSpacing.m)) {
            Box(
                modifier = Modifier
                    .progressMarker(percent)
                    .background(
                        if (visible) EInkTheme.colorScheme.onSurface else Color.Transparent
                    )
                    .padding(horizontal = EInkSpacing.s),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(
                    text = if (visible) "$percent%" else "0%",
                    style = EInkTheme.typography.bodyMedium,
                    color = if (visible) EInkTheme.colorScheme.surface else Color.Transparent,
                )
            }
        }
    }
}

/**
 * 百分比小牌定位：水平在行程宽度内按进度移动并钳制两端；垂直铺满
 * 行高（行高即小牌测量高度），小牌以行高中点的分隔线为轴对称跨线。
 */
private fun Modifier.progressMarker(percent: Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(
                progressMarkerOffset(constraints.maxWidth, placeable.width, percent),
                0
            )
        }
    }

/** 小牌左缘偏移：进度映射到 [0, 行程宽-牌宽]，越界百分比钳制。 */
internal fun progressMarkerOffset(trackWidth: Int, markerWidth: Int, percent: Int): Int {
    val maxOffset = (trackWidth - markerWidth).coerceAtLeast(0)
    return (maxOffset * percent / 100f).roundToInt().coerceIn(0, maxOffset)
}
