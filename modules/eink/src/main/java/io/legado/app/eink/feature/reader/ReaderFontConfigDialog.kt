package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
 * 自定义显示拖动条（100..900）；底部全宽字体文件夹按钮（恒为
 * 「选择字体文件夹」，重复选择即换文件夹），选择后字体进入上方网格。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
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
            // 标签在左（按需占宽保证完整显示，同字重行；垂直居中对齐
            // 首行网格按钮），网格居右；顶部加呼吸边距与标题区拉开层次
            Row(
                modifier = Modifier.padding(top = EInkSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = "字体选择",
                        style = EInkTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                ) {
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
                }
            }
            WeightSettingRow(
                label = "正文字重",
                value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                markerStep = catalog.defaultStep(Ids.BODY_WEIGHT),
                onSetWeight = onSetBodyWeight,
            )
            WeightSettingRow(
                label = "标题字重",
                value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                markerStep = catalog.defaultStep(Ids.TITLE_WEIGHT),
                onSetWeight = onSetTitleWeight,
            )
            EInkButton(
                text = "选择字体文件夹",
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
 * 字重设置行：标签在左（按需占宽、垂直对齐按钮行），右侧纵列为
 * 「细体/常规/粗体/自定义」四选与（仅自定义时）其下的拖动条——拖动条
 * 只占按钮区域宽度，不延伸到标签下方。文字样式统一（labelLarge）。
 * 进入自定义时按当前档位映射等效值（0→400/1→900/2→300，与宿主
 * resolveWeight 同口径），不写死默认。
 */
@Composable
private fun WeightSettingRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    markerStep: Int?,
    onSetWeight: (Int) -> Unit,
) {
    val isCustom = value !in 0..2
    // 与 EInkButton 默认文案样式同款（labelLarge），标签不比按钮细
    val buttonStyle = EInkTheme.typography.labelLarge
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
                style = buttonStyle,
                // 按需占宽不设上限：标签（正文字重/标题字重）保证完整显示，
                // 空间压力由右侧四枚等宽按钮吸收（极端字号下按钮省略兜底）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
            ) {
                listOf(2 to "细体", 0 to "常规", 1 to "粗体").forEach { (preset, text) ->
                    EInkButton(
                        text = text,
                        onClick = { onSetWeight(preset) },
                        modifier = Modifier.weight(1f),
                        selected = !isCustom && value == preset,
                        height = 40.dp,
                        style = buttonStyle,
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        role = Role.Tab,
                    )
                }
                EInkButton(
                    text = "自定义",
                    onClick = { if (!isCustom) onSetWeight(presetToCustom(value)) },
                    modifier = Modifier.weight(1f),
                    selected = isCustom,
                    height = 40.dp,
                    style = buttonStyle,
                    contentPadding = PaddingValues(horizontal = 2.dp),
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
}

/** 预设档 → 自定义等效值（与宿主 resolveWeight 同口径：1→900/2→300/其余→400）。 */
private fun presetToCustom(value: Int): Int = when (value) {
    1 -> 900
    2 -> 300
    else -> 400
}
