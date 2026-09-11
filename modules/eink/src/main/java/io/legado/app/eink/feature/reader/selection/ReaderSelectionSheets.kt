package io.legado.app.eink.feature.reader.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 想法弹层（v2.2 统一，设计 §5）：顶部只读预览选中文本 + 想法输入 +
 * 底部保存/取消。**新建与编辑共用同一份**，唯一差别是 [thoughtText] 预填
 * （编辑带出宿主记录的笔记内容，新建/划线转想法为空串）——复制/删除在操作条
 * 上（复制、想法、删除三键），弹框内不再重复放附加动作钮。
 *
 * 确认 = 调用方按**内容**判类型落库：note 非空 = 想法（虚线），清空 =
 * 划线（实线，想法清空内容即自动变回划线）；同锚点 upsert 为原地更新。
 * 成功无 toast（重排后装饰呈现即反馈），失败由调用方提示并保留弹层可重试。
 *
 * 形态（v2.1 交互优化轮）：**顶部大弹框 + 进入即聚焦拉起输入法**——输入区
 * 不会被键盘顶走，用户选择「想法」后可直接打字，底部保持取消/保存。
 * 预览文本取本地选区/快照命中 run，不经端口。输入行参照 EInkSearchInputBar
 * 的 BasicTextField 写法（输入行暂不沉淀 DS）；IME 避让由 EInkDialog 自带
 * imePadding 承担。
 *
 * @param topInset 顶部避让（状态栏高度快照）：卡片贴顶但不压在状态栏下
 */
@Composable
internal fun ReaderThoughtDialog(
    thoughtText: String,
    selectedText: String,
    onDismiss: () -> Unit,
    onConfirm: (note: String) -> Unit,
    topInset: Dp = 0.dp,
) {
    var note by remember { mutableStateOf(thoughtText) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 进入即聚焦并拉起输入法：想法弹层的唯一目的就是输入（EDIT 模式同理，
    // 用户多为改动内容而来）；聚焦失败（无 IME 的墨水屏设备）静默降级为
    // 普通可点输入区，不阻塞弹层
    LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
            .onSuccess { keyboard?.show() }
    }
    EInkDialog(
        onDismiss = onDismiss,
        title = "写想法",
        confirmText = "保存",
        onConfirm = { onConfirm(note) },
        contentAlignment = Alignment.TopCenter,
        panelPadding = PaddingValues(top = topInset + 16.dp),
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
            // 大输入区：3 行起、6 行封顶；**超出即向上滚动**——想法写长时
            // 输入区自身滚动，而不是把标题/预览/底部按钮挤走（真机反馈
            // 「内容被挤压」）。光标随输入滚动由 Compose 的 bring-into-view
            // 沿本滚动容器处理；描边走 DS outline 实灰（非 alpha）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(EInkTheme.colorScheme.surface, shape = EInkShapes.small)
                    .border(1.dp, EInkTheme.colorScheme.outline, EInkShapes.small)
                    .padding(8.dp)
                    .heightIn(min = 96.dp, max = 168.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
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
                        .focusRequester(focusRequester),
                )
            }
        }
    }
}
