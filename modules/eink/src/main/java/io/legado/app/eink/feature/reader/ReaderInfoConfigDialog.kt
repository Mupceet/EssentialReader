package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 信息配置弹层（tabs 标题｜页眉｜页脚）：标题的版面信息（位置/字号/
 * 留白/行距）与页眉页脚几何（页眉模式三态：随状态栏/显示/隐藏；
 * 页脚显隐两态；字号/分割线）。居中透明卡片，
 * 页面上下边缘不被遮挡，实时预览。参数可用性由排版面板入口守卫，
 * 本弹层内不再逐项判。
 */
@Composable
internal fun ReaderInfoConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    footerVisible: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSize: (Int) -> Unit,
    onSetTitleTopSpacing: (Int) -> Unit,
    onSetTitleBottomSpacing: (Int) -> Unit,
    onSetTitleLineSpacing: (Int) -> Unit,
    onSetHeaderMode: (Int) -> Unit,
    onSetHeaderSize: (Int) -> Unit,
    onSetHeaderDivider: (Boolean) -> Unit,
    onSetFooterVisible: (Boolean) -> Unit,
    onSetFooterDivider: (Boolean) -> Unit,
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
        ) {
            when (selectedTab) {
                0 -> {
                    ChoiceRow(
                        label = "标题位置",
                        options = listOf("居左", "居中", "隐藏"),
                        selected = style.titleMode
                            ?: (catalog.find(Ids.TITLE_MODE) as? ReaderStyleParam.Choice)?.default
                            ?: 0,
                        onSelect = onSetTitleMode,
                    )
                    SliderRow(
                        label = "标题字号",
                        value = style.titleSize ?: catalog.defaultInt(Ids.TITLE_SIZE),
                        valueRange = catalog.intRange(Ids.TITLE_SIZE),
                        thumbLabel = { "${it}sp" },
                        tickStep = 6,
                        onSetValue = onSetTitleSize,
                        markerStep = catalog.defaultStep(Ids.TITLE_SIZE),
                    )
                    SliderRow(
                        label = "上留白",
                        value = style.titleTopSpacing ?: catalog.defaultInt(Ids.TITLE_TOP_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_TOP_SPACING),
                        thumbLabel = { "${it}dp" },
                        tickStep = 10,
                        onSetValue = onSetTitleTopSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_TOP_SPACING),
                    )
                    SliderRow(
                        label = "下留白",
                        value = style.titleBottomSpacing ?: catalog.defaultInt(Ids.TITLE_BOTTOM_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_BOTTOM_SPACING),
                        thumbLabel = { "${it}dp" },
                        tickStep = 10,
                        onSetValue = onSetTitleBottomSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_BOTTOM_SPACING),
                    )
                    SliderRow(
                        label = "标题行距",
                        value = style.titleLineSpacing ?: catalog.defaultInt(Ids.TITLE_LINE_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_LINE_SPACING),
                        thumbLabel = { "%.1f倍".format(it / 10f) },
                        tickStep = 2,
                        onSetValue = onSetTitleLineSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_LINE_SPACING),
                    )
                }

                1 -> {
                    ChoiceRow(
                        label = "页眉",
                        options = listOf("随状态栏", "显示", "隐藏"),
                        selected = style.headerMode ?: 0,
                        onSelect = onSetHeaderMode,
                    )
                    SliderRow(
                        label = "页眉字号",
                        value = style.headerSize ?: catalog.defaultInt(Ids.HEADER_SIZE),
                        valueRange = catalog.intRange(Ids.HEADER_SIZE),
                        thumbLabel = { "${it}sp" },
                        tickStep = 6,
                        onSetValue = onSetHeaderSize,
                        markerStep = catalog.defaultStep(Ids.HEADER_SIZE),
                    )
                    ToggleRow(
                        label = "页眉分割线",
                        checked = style.headerDivider ?: false,
                        onToggle = { onSetHeaderDivider(!(style.headerDivider ?: false)) },
                    )
                }

                else -> {
                    ToggleRow(
                        label = "显示页脚",
                        checked = footerVisible,
                        onToggle = { onSetFooterVisible(!footerVisible) },
                    )
                    ToggleRow(
                        label = "页脚分割线",
                        checked = style.footerDivider ?: true,
                        onToggle = { onSetFooterDivider(!(style.footerDivider ?: true)) },
                    )
                }
            }
        }
    }
}

/** 选项行：标签在左，右侧等宽分段按钮（选中反白）。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
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
        EInkText(
            text = label,
            modifier = Modifier.width(SliderLabelWidth),
            style = EInkTheme.typography.bodyMedium,
        )
        options.forEachIndexed { index, option ->
            EInkButton(
                text = option,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                selected = index == selected,
                height = 40.dp,
                role = Role.Tab,
            )
        }
    }
}
