package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkSliderRow
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.roundToInt

/**
 * 排版设置共享行组件（归一弹框 Task 2 抽取）：
 * 功能轴弹框与区域轴分组视图复用同一份行实现与回调——同一参数在两轴
 * 同值同控件（spec §4.3「行组件两处复用，回调一致」）。
 * 行集 = spec §4.3 现状行清单，不新增参数行。
 */

/** 正文基础五行档位滑条（字号/字距/缩进/行距/段距），值域来自协商目录。 */
@Composable
internal fun BodyBasicRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTextSize: (Int) -> Unit,
    onSetLetterSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onSetLineSpacing: (Int) -> Unit,
    onSetParagraphSpacing: (Int) -> Unit,
) {
    EInkSliderRow(
        label = "字号",
        value = style.textSize,
        valueRange = catalog.intRange(Ids.BODY_SIZE),
        thumbLabel = { "$it" },
        onSetValue = onSetTextSize,
    )
    val lsRange = catalog.floatStepIndexRange(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP)
    EInkSliderRow(
        label = "字距",
        value = (style.letterSpacing / LETTER_SPACING_STEP).roundToInt()
            .coerceIn(lsRange.first, lsRange.last),
        valueRange = lsRange,
        thumbLabel = { "%.2f".format(it * LETTER_SPACING_STEP) },
        onSetValue = onSetLetterSpacing,
    )
    EInkSliderRow(
        label = "缩进",
        value = style.indentChars,
        valueRange = catalog.intRange(Ids.BODY_INDENT),
        thumbLabel = { "${it}字" },
        onSetValue = onSetIndent,
    )
    EInkSliderRow(
        label = "行距",
        value = style.lineSpacing,
        valueRange = catalog.intRange(Ids.BODY_LINE_SPACING),
        thumbLabel = { "%.1f倍".format(it / 10f) },
        onSetValue = onSetLineSpacing,
    )
    EInkSliderRow(
        label = "段距",
        value = style.paragraphSpacing,
        valueRange = catalog.intRange(Ids.BODY_PARAGRAPH_SPACING),
        thumbLabel = { "%.1f行".format(it / 10f) },
        onSetValue = onSetParagraphSpacing,
    )
}

/** 标题两行：标题位置三选 + 标题字号（Locked 置灰 / 随正文一致·自定义 + 滑条）。 */
@Composable
internal fun TitleInfoRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSizeFollowBody: (Boolean) -> Unit,
    onSetTitleSize: (Int) -> Unit,
) {
    if (catalog.available(Ids.TITLE_MODE)) {
        LabeledSettingRow(label = "标题位置") {
            ChoiceButtons(
                options = listOf("居左", "居中", "隐藏"),
                values = listOf(0, 1, 2),
                selected = style.titleMode ?: 0,
                onSelect = onSetTitleMode,
            )
        }
    }
    when (val titleSizeParam = catalog.find(Ids.TITLE_SIZE)) {
        // Locked：值可见不可调，置灰呈现（无「随正文/自定义」二选）
        is ReaderStyleParam.Locked -> LabeledSettingRow(label = "标题字号") {
            LockedTipValueRow(
                text = "${style.titleSize ?: titleSizeParam.value.roundToInt()}sp · 固定",
            )
        }
        is ReaderStyleParam.Stepped -> if (titleSizeParam.available) {
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
                        style = EInkTheme.typography.bodyMedium,
                        role = Role.Tab,
                    )
                    EInkButton(
                        text = "自定义",
                        onClick = { onSetTitleSizeFollowBody(false) },
                        modifier = Modifier.weight(1f),
                        selected = !titleSizeFollowBody,
                        style = EInkTheme.typography.bodyMedium,
                        role = Role.Tab,
                    )
                }
                if (!titleSizeFollowBody) {
                    EInkSliderRow(
                        label = null,
                        value = style.titleSize ?: catalog.defaultInt(Ids.TITLE_SIZE),
                        valueRange = catalog.intRange(Ids.TITLE_SIZE),
                        thumbLabel = { "$it" },
                        onSetValue = onSetTitleSize,
                    )
                }
            }
        }
        // 未声明/不可用/非连续档：整行隐藏
        else -> Unit
    }
}

/** 页眉显示三选行（显示/隐藏/随状态栏）。 */
@Composable
internal fun HeaderModeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetHeaderMode: (Int) -> Unit,
) {
    if (catalog.available(Ids.HEADER_MODE)) {
        LabeledSettingRow(label = "页眉显示") {
            ChoiceButtons(
                options = listOf("显示", "隐藏", "随状态栏"),
                values = listOf(1, 2, 0),
                selected = style.headerMode ?: 0,
                onSelect = onSetHeaderMode,
            )
        }
    }
}

