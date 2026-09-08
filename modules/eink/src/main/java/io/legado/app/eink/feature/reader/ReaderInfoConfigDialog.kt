package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 信息配置弹层（单页）：标题位置三选；标题字号「随正文一致/自定义」
 * 二选（仅自定义显示拖动条，初值=当前标题字号）；「页眉显示」「页脚
 * 显示」两行按钮依次 显示/隐藏/随状态栏（页脚仅前两枚，第三槽占位
 * 与页眉行同列对齐）；「页脚字号」统一拖动条（页眉/页脚字号一次写
 * 两侧）。标签按需占宽保证完整显示（Box 居中 intrinsic，同字体弹层）。
 *  上/下留白、标题行距、页眉页脚分割线不暴露（eink 不支持分割线绘制，
 *  留白/行距不开放调节）。
 */
@Composable
internal fun ReaderInfoConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSizeFollowBody: (Boolean) -> Unit,
    onSetTitleSize: (Int) -> Unit,
    onSetHeaderMode: (Int) -> Unit,
    onSetFooterVisible: (Boolean) -> Unit,
    onSetTipSize: (Int) -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    EInkDialog(
        onDismiss = onClose,
        title = "信息配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            LabeledSettingRow(label = "标题位置") {
                ChoiceButtons(
                    options = listOf("居左", "居中", "隐藏"),
                    values = listOf(0, 1, 2),
                    selected = style.titleMode ?: 0,
                    onSelect = onSetTitleMode,
                )
            }
            LabeledSettingRow(label = "标题字号") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                ) {
                    EInkButton(
                        text = "随正文一致",
                        onClick = { onSetTitleSizeFollowBody(true) },
                        modifier = Modifier.weight(1f),
                        selected = titleSizeFollowBody,
                        height = 40.dp,
                        role = Role.Tab,
                    )
                    EInkButton(
                        text = "自定义",
                        onClick = { onSetTitleSizeFollowBody(false) },
                        modifier = Modifier.weight(1f),
                        selected = !titleSizeFollowBody,
                        height = 40.dp,
                        role = Role.Tab,
                    )
                }
                if (!titleSizeFollowBody) {
                    SliderRow(
                        label = null,
                        value = style.titleSize ?: catalog.defaultInt(Ids.TITLE_SIZE),
                        valueRange = catalog.intRange(Ids.TITLE_SIZE),
                        thumbLabel = { "${it}sp" },
                        tickStep = 6,
                        onSetValue = onSetTitleSize,
                    )
                }
            }
            LabeledSettingRow(label = "页眉显示") {
                ChoiceButtons(
                    options = listOf("显示", "隐藏", "随状态栏"),
                    values = listOf(1, 2, 0),
                    selected = style.headerMode ?: 0,
                    onSelect = onSetHeaderMode,
                )
            }
            LabeledSettingRow(label = "页脚显示") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                ) {
                    // 与页眉行同列对齐：前两槽 显示/隐藏，第三槽（随状态栏）占位
                    EInkButton(
                        text = "显示",
                        onClick = { onSetFooterVisible(true) },
                        modifier = Modifier.weight(1f),
                        selected = style.footerVisible ?: true,
                        height = 40.dp,
                        role = Role.Tab,
                    )
                    EInkButton(
                        text = "隐藏",
                        onClick = { onSetFooterVisible(false) },
                        modifier = Modifier.weight(1f),
                        selected = !(style.footerVisible ?: true),
                        height = 40.dp,
                        role = Role.Tab,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            LabeledSettingRow(label = "页脚字号") {
                SliderRow(
                    label = null,
                    value = style.footerSize ?: catalog.defaultInt(Ids.FOOTER_SIZE),
                    valueRange = catalog.intRange(Ids.FOOTER_SIZE),
                    thumbLabel = { "${it}sp" },
                    tickStep = 6,
                    onSetValue = onSetTipSize,
                )
            }
        }
    }
}

/** 标签在左（按需占宽保证完整显示、垂直居中对齐首行控件），内容在右。 */
@Composable
private fun LabeledSettingRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = label,
                style = EInkTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
            content = content,
        )
    }
}

/** 等宽选项按钮行（选中反白，值语义由调用方映射）。 */
@Composable
private fun ChoiceButtons(
    options: List<String>,
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        options.forEachIndexed { index, option ->
            EInkButton(
                text = option,
                onClick = { onSelect(values[index]) },
                modifier = Modifier.weight(1f),
                selected = values[index] == selected,
                height = 40.dp,
                role = Role.Tab,
            )
        }
    }
}
