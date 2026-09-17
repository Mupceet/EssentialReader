package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.legado.app.eink.designsystem.control.EInkSliderRow
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 字体配置弹层（一级，固定面板无滚动）：统一字体原则——正文/标题/页眉
 * （页脚经 applyHeaderStyle 跟随页眉）字体完全一致。系统预设一行三钮；
 * 条件行反显当前选中文件字体（选中反色，点击进二级）+ 入口按钮——
 * 文件夹枚举为空（未选过文件夹/空文件夹）时显示「选择字体文件夹」
 * 直开 SAF，否则「更多字体…（N）」进二级浮层 [ReaderFontPickerOverlay]
 * 分页选择（换文件夹在二级底栏图标，重复选择即换）；正文字重与标题字重
 * 各为「细体/常规/粗体/自定义」四选，仅自定义显示拖动条（100..900）。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
    onSetFont: (ReaderFontSelection) -> Unit,
    onOpenFontPicker: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            // 当前选中的文件字体（须仍在文件夹枚举中：换过文件夹的幽灵选中不显示）
            val selectedFileOption = (style.bodyFont as? ReaderFontSelection.File)
                ?.path
                ?.let { path -> fontOptions.firstOrNull { it.path == path } }
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
                        style = EInkTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                ) {
                    // 系统预设一行三钮（选中反色，同一级旧网格逻辑）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                    ) {
                        EInkButton(
                            text = "系统默认",
                            onClick = { onSetFont(ReaderFontSelection.Sans) },
                            modifier = Modifier.weight(1f),
                            selected = style.bodyFont == ReaderFontSelection.Sans,
                            height = 44.dp,
                            style = EInkTheme.typography.bodyMedium,
                            role = Role.Button,
                        )
                        EInkButton(
                            text = "系统衬线",
                            onClick = { onSetFont(ReaderFontSelection.Serif) },
                            modifier = Modifier.weight(1f),
                            selected = style.bodyFont == ReaderFontSelection.Serif,
                            height = 44.dp,
                            style = EInkTheme.typography.bodyMedium,
                            role = Role.Button,
                        )
                        EInkButton(
                            text = "系统等宽",
                            onClick = { onSetFont(ReaderFontSelection.Mono) },
                            modifier = Modifier.weight(1f),
                            selected = style.bodyFont == ReaderFontSelection.Mono,
                            height = 44.dp,
                            style = EInkTheme.typography.bodyMedium,
                            role = Role.Button,
                        )
                    }
                    // 条件行：选中文件字体反显（选中反色，点击进二级）+
                    // 「更多字体…（N）」入口；无文件字体选中时入口独占整行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                    ) {
                        if (selectedFileOption != null) {
                            EInkButton(
                                // 显示名去除扩展名（.ttf/.otf），选中身份按 path 比对；
                                // 长名占满半宽，留横向内边距防贴边（默认 0dp）
                                text = selectedFileOption.name.substringBeforeLast("."),
                                onClick = onOpenFontPicker,
                                modifier = Modifier.weight(1f),
                                selected = true,
                                height = 44.dp,
                                style = EInkTheme.typography.bodyMedium,
                                role = Role.Button,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            )
                        }
                        EInkButton(
                            // 空枚举（未选过文件夹/空文件夹）= 首选动作是选文件夹：
                            // 直开 SAF；选过则显示数量进二级
                            text = if (fontOptions.isEmpty()) {
                                "选择字体文件夹"
                            } else {
                                "更多字体…（${fontOptions.size}）"
                            },
                            onClick = if (fontOptions.isEmpty()) onPickFolder else onOpenFontPicker,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                            style = EInkTheme.typography.bodyMedium,
                            role = Role.Button,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        )
                    }
                }
            }
            WeightSettingRow(
                label = "正文字重",
                value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                onSetWeight = onSetBodyWeight,
            )
            WeightSettingRow(
                label = "标题字重",
                value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                onSetWeight = onSetTitleWeight,
            )
        }
    }
}

/**
 * 字重设置行：标签在左（按需占宽、垂直对齐按钮行），右侧纵列为
 * 「细体/常规/粗体/自定义」四选与（仅自定义时）其下的拖动条——拖动条
 * 只占按钮区域宽度，不延伸到标签下方。文字样式统一（bodyMedium，
 * 与排版面板滑条标签同风格）。
 * 进入自定义时按当前档位映射等效值（0→400/1→900/2→300，与宿主
 * resolveWeight 同口径），不写死默认。
 */
@Composable
private fun WeightSettingRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    onSetWeight: (Int) -> Unit,
) {
    val isCustom = value !in 0..2
    // 标签与按钮统一 bodyMedium（与滑条标签同风格，轻一级）
    val buttonStyle = EInkTheme.typography.bodyMedium
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
                EInkSliderRow(
                    label = null,
                    value = value.coerceIn(valueRange.first, valueRange.last),
                    valueRange = valueRange,
                    thumbLabel = { it.toString() },
                    tickStep = 100,
                    onSetValue = onSetWeight,
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
