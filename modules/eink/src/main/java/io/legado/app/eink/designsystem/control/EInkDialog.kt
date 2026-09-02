package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 通用对话框外壳：标题 + 内容插槽 + 取消/确认按钮（DS 规范 §35 按压反色；
 * [onConfirm] 传 null 时确认按钮呈禁用态）。
 *
 * 内容（表单、输入行、说明文字）由调用方在 [content] 插槽组合；输入行
 * 暂不沉淀 DS（等第二个消费者），参照 EInkSearchBar 的 BasicTextField
 * decorationBox 写法自行组合。
 */
@Composable
fun EInkDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String = "确定",
    cancelText: String = "取消",
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkTheme.colorScheme.surface, shape = EInkShapes.medium)
                .border(1.dp, EInkTheme.colorScheme.outline, EInkShapes.medium)
                .padding(EInkSpacing.m)
        ) {
            EInkText(
                text = title,
                style = EInkTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = EInkSpacing.m)
            )
            content()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = EInkSpacing.m),
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s)
            ) {
                DialogButton(
                    text = cancelText,
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                DialogButton(
                    text = confirmText,
                    enabled = onConfirm != null,
                    onClick = { onConfirm?.invoke() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 对话框按钮：44dp 高、描边、按压反色；禁用时弱化且不可点。 */
@Composable
private fun DialogButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed, enabled = enabled)
    val borderColor = if (enabled) {
        EInkTheme.colorScheme.outline
    } else {
        EInkTheme.colorScheme.disabledContent
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .then(press.modifier)
            .background(colors.containerColor, EInkShapes.small)
            .border(1.dp, borderColor, EInkShapes.small)
            .then(
                if (enabled) {
                    Modifier.einkClickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.labelLarge,
            color = colors.contentColor
        )
    }
}
