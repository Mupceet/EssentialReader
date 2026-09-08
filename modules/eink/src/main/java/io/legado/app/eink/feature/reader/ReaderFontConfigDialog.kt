package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 字体配置弹层：统一字体原则——正文/标题/页眉（页脚经 applyHeaderStyle
 * 跟随页眉）字体完全一致。三列字体网格（系统默认/衬线/等宽 + 字体
 * 文件）；正文字重与标题字重各为「细体/常规/粗体/自定义」四选，仅
 * 自定义显示拖动条（100..900）；底部全宽字体文件夹按钮（未选=选择、
 * 已选=更新），选择后字体进入上方网格。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
    hasFontFolder: Boolean,
    onSetFont: (ReaderFontSelection) -> Unit,
    onSetBodyWeight: (Int) -> Unit,
    onSetTitleWeight: (Int) -> Unit,
    onPickFolder: () -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    EInkDialog(
        onDismiss = onClose,
        title = "字体配置",
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
            val entries = buildList {
                add(FontEntry("系统默认", style.bodyFont == ReaderFontSelection.Sans) {
                    onSetFont(ReaderFontSelection.Sans)
                })
                add(FontEntry("系统衬线", style.bodyFont == ReaderFontSelection.Serif) {
                    onSetFont(ReaderFontSelection.Serif)
                })
                add(FontEntry("系统等宽", style.bodyFont == ReaderFontSelection.Mono) {
                    onSetFont(ReaderFontSelection.Mono)
                })
                fontOptions.forEach { option ->
                    add(
                        FontEntry(
                            label = option.name,
                            selected = style.bodyFont == ReaderFontSelection.File(option.path),
                        ) { onSetFont(ReaderFontSelection.File(option.path)) }
                    )
                }
            }
            entries.chunked(3).forEach { rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                ) {
                    rowEntries.forEach { entry ->
                        EInkButton(
                            text = entry.label,
                            onClick = entry.onClick,
                            modifier = Modifier.weight(1f),
                            selected = entry.selected,
                            height = 44.dp,
                            role = Role.Button,
                        )
                    }
                    repeat(3 - rowEntries.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            WeightSettingRow(
                label = "正文字重",
                value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                customDefault = catalog.defaultInt(Ids.BODY_WEIGHT),
                valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                markerStep = catalog.defaultStep(Ids.BODY_WEIGHT),
                onSetWeight = onSetBodyWeight,
            )
            WeightSettingRow(
                label = "标题字重",
                value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                customDefault = catalog.defaultInt(Ids.TITLE_WEIGHT),
                valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                markerStep = catalog.defaultStep(Ids.TITLE_WEIGHT),
                onSetWeight = onSetTitleWeight,
            )
            EInkButton(
                text = if (hasFontFolder) "更新字体文件夹" else "选择字体文件夹",
                onClick = onPickFolder,
                modifier = Modifier.fillMaxWidth(),
                height = 44.dp,
                role = Role.Button,
            )
        }
    }
}

/** 字体网格项：展示名 + 选中态 + 点击回调。 */
private data class FontEntry(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 字重设置行：右侧「细体/常规/粗体/自定义」四选（0/2/1 预设档直写，
 * 宿主下拉同构）；仅自定义选中时在其下显示拖动条（进入自定义时写
 * 目录默认值）。
 */
@Composable
private fun WeightSettingRow(
    label: String,
    value: Int,
    customDefault: Int,
    valueRange: IntRange,
    markerStep: Int?,
    onSetWeight: (Int) -> Unit,
) {
    val isCustom = value !in 0..2
    Column {
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
            listOf(2 to "细体", 0 to "常规", 1 to "粗体").forEach { (preset, text) ->
                EInkButton(
                    text = text,
                    onClick = { onSetWeight(preset) },
                    modifier = Modifier.weight(1f),
                    selected = !isCustom && value == preset,
                    height = 40.dp,
                    role = Role.Tab,
                )
            }
            EInkButton(
                text = "自定义",
                onClick = { if (!isCustom) onSetWeight(customDefault) },
                modifier = Modifier.weight(1f),
                selected = isCustom,
                height = 40.dp,
                role = Role.Tab,
            )
        }
        if (isCustom) {
            SliderRow(
                label = null,
                value = value.coerceIn(valueRange.first, valueRange.last),
                valueRange = valueRange,
                thumbLabel = { it.toString() },
                tickStep = 100,
                onSetValue = onSetWeight,
                markerStep = markerStep,
            )
        }
    }
}
