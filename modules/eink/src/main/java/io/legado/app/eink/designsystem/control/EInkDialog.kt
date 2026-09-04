package io.legado.app.eink.designsystem.control

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 通用对话框外壳（DS 规范 §20 E-Ink Native）：白底面板 + 1dp 实线 border +
 * 全屏透明点击层，页内组装、不使用独立弹框窗口——无系统 dim/动画/shadow
 * 问题面，打开/关闭与内容变化全部走应用侧 eink 刷新管线；页面离开时弹框
 * 随组合卸载，无窗口泄漏。
 *
 * 两种形态：
 * - 确认弹框（默认）：标题 + [content] + 取消/确认按钮（§35 按压反色；
 *   [onConfirm] 传 null 时确认按钮呈禁用态）。
 * - 面板弹框：[onClose] 非空时标题行右侧显示 × 关闭钮并在标题行下加分隔线；
 *   [showActions] = false 隐藏底部按钮组（排版调参等实时预览场景，背后
 *   内容不被遮盖）。
 *
 * 收起路径：系统返回 → [onDismiss]；弹框外点击 → [onBackdropClick]（默认与
 * [onDismiss] 一致，供「逐级回退」与「一次收起」分离的调用方使用）；
 * × → [onClose]。
 *
 * 组合契约：必须组合在全屏容器（Screen 根 Box）的子级，不得放进滚动或
 * 受限容器——覆盖与点击拦截范围以组合位置为准。内容（表单、输入行、
 * 说明文字）由调用方在 [content] 插槽组合；输入行暂不沉淀 DS（等第二个
 * 消费者），参照 EInkSearchBar 的 BasicTextField decorationBox 写法自行组合。
 */
@Composable
fun EInkDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String = "确定",
    cancelText: String = "取消",
    onConfirm: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onBackdropClick: (() -> Unit)? = null,
    showActions: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 系统返回 = 逐级回退的 onDismiss：组合期注册、收起随组合注销；
    // 晚于页面自身 BackHandler 组合，弹框打开期间优先接管返回键
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 透明点击层承担模态拦截：点弹框外收起，面板外内容不可交互
            .einkClickable(
                role = Role.Button,
                onClickLabel = "关闭",
                onClick = onBackdropClick ?: onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(EInkTheme.colorScheme.surface, shape = EInkShapes.medium)
                .border(1.dp, EInkTheme.colorScheme.outline, EInkShapes.medium)
                // 消费面板内空白处点击，避免透传到点击层误关
                .einkClickable(onClick = {})
            // 内边距下放到各行级：面板形态的分隔线要通到面板左右边缘
        ) {
            Row(
                modifier = Modifier.padding(
                    start = EInkSpacing.m,
                    end = EInkSpacing.m,
                    top = EInkSpacing.m,
                    bottom = if (onClose != null) EInkSpacing.s else EInkSpacing.m
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EInkText(
                    text = title,
                    style = EInkTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                onClose?.let { EInkCloseButton(onClose = it) }
            }
            if (onClose != null) {
                EInkHorizontalDivider()
            }
            // Column 而非 Box：内容插槽可平铺多个兄弟组件（如滑条行组），
            // 逐行纵向堆叠
            Column(
                modifier = Modifier.padding(
                    start = EInkSpacing.m,
                    end = EInkSpacing.m,
                    bottom = if (showActions) 0.dp else EInkSpacing.m
                )
            ) {
                content()
            }
            if (showActions) {
                Row(
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.m,
                        bottom = EInkSpacing.m
                    ),
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s)
                ) {
                    EInkButton(
                        text = cancelText,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                    EInkButton(
                        text = confirmText,
                        enabled = onConfirm != null,
                        onClick = { onConfirm?.invoke() },
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

/**
 * 标题行关闭钮（×）：44dp 触控目标、按压反色（§35）。对话框与底部面板
 * 的标题行共用，module 内部组件。
 */
@Composable
internal fun EInkCloseButton(onClose: () -> Unit) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = "×",
            style = EInkTheme.typography.titleLarge,
            color = colors.secondaryContentColor,
        )
    }
}
