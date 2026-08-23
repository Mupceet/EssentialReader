package io.legado.app.eink.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.modifier.rememberImmediatePressState
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkTheme

/**
 * 统一的上/下翻页箭头组件。
 *
 * 视觉样式：上下箭头共同包在一个胶囊（长圆）边框内，中间用竖线分隔；
 * 竖线贯通胶囊上下边。单个箭头为宽 96dp、高 48dp 的大触控目标，
 * 不可用时置灰，按下瞬时反色（最短保持 120ms，规范 §35）。
 */
@Composable
fun EInkPageArrows(
    pageUpEnabled: Boolean,
    pageDownEnabled: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = EInkTheme.colorScheme
    Row(
        modifier = modifier
            .height(ArrowTouchTarget)
            .clip(CircleShape)
            .background(Color.Transparent)
            .border(width = CapsuleBorder, color = scheme.outline, shape = CircleShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrowIcon(
            iconRes = R.drawable.ic_keyboard_arrow_up,
            enabled = pageUpEnabled,
            contentDescription = "上一页",
            onClick = onPageUp
        )
        Box(
            modifier = Modifier
                .width(DividerWidth)
                .height(ArrowTouchTarget)
                .background(scheme.outline)
        )
        PageArrowIcon(
            iconRes = R.drawable.ic_keyboard_arrow_down,
            enabled = pageDownEnabled,
            contentDescription = "下一页",
            onClick = onPageDown
        )
    }
}

@Composable
private fun PageArrowIcon(
    iconRes: Int,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // 按压反色：共享 ImmediatePress（含 120ms 最短保持）+ 配色解析，规范 §35
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed, enabled = enabled)
    Box(
        modifier = Modifier
            .width(ArrowButtonWidth)
            .height(ArrowTouchTarget)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(ArrowIconSize),
            colorFilter = ColorFilter.tint(colors.contentColor)
        )
    }
}

/** 箭头按钮高度 / 胶囊高度。 */
private val ArrowTouchTarget = 40.dp

/** 单个箭头按钮宽度。 */
private val ArrowButtonWidth = 60.dp

/** 图标显示尺寸。 */
private val ArrowIconSize = 24.dp

/** 胶囊边框宽度。 */
private val CapsuleBorder = 1.dp

/** 上下箭头之间的竖向分隔线宽度。 */
private val DividerWidth = 1.dp
