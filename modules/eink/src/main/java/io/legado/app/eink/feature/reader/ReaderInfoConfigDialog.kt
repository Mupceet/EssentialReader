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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 信息配置弹层（tabs 标题｜页眉｜页脚）。标签按需占宽保证完整显示，
 * 内容居右：
 *  - 标题：位置三选；字号「随正文一致/自定义」二选（仅自定义显示
 *    拖动条，进入自定义初值=当前标题字号）；
 *  - 页眉：合并控件——「随状态栏/显示/隐藏」三选 + 页眉字号拖动条；
 *  - 页脚：「显示/隐藏」两按钮按三槽空间申请（首槽占位，与页眉行
 *    显示/隐藏列对齐）。
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
    onSetHeaderSize: (Int) -> Unit,
    onSetFooterVisible: (Boolean) -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    EInkDialog(
        onDismiss = onClose,
        title = "信息配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        PanelTabRow(
            labels = listOf("标题", "页眉", "页脚"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            when (selectedTab) {
                0 -> {
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
                                markerStep = catalog.defaultStep(Ids.TITLE_SIZE),
                            )
                        }
                    }
                }

                1 -> {
                    LabeledSettingRow(label = "页眉") {
                        ChoiceButtons(
                            options = listOf("随状态栏", "显示", "隐藏"),
                            values = listOf(0, 1, 2),
                            selected = style.headerMode ?: 0,
                            onSelect = onSetHeaderMode,
                        )
                        SliderRow(
                            label = null,
                            value = style.headerSize ?: catalog.defaultInt(Ids.HEADER_SIZE),
                            valueRange = catalog.intRange(Ids.HEADER_SIZE),
                            thumbLabel = { "${it}sp" },
                            tickStep = 6,
                            onSetValue = onSetHeaderSize,
                            markerStep = catalog.defaultStep(Ids.HEADER_SIZE),
                        )
                    }
                }

                else -> {
                    LabeledSettingRow(label = "页脚") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                        ) {
                            // 首槽占位：按三槽空间申请，显示/隐藏 与页眉行同名列对齐
                            Spacer(modifier = Modifier.weight(1f))
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
                        }
                    }
                }
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
