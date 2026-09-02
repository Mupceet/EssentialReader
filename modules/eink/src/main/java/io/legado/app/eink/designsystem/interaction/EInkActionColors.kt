package io.legado.app.eink.designsystem.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * E-Ink 交互元素标准态配色（规范 §35 分层反馈语言）：
 *
 *  - pressed（瞬时态）：容器/内容反色 —— 唯一的按压反馈语言，
 *    黑白翻转是 A2/DU 快刷下最可靠、最显著的 1-bit 变换；
 *  - selected（持久态，小面积控件：Tab/开关/复选）：实心色块；
 *  - disabled：专用中灰 disabledContent，不用 alpha 混合（避免残影）；
 *  - 常态：透明容器 + onSurface 内容 / onSurfaceVariant 次级内容。
 *
 * 优先级：disabled > pressed > selected > 常态（按压反馈始终可见，
 * 禁用态不反色；按压瞬时覆盖选中，抬起后回到选中态）。
 *
 * 长列表行的持久选中不在此列：用左侧实心标记 + 标题加粗表达，
 * 禁止整行反色（规范 §42，大面积持久反色退出时残影重）。
 */
@Stable
data class EInkActionColors(
    val containerColor: Color,
    val contentColor: Color,
    /** 次级内容（元信息、箭头、弱化图标）：常态 onSurfaceVariant，随按压反色。 */
    val secondaryContentColor: Color,
)

/**
 * 解析交互元素当前态的容器/内容色。
 *
 * 所有按压反色实现必须复用本解析 +
 * [io.legado.app.eink.designsystem.interaction.rememberImmediatePressState]（含 120ms
 * 最短保持），不得在屏幕内自写反色配色逻辑（规范 §35 / §78 Rule 11）。
 *
 * @param pressed 是否处于按压态（来自 rememberImmediatePressState）
 * @param enabled 是否可用
 * @param selected 是否处于持久选中态（Tab、开关类小面积控件）
 */
@Composable
fun eInkActionColors(
    pressed: Boolean,
    enabled: Boolean = true,
    selected: Boolean = false,
): EInkActionColors {
    val scheme = EInkTheme.colorScheme
    return when {
        !enabled -> EInkActionColors(
            containerColor = Color.Transparent,
            contentColor = scheme.disabledContent,
            secondaryContentColor = scheme.disabledContent,
        )

        pressed -> EInkActionColors(
            containerColor = scheme.onSurface,
            contentColor = scheme.surface,
            secondaryContentColor = scheme.surface,
        )

        selected -> EInkActionColors(
            containerColor = scheme.selected,
            contentColor = scheme.selectedContent,
            secondaryContentColor = scheme.selectedContent,
        )

        else -> EInkActionColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurface,
            secondaryContentColor = scheme.secondaryContent,
        )
    }
}