/** 页脚显示行（显示/隐藏，第三槽占位与页眉行同列对齐）。 */
@Composable
internal fun FooterModeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetFooterVisible: (Boolean) -> Unit,
) {
    if (catalog.available(Ids.FOOTER_VISIBILITY)) {
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
                    style = EInkTheme.typography.bodyMedium,
                    role = Role.Tab,
                )
                EInkButton(
                    text = "隐藏",
                    onClick = { onSetFooterVisible(false) },
                    modifier = Modifier.weight(1f),
                    selected = !(style.footerVisible ?: true),
                    style = EInkTheme.typography.bodyMedium,
                    role = Role.Tab,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 页眉页脚字号统一滑条（Locked 置灰 / 滑条 / 未声明不可用整行隐藏）。 */
@Composable
internal fun TipSizeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTipSize: (Int) -> Unit,
) {
    when (val footerSizeParam = catalog.find(Ids.FOOTER_SIZE)) {
        is ReaderStyleParam.Locked -> LabeledSettingRow(label = "页眉页脚字号") {
            LockedTipValueRow(
                text = "${style.footerSize ?: footerSizeParam.value.roundToInt()}sp · 固定",
            )
        }
        is ReaderStyleParam.Stepped -> if (footerSizeParam.available) {
            LabeledSettingRow(label = "页眉页脚字号") {
                EInkSliderRow(
                    label = null,
                    value = style.footerSize ?: catalog.defaultInt(Ids.FOOTER_SIZE),
                    valueRange = catalog.intRange(Ids.FOOTER_SIZE),
                    thumbLabel = { "$it" },
                    onSetValue = onSetTipSize,
                )
            }
        }
        // 未声明/不可用/非连续档：整行隐藏（不留 0..0 死滑条）
        else -> Unit
    }
}

/** 正文四边距 4 行档位滑条。 */
@Composable
internal fun BodyMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.paddingTop,
    bottomDp = style.paddingBottom,
    leftDp = style.paddingLeft,
    rightDp = style.paddingRight,
    maxVertical = catalog.intRange(Ids.BODY_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.BODY_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)

/** 页眉四边距 4 行档位滑条。 */
@Composable
internal fun HeaderMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.headerPaddingTop,
    bottomDp = style.headerPaddingBottom,
    leftDp = style.headerPaddingLeft,
    rightDp = style.headerPaddingRight,
    maxVertical = catalog.intRange(Ids.HEADER_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.HEADER_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)

/** 页脚四边距 4 行档位滑条。 */
@Composable
internal fun FooterMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.footerPaddingTop,
    bottomDp = style.footerPaddingBottom,
    leftDp = style.footerPaddingLeft,
    rightDp = style.footerPaddingRight,
    maxVertical = catalog.intRange(Ids.FOOTER_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.FOOTER_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)

/** 单个区域的边距 4 行档位滑条：上边距、下边距、左边距、右边距。 */
@Composable
internal fun MarginRows(
    topDp: Int,
    bottomDp: Int,
    leftDp: Int,
    rightDp: Int,
    maxVertical: Int,
    maxHorizontal: Int,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) {
    EInkSliderRow(
        label = "上边距",
        value = topDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "$it" },
        onSetValue = onSetTop,
    )
    EInkSliderRow(
        label = "下边距",
        value = bottomDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "$it" },
        onSetValue = onSetBottom,
    )
    EInkSliderRow(
        label = "左边距",
        value = leftDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "$it" },
        onSetValue = onSetLeft,
    )
    EInkSliderRow(
        label = "右边距",
        value = rightDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "$it" },
        onSetValue = onSetRight,
    )
}

/** 标签在左（按需占宽保证完整显示、垂直居中对齐首行控件），内容在右。 */
@Composable
internal fun LabeledSettingRow(label: String, content: @Composable ColumnScope.() -> Unit) {
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
                style = EInkTheme.typography.bodyMedium,
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
internal fun ChoiceButtons(
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
                style = EInkTheme.typography.bodyMedium,
                role = Role.Tab,
            )
        }
    }
}

/**
 * 锁定值行（数值参数目录声明 [ReaderStyleParam.Locked] 时）：值可见、
 * 不可调，右侧标注「固定」说明不可变更——同字重锁定行的置灰呈现，
 * 不留假滑条。
 */
@Composable
internal fun LockedTipValueRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            EInkText(
                text = text,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 区域分组小节标题：流内常驻（非吸顶），与锚点行呼应。 */
@Composable
internal fun GroupHeading(text: String) {
    EInkText(
        text = text,
        style = EInkTheme.typography.titleSmall,
        modifier = Modifier
            .semantics { heading() }
            .padding(top = EInkSpacing.s, bottom = EInkSpacing.xs),
    )
}
