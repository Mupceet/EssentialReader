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
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderSelectionDraft
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 书签编辑弹层：标题（预填选中文本）+ 内容（预填空），均可改；
 * 字段对应宿主 Bookmark.bookText / content。输入行参照
 * EInkSearchInputBar 的 BasicTextField 写法（输入行暂不沉淀 DS）。
 */
@Composable
internal fun ReaderBookmarkEditDialog(
    draft: ReaderSelectionDraft,
    onDismiss: () -> Unit,
    onConfirm: (bookText: String, content: String) -> Unit,
) {
    var bookText by remember { mutableStateOf(draft.bookmarkText) }
    var content by remember { mutableStateOf(draft.bookmarkContent) }
    EInkDialog(
        onDismiss = onDismiss,
        title = "添加书签",
        confirmText = "保存",
        onConfirm = { onConfirm(bookText, content) },
    ) {
        Column {
            EInkText(
                text = "标题",
                style = EInkTheme.typography.labelMedium,
            )
            BasicTextField(
                value = bookText,
                onValueChange = { bookText = it },
                textStyle = EInkTheme.typography.bodyMedium.copy(
                    color = EInkTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(EInkTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            EInkText(
                text = "内容",
                style = EInkTheme.typography.labelMedium,
            )
            BasicTextField(
                value = content,
                onValueChange = { content = it },
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
