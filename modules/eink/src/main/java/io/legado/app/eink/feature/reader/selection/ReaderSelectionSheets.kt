package io.legado.app.eink.feature.reader.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 想法弹层（v2，设计 §5）：顶部只读预览选中文本 + 想法输入 + 保存/取消。
 * 确认 = saveMarking(thought=true, note)——同锚点落库为原地更新，松手已落
 * 的划线由此转换为想法（虚线）；成功无 toast（重排后新快照虚线呈现即反馈），
 * 失败由调用方提示并保留弹层可重试。
 *
 * [thoughtText] 为输入预填值：松手新建场景为空串；点按编辑场景（Task 6）
 * 预填 findMarking 的 note。预览文本取本地选区/快照命中 run，不经端口。
 * 输入行参照 EInkSearchInputBar 的 BasicTextField 写法（输入行暂不沉淀
 * DS）；IME 避让由 EInkDialog 自带 imePadding 承担。
 */
@Composable
internal fun ReaderThoughtDialog(
    thoughtText: String,
    selectedText: String,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit,
) {
    var note by remember { mutableStateOf(thoughtText) }
    EInkDialog(
        onDismiss = onDismiss,
        title = "写想法",
        confirmText = "保存",
        onConfirm = { onConfirm(note) },
    ) {
        Column {
            EInkText(
                text = "选中内容",
                style = EInkTheme.typography.labelMedium,
            )
            // 只读预览：选区上限为一页文本，超长时截断展示（落库不受影响）
            EInkText(
                text = selectedText,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            EInkText(
                text = "想法",
                style = EInkTheme.typography.labelMedium,
            )
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                textStyle = EInkTheme.typography.bodyMedium.copy(
                    color = EInkTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(EInkTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
    }
}
