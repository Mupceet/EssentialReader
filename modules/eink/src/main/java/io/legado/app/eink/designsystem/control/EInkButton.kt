package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * E-Ink 通用按钮：唯一的可交互文本元素（EInkText 只负责展示）。
 *
 * 两种形态（DS 规范 §35/§42）：
 *  - [bordered] = true（默认）：1dp 描边 + 小圆角，如对话框按钮、页签、
 *    动作按钮、开/关状态块（[selected] = checked）；
 *  - [bordered] = false：无描边矩形按压反色块，如顶栏可点击标题、
 *    章节步进、± 步进按钮——按压背景为直角矩形（可贴容器边缘）。
 *
 * 状态：按压瞬时反色、选中实心反白、禁用弱化不可点；描边形态下
 * 实心态边框取容器色与色块融合，常态为轮廓线，禁用弱化。
 * 宽度与布局定位（占满/weight/固定尺寸）由调用方经 [modifier] 决定。
 *
 * @param text 按钮文案
 * @param onClick 点击回调（[enabled] 为 false 时不响应）
 * @param modifier 布局定位（如 fillMaxWidth / weight / size）
 * @param enabled 禁用态：弱化且不可点
 * @param selected 选中态：实心反白（如运行中/当前页签/开关开启）
 * @param bordered 描边形态；false 为无描边直角按压块
 * @param height 按钮高度（对话框按钮 44dp、页签 40dp、常规 48dp）；
 *   null 时不约束高度，由 [modifier] 决定（如 size / fillMaxHeight）
 * @param style 文案字号
 * @param role 无障碍语义角色
 * @param onClickLabel 无障碍点击描述
 * @param contentPadding 文案内边距（在按压背景之内；如顶栏标题的
 *   start 边距、章节按钮的水平边距）
 * @param contentAlignment 文案对齐（默认居中；顶栏标题起始对齐）
 */
@Composable
fun EInkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    bordered: Boolean = true,
    height: Dp? = 48.dp,
    style: TextStyle = EInkTheme.typography.labelLarge,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(
        pressed = press.isPressed,
        enabled = enabled,
        selected = selected,
    )
    // 描边形态：实心态（按压/选中）边框取容器色，禁用弱化，常态为轮廓线
    val borderColor = when {
        !enabled -> scheme.disabledContent
        colors.containerColor != Color.Transparent -> colors.containerColor
        else -> scheme.outline
    }
    // 无描边形态：直角矩形（按压背景可贴容器边缘），不做圆角
    val shape = if (bordered) EInkShapes.small else RectangleShape
    Box(
        modifier = modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .then(press.modifier)
            .background(color = colors.containerColor, shape = shape)
            .then(
                if (bordered) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier
                }
            )
            .einkClickable(
                enabled = enabled,
                role = role,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        EInkText(
            text = text,
            color = colors.contentColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
