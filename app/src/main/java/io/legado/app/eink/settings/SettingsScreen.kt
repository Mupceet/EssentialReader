package io.legado.app.eink.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.EInkTopBar
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 设置动作回调集（Route 提供，Screen 只调用）。
 */
data class SettingsActions(
    val onTextSize: (Int) -> Unit,
    val onLineSpacing: (Int) -> Unit,
    val onLetterSpacing: (Float) -> Unit,
    val onParagraphSpacing: (Int) -> Unit,
    val onPaddingHorizontal: (Int) -> Unit,
    val onPaddingTop: (Int) -> Unit,
    val onPaddingBottom: (Int) -> Unit,
    val onReset: () -> Unit,
)

/**
 * 设置 Route — ViewModel 感知层。
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = SettingsActions(
        onTextSize = viewModel::setTextSize,
        onLineSpacing = viewModel::setLineSpacing,
        onLetterSpacing = viewModel::setLetterSpacing,
        onParagraphSpacing = viewModel::setParagraphSpacing,
        onPaddingHorizontal = { delta -> viewModel.setPadding(
            left = uiState.paddingLeft + delta,
            right = uiState.paddingRight + delta
        ) },
        onPaddingTop = { delta -> viewModel.setPadding(top = uiState.paddingTop + delta) },
        onPaddingBottom = { delta -> viewModel.setPadding(bottom = uiState.paddingBottom + delta) },
        onReset = viewModel::resetToDefault
    )

    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(title = "设置", onBack = onBack)
        SettingsScreen(state = uiState, actions = actions)
    }
}

/**
 * 无状态设置 Screen。
 *
 * 每项为: 标签 + [－] 值 [＋] 步进器（静态边框按钮，E-Ink 规范 §11）。
 */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = EInkSpacing.m)
    ) {
        SectionHeader("排版")
        StepperItem(
            label = "字号",
            value = "${state.textSize} sp",
            onDecrement = { actions.onTextSize(state.textSize - 1) },
            onIncrement = { actions.onTextSize(state.textSize + 1) }
        )
        StepperItem(
            label = "行距",
            value = "${state.lineSpacingExtra} dp",
            onDecrement = { actions.onLineSpacing(state.lineSpacingExtra - 1) },
            onIncrement = { actions.onLineSpacing(state.lineSpacingExtra + 1) }
        )
        StepperItem(
            label = "段距",
            value = "${state.paragraphSpacing} dp",
            onDecrement = { actions.onParagraphSpacing(state.paragraphSpacing - 1) },
            onIncrement = { actions.onParagraphSpacing(state.paragraphSpacing + 1) }
        )
        StepperItem(
            label = "字间距",
            value = state.letterSpacing.toString(),
            onDecrement = { actions.onLetterSpacing(state.letterSpacing - 0.1f) },
            onIncrement = { actions.onLetterSpacing(state.letterSpacing + 0.1f) }
        )

        SectionHeader("边距")
        StepperItem(
            label = "左右边距",
            value = "${state.paddingLeft} dp",
            onDecrement = { actions.onPaddingHorizontal(-1) },
            onIncrement = { actions.onPaddingHorizontal(1) }
        )
        StepperItem(
            label = "上边距",
            value = "${state.paddingTop} dp",
            onDecrement = { actions.onPaddingTop(-1) },
            onIncrement = { actions.onPaddingTop(1) }
        )
        StepperItem(
            label = "下边距",
            value = "${state.paddingBottom} dp",
            onDecrement = { actions.onPaddingBottom(-1) },
            onIncrement = { actions.onPaddingBottom(1) }
        )

        SectionHeader("其他")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EInkText(
                text = "恢复全部默认值",
                style = EInkTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            StaticBorderButton(text = "恢复", onClick = actions.onReset)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    EInkText(
        text = title,
        style = EInkTheme.typography.labelLarge,
        color = EInkTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EInkSpacing.m,
                end = EInkSpacing.m,
                top = EInkSpacing.m,
                bottom = EInkSpacing.xs
            )
    )
}

/**
 * 步进器项: 标签 …… [－] 值 [＋]
 */
@Composable
private fun StepperItem(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        StaticBorderButton(text = "－", onClick = onDecrement)
        EInkText(
            text = value,
            style = EInkTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = EInkSpacing.m)
        )
        StaticBorderButton(text = "＋", onClick = onIncrement)
    }
}

/**
 * 静态边框按钮（text + border，零涟漪零阴影，E-Ink 规范 §11）。
 */
@Composable
private fun StaticBorderButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .border(BorderStroke(1.dp, EInkTheme.colorScheme.outline), EInkShapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = EInkSpacing.m,
                vertical = EInkSpacing.xs
            )
        )
    }
}
