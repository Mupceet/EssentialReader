package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/** 步进器加减按钮触控目标。 */
private val StepTouchTarget = 44.dp

/** 档位滑条行标签列宽默认值（容纳"上边距"三字并对齐各行滑条起点）。 */
private val SliderLabelWidth = 64.dp

/**
 * 档位滑条行：标签在左（可空，空时滑条占满），[−] 滑条 [+] 在右，
 * 当前数值印在滑块上。
 *
 * 滑条支持拖动选值与点按轨道跳档，[−]/[+] 为逐档精调（行内按值域钳制）。
 *
 * [labelWidth] 为标签列的最小宽度：短于此的标签按最小宽对齐各行滑条
 * 起点，更长的标签（四字以上/字体缩放）按实际宽展开、不折行。
 *
 * 自阅读排版面板提升（原 feature.reader 私有 SliderRow，纯移动改名），
 * 书架个性化样式面板复用。
 */
@Composable
fun EInkSliderRow(
    label: String?,
    value: Int,
    valueRange: IntRange,
    thumbLabel: (Int) -> String,
    tickStep: Int,
    onSetValue: (Int) -> Unit,
    labelWidth: Dp = SliderLabelWidth,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        if (label != null) {
            EInkText(
                text = label,
                modifier = Modifier.widthIn(min = labelWidth),
                style = EInkTheme.typography.bodyMedium,
            )
        }
        EInkButton(
            text = "−",
            onClick = { onSetValue((value - 1).coerceIn(valueRange.first, valueRange.last)) },
            modifier = Modifier.size(StepTouchTarget),
            bordered = false,
            height = null,
            style = EInkTheme.typography.titleLarge,
            onClickLabel = "减小",
        )
        EInkSteppedSlider(
            value = value,
            onValueChange = onSetValue,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            thumbLabel = thumbLabel,
            tickStep = tickStep,
        )
        EInkButton(
            text = "＋",
            onClick = { onSetValue((value + 1).coerceIn(valueRange.first, valueRange.last)) },
            modifier = Modifier.size(StepTouchTarget),
            bordered = false,
            height = null,
            style = EInkTheme.typography.titleLarge,
            onClickLabel = "增大",
        )
    }
}
