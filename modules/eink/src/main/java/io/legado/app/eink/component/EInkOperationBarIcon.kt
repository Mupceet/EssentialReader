package io.legado.app.eink.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.modifier.rememberImmediatePressState
import io.legado.app.eink.modifier.staticClickable

/**
 * 操作栏图标按钮（公共组件，规范 §35/§42 操作条图标按钮层）。
 *
 * 尺寸：高度默认撑满底部通用操作栏 [EInkOperationBar]（56dp），
 * 可用 [height]/[iconSize] 覆写。宽度默认自适应（[width] 传 null）：
 * 假设 [AdaptiveButtonCount] 枚按钮均分屏幕宽度；屏幕足够宽
 * （均分结果超过 1.7 倍高度，约 95dp）时收敛为 1.7 倍高度，
 * 避免宽屏上按钮过宽。
 *
 * 分层反馈（规范 §35/§42）：
 *  - 选中：提供 [selectedIcon] 填充变体素材（`_e`/`_s` 素材对）时，
 *    容器保持白底，仅图标切换为填充变体；未提供素材对时保持
 *    实心色块（primary/onPrimary）选中。无选中语义的按钮（如返回）
 *    不传 [selectedIcon] 且 [selected] 恒为 false；
 *  - 按压：瞬时反色（容器 onSurface / 图标 surface，含 120ms 最短
 *    保持），按压期间覆盖选中样式；
 *  - 禁用：中灰 disabledContent，容器透明。
 */
@Composable
fun EInkOperationBarIcon(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIcon: Painter? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    role: Role = Role.Button,
    width: Dp? = null,
    height: Dp = DefaultHeight,
    iconSize: Dp = DefaultIconSize,
) {
    val press = rememberImmediatePressState()
    // 有素材对时选中只切换素材，配色保持白底；
    // 无素材对时选中回落实心色块（共享配色解析，规范 §35）
    val colors = eInkActionColors(
        pressed = press.isPressed,
        enabled = enabled,
        selected = selected && selectedIcon == null
    )
    // 宽度自适应：min(屏幕宽 / 6, 1.7 × 高度)。手机类窄屏均分
    // 屏宽（触控目标仍 ≥48dp），宽屏收敛为固定规格
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val resolvedWidth = width ?: minOf(
        screenWidth / AdaptiveButtonCount,
        height * DefaultWidthRatio
    )
    Box(
        modifier = modifier
            .width(resolvedWidth)
            .height(height)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(
                enabled = enabled,
                role = role,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = if (selected && selectedIcon != null) selectedIcon else icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(colors.contentColor)
        )
    }
}

/** 默认尺寸对齐底部通用操作栏（EInkOperationBar，56dp 高），需与该组件 BarHeight 保持同步。 */
private val DefaultHeight = 56.dp

/** 宽度自适应基准：假设的操作条按钮数量（均分屏幕宽度）。 */
private const val AdaptiveButtonCount = 5

/** 宽度收敛上限相对高度的倍数（约 95dp，暂定可微调）。 */
private const val DefaultWidthRatio = 1.7f

/** 默认图标尺寸：默认高度的 50%（与 48dp 按钮配 24dp 图标同比例）。 */
private val DefaultIconSize = 28.dp
