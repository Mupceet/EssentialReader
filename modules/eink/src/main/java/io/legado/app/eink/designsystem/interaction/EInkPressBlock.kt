package io.legado.app.eink.designsystem.interaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/**
 * 按压反色块原语（规范 §35 分层反馈的唯一机制实现）。
 *
 * 一站式封装按压反色的全部机制：即时按压状态
 * （[rememberImmediatePressState]，含 120ms 最短保持）、标准态配色解析
 * （[eInkActionColors]：disabled > pressed > selected > 常态）、容器背景
 * 与点击。屏幕与组件不得再手写这套样板（规范 §35 / §78 Rule 11，
 * 同 [eInkActionColors] 的复用要求）。
 *
 * 内容经 [content] 槽接收**已解析的当前态配色**（容器/内容/次级内容三
 * 色）与按压位——整块反色时子内容颜色随容器一致翻转，调用方无需自行
 * 传播按压态；选中标记等按压相关派生色用 [content] 的 pressed 位
 * 自行计算。
 *
 * 尺寸、边距、形状裁剪等布局属性由调用方经 [modifier] 与 [shape] 决定；
 * 原语只在修饰链上追加按压状态、背景与点击（顺序：
 * `modifier → press → background → clickable`），行为与既有手写样板
 * 逐字节一致。
 *
 * @param onClick 点击回调（[enabled] 为 false 时不响应）
 * @param modifier 布局定位与尺寸（如 weight / height / size）；原语的
 *   背景与点击区域即本修饰链最终边界
 * @param enabled 禁用态：中灰弱化且不可点，不参与按压反色
 * @param selected 持久选中态（小面积控件）：实心色块；按压瞬时覆盖选中
 * @param shape 背景形状（默认直角——按压块可贴容器边缘）
 * @param role 无障碍语义角色
 * @param onClickLabel 无障碍点击描述
 * @param contentAlignment 内容对齐（默认居中）
 * @param content 内容槽：参数为当前态配色与按压位
 */
@Composable
fun EInkPressBlock(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = RectangleShape,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (colors: EInkActionColors, pressed: Boolean) -> Unit,
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(
        pressed = press.isPressed,
        enabled = enabled,
        selected = selected,
    )
    Box(
        modifier = modifier
            .then(press.modifier)
            .background(color = colors.containerColor, shape = shape)
            .einkClickable(
                enabled = enabled,
                role = role,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
    ) {
        content(colors, press.isPressed)
    }
}
