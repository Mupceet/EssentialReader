package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.eink.designsystem.content.EInkText
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
