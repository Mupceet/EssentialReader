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
 * 条件行反显最近选中的文件字体（历史 ∩ 当前枚举，幽灵剔除——切回系统
 * 预设后一键可回；无历史不默认展示，入口独占整行）；入口：枚举空（未选
 * 过文件夹/空文件夹）为「选择字体文件夹」直开 SAF，非空为「更多字体…（N）」
 * 进二级浮层 [ReaderFontPickerOverlay]。正文字重与标题字重：预设行
 * 「细体/常规/粗体」三选 + 独立自定义行（点击原地替换为拖动条，点预设
 * 复原，窄屏四枚一行显示不完整），拖动条值域 100..900。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
    recentFontPaths: List<String>,
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
            // 反显数据源：最近选中的文件字体 ∩ 当前枚举（幽灵 path 剔除）；
            // 无历史不默认展示枚举字体
            val recentOption = recentFontPaths.firstNotNullOfOrNull { path ->
                fontOptions.firstOrNull { it.path == path }
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
                    // 条件行：最近选中的文件字体反显（若有）与入口同排等分；
                    // 无反显时入口独占整行。入口：枚举空 = 首选动作选文件夹
                    // 直开 SAF；非空「更多字体…（N）」进二级
                    val entryLabel = if (fontOptions.isEmpty()) {
                        "选择字体文件夹"
                    } else {
                        "更多字体…（${fontOptions.size}）"
                    }
                    val entryAction = if (fontOptions.isEmpty()) onPickFolder else onOpenFontPicker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                    ) {
                        if (recentOption != null) {
                            EInkButton(
                                // 显示名去除扩展名（.ttf/.otf）；长名单行省略，
                                // 触控目标不缩
                                text = recentOption.name.substringBeforeLast("."),
                                onClick = {
                                    onSetFont(ReaderFontSelection.File(recentOption.path))
                                },
                                modifier = Modifier.weight(1f),
                                selected = (style.bodyFont as? ReaderFontSelection.File)
                                    ?.path == recentOption.path,
                                height = 44.dp,
                                style = EInkTheme.typography.bodyMedium,
                                role = Role.Button,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            )
                        }
                        EInkButton(
                            text = entryLabel,
                            onClick = entryAction,
                            modifier = if (recentOption == null) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.weight(1f)
                            },
                            height = 44.dp,
                            style = EInkTheme.typography.bodyMedium,
                            role = Role.Button,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        )
                    }
                }
            }
            // 目录守卫（0.6.0）：参数未声明（宿主不支持）时整行隐藏；
            // Locked（仅默认值可用）时置灰呈现锁定值——不再出现值域塌缩
            // 为 0..0 的死滑条或无效按钮
            when (val bodyWeightParam = catalog.find(Ids.BODY_WEIGHT)) {
                is io.legado.app.eink.contract.ReaderStyleParam.Locked ->
                    LockedWeightRow(label = "正文字重", value = style.bodyWeight ?: 0)
                null -> Unit
                // 预设档宿主：三预设按钮，无自定义入口
                is io.legado.app.eink.contract.ReaderStyleParam.Presets -> WeightSettingRow(
                    label = "正文字重",
                    value = style.bodyWeight ?: bodyWeightParam.default,
                    valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                    onSetWeight = onSetBodyWeight,
                    allowCustom = false,
                )
                else -> WeightSettingRow(
                    label = "正文字重",
                    value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                    valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                    onSetWeight = onSetBodyWeight,
                )
            }
            when (val titleWeightParam = catalog.find(Ids.TITLE_WEIGHT)) {
                is io.legado.app.eink.contract.ReaderStyleParam.Locked ->
                    LockedWeightRow(label = "标题字重", value = style.titleWeight ?: 0)
                null -> Unit
                // 预设档宿主：三预设按钮，无自定义入口（同正文字重）
                is io.legado.app.eink.contract.ReaderStyleParam.Presets -> WeightSettingRow(
                    label = "标题字重",
                    value = style.titleWeight ?: titleWeightParam.default,
                    valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                    onSetWeight = onSetTitleWeight,
                    allowCustom = false,
                )
                else -> WeightSettingRow(
                    label = "标题字重",
                    value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                    valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                    onSetWeight = onSetTitleWeight,
                )
            }
        }
    }
}

/**
 * 字重设置行：标签在左（按需占宽、垂直对齐按钮行），右侧纵列两行——
 * 预设行「细体/常规/粗体」三选 + 第二行「自定义/拖动条」原地二态：
 * 点自定义整行原地替换为拖动条（100..900），点任一预设复原为自定义
 * 按钮（窄屏四枚一行显示不完整，故自定义独立成行）。文字样式统一
 * （bodyMedium，与排版面板滑条标签同风格）。
 * 进入自定义时按当前档位映射等效值（0→400/1→900/2→300，与宿主
 * resolveWeight 同口径），不写死默认。
 */
@Composable
private fun WeightSettingRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    onSetWeight: (Int) -> Unit,
    // false = 预设档宿主（ReaderStyleParam.Presets）：隐藏「自定义」行
    // 与拖动条，仅三预设可选
    allowCustom: Boolean = true,
) {
    val isCustom = value !in 0..2
    // 标签与按钮统一 bodyMedium（与排版面板滑条标签同风格，轻一级）
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
                // 空间压力由右侧等宽按钮吸收（极端字号下按钮省略兜底）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            // 预设行：三枚等分（自定义移出本行，保窄屏完整显示）
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
            }
            if (allowCustom) {
                // 第二行原地二态：自定义态显示拖动条；点预设按钮态复原。
                // 容器定高 48dp（= EInkSliderRow 行高）：按钮态 40dp 垂直
                // 居中，二态切换不产生纵向跳动
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCustom) {
                        EInkSliderRow(
                            label = null,
                            value = value.coerceIn(valueRange.first, valueRange.last),
                            valueRange = valueRange,
                            thumbLabel = { it.toString() },
                            onSetValue = onSetWeight,
                        )
                    } else {
                        EInkButton(
                            text = "自定义",
                            // 按钮仅在非自定义态在场，点击即进自定义（映射等效值）
                            onClick = { onSetWeight(presetToCustom(value)) },
                            modifier = Modifier.fillMaxWidth(),
                            height = 40.dp,
                            style = buttonStyle,
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            role = Role.Tab,
                        )
                    }
                }
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

/**
 * 锁定字重行：宿主声明 [io.legado.app.eink.contract.ReaderStyleParam.Locked]
 * 时的置灰呈现——值可见（次级灰，与未选 Tab 文字同色系）、不可调，
 * 右侧标注「固定」说明不可变更，不留假交互。
 */
@Composable
private fun LockedWeightRow(
    label: String,
    value: Int,
) {
    val presetText = when (value) {
        0 -> "常规"
        1 -> "粗体"
        2 -> "细体"
        else -> value.toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        Box(
            modifier = Modifier.height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = label,
                style = EInkTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            EInkText(
                text = "$presetText · 固定",
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
