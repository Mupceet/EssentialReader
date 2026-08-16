package io.legado.app.eink.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.modifier.rememberImmediatePressState
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkTheme

/**
 * E-Ink 标准图标按钮：单个图标的方形按钮（48dp 触控目标 + 24dp 图标）。
 *
 * 触摸反馈（规范 §35）：按下瞬时反色（容器 = 图标色、图标 = 表色），
 * 抬起恢复；pointer 手势层直接驱动，不受滚动容器派发延迟影响，
 * 零涟漪零动画，仅离散状态替换。
 *
 * 图标常规态取 [tint]（默认 onSurface），按压反色由组件内部处理，
 * 调用方无需自行组装按压态。需要图标置灰等弱化态时传较浅的 tint
 * （如 onSurfaceVariant），按压时同样按该色反色，保持可读。
 */
@Composable
fun EInkIconButton(
    onClick: () -> Unit,
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = EInkTheme.colorScheme.onSurface,
    touchTarget: Dp = TouchTarget,
    iconSize: Dp = IconSize,
) {
    val press = rememberImmediatePressState()
    Box(
        modifier = modifier
            .size(touchTarget)
            .then(press.modifier)
            .background(if (enabled && press.isPressed) tint else Color.Transparent)
            .staticClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(
                if (enabled && press.isPressed) {
                    EInkTheme.colorScheme.surface
                } else {
                    tint
                }
            )
        )
    }
}

/** 图标按钮触控目标（边缘区规范 48dp）。 */
private val TouchTarget = 48.dp

/** 图标绘制尺寸。 */
private val IconSize = 24.dp
