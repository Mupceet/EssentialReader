package io.legado.app.eink.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 搜索条（首页提示样式与搜索页输入样式共用同一外壳）。
 *
 * 几何规格固定：外层高 64dp + 水平 16dp 内边距，输入框高 44dp、
 * 1dp 描边小圆角，右侧可选动作槽。首页点击提示条进入搜索页后，
 * 输入条落在完全相同的位置，视觉上是"同一个框"从未移动。
 */

/**
 * 搜索提示条（首页）：不可输入，整条可点击，点击进入搜索页。
 */
@Composable
fun EInkSearchHintBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索书名 / 作者",
) {
    SearchBarShell(
        modifier = modifier.clickable(onClick = onClick),
        input = {
            EInkText(
                text = hint,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = EInkSpacing.m)
            )
        }
    )
}

/**
 * 搜索输入条（搜索页）：聚焦时拉起输入法，右侧为动作槽（搜索/停止按钮）。
 *
 * @param onImeAction 输入法"搜索"键回调（与点击搜索按钮同一触发）
 * @param autoFocus 进入即聚焦并拉起输入法
 */
@Composable
fun EInkSearchInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索书名 / 作者",
    onImeAction: () -> Unit = {},
    autoFocus: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val focusRequester = FocusRequester()
    if (autoFocus) {
        // 只在该搜索条首次进入时自动聚焦拉起输入法；从详情页等返回时
        // 不再抢焦点（rememberSaveable 跨返回保留），除非用户主动点击输入框。
        var autoFocused by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(focusRequester) {
            if (!autoFocused) {
                autoFocused = true
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
    SearchBarShell(
        modifier = modifier,
        input = {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = EInkTheme.typography.bodyMedium.copy(
                    color = EInkTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(EInkTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onImeAction() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = EInkSpacing.m),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            EInkText(
                                text = hint,
                                style = EInkTheme.typography.bodyMedium,
                                color = EInkTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
        },
        trailing = action
    )
}

/**
 * 搜索条外壳：64dp 行高 + 16dp 水平内边距 + 44dp 描边输入框 + 可选尾部动作。
 */
@Composable
private fun SearchBarShell(
    modifier: Modifier = Modifier,
    input: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SearchBarHeight)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(SearchInputHeight)
                .border(
                    width = 1.dp,
                    color = EInkTheme.colorScheme.outline,
                    shape = EInkShapes.small
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            input()
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(EInkSpacing.m))
            trailing()
        }
    }
}

private val SearchBarHeight = 64.dp
private val SearchInputHeight = 44.dp
