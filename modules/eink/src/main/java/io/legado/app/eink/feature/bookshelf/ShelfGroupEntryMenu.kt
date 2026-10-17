package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkSpacing
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 用户组聚合条目的管理对话框组（spec D11 修订，P1）：长按弹出动作菜单
 * （重命名/排序/解散），重命名与解散各带二级弹框，排序由调用方挂载
 * [GroupAdjustPanel]（菜单内不再逐组上移/下移）。
 * 三态互斥由调用方（HomeRoute）以局部状态驱动，同一时刻至多一个非空。
 */
@Composable
fun ShelfGroupEntryDialogs(
    menuGroup: BookshelfShelfEntryUiModel.Group?,
    renamingGroup: BookshelfShelfEntryUiModel.Group?,
    dissolvingGroup: BookshelfShelfEntryUiModel.Group?,
    onRename: (groupId: Long, name: String) -> Unit,
    onOpenAdjust: (BookshelfShelfEntryUiModel.Group) -> Unit,
    onDissolve: (groupId: Long) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRename: (BookshelfShelfEntryUiModel.Group) -> Unit,
    onSwitchToDissolve: (BookshelfShelfEntryUiModel.Group) -> Unit,
) {
    if (menuGroup != null) {
        EInkDialog(
            onDismiss = onDismiss,
            title = menuGroup.name,
            showActions = false,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MenuActionRow("重命名分组") { onSwitchToRename(menuGroup) }
                MenuActionRow("解散分组") { onSwitchToDissolve(menuGroup) }
                MenuActionRow("调整分组") { onOpenAdjust(menuGroup) }
            }
        }
    }
    if (renamingGroup != null) {
        GroupNameEditDialog(
            initialName = renamingGroup.name,
            title = "重命名分组",
            onConfirm = { onRename(renamingGroup.groupId, it) },
            onDismiss = onDismiss,
        )
    }
    if (dissolvingGroup != null) {
        EInkDialog(
            onDismiss = onDismiss,
            title = "解散分组",
            belowTitle = {
                EInkText(
                    text = "书籍保留，仅移出该分组。",
                    style = EInkTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                )
            },
            confirmText = "解散",
            onConfirm = { onDissolve(dissolvingGroup.groupId) },
        ) {}
    }
}

/**
 * 分组名编辑弹框（重命名/创建共用）：TextFieldValue 初始选区置行尾 +
 * 打开即聚焦——光标立现于文字末尾；空名确认禁用。
 */
@Composable
internal fun GroupNameEditDialog(
    initialName: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember(initialName) {
        mutableStateOf(
            TextFieldValue(
                text = initialName,
                selection = TextRange(initialName.length),
            )
        )
    }
    val blank = textValue.text.isBlank()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(initialName) { focusRequester.requestFocus() }
    EInkDialog(
        onDismiss = onDismiss,
        title = title,
        onConfirm = if (blank) null else ({ onConfirm(textValue.text) }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
        ) {
            BasicTextField(
                value = textValue,
                onValueChange = { textValue = it },
                singleLine = true,
                cursorBrush = SolidColor(EInkTheme.colorScheme.onSurface),
                textStyle = EInkTheme.typography.bodyLarge.copy(color = EInkTheme.colorScheme.onSurface),
                // 文本域自带焦点节点：仅挂 focusRequester（勿加 focusable，
                // 会另立焦点节点抢走 requestFocus，文本域仍无光标）
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }
}
