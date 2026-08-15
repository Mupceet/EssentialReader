package io.legado.app.eink.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/** 设置面板类型（UI 局部状态，见 Route 中的 remember）。 */
internal enum class ReaderPanel { LAYOUT, OTHER, CACHE }

/** 操作条高度（与全局顶/底栏一致）。 */
private val BarHeight = 56.dp

/** 步进器加减按钮触控目标。 */
private val StepTouchTarget = 44.dp

// ====================================================================
// 顶部操作条：换源 / 刷新 / 缓存 / 添加书架或移出书架
// ====================================================================

@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    onChangeSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onToggleBookshelf: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .background(EInkTheme.colorScheme.surface)
                .padding(horizontal = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        ) {
            EInkText(
                text = state.bookName,
                modifier = Modifier.weight(1f),
                style = EInkTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BarAction(
                label = "换源",
                enabled = !state.isLocalBook,
                onClick = onChangeSource,
            )
            BarAction(label = "刷新", onClick = onRefresh)
            BarAction(
                label = "缓存",
                enabled = !state.isLocalBook,
                onClick = onOpenCachePanel,
            )
            BarAction(
                label = if (state.inBookshelf) "移出书架" else "加书架",
                onClick = onToggleBookshelf,
            )
        }
        // 分隔线在底部：与下方正文分界
        EInkHorizontalDivider()
    }
}

@Composable
private fun BarAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) {
        EInkTheme.colorScheme.onSurface
    } else {
        EInkTheme.colorScheme.disabledContent
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .staticClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(text = label, color = color, style = EInkTheme.typography.labelLarge)
    }
}

// ====================================================================
// 底部操作条：返回 / 目录 / 自动翻页 / 排版 / 其它
// ====================================================================

@Composable
internal fun ReaderBottomBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onOpenPanel: (ReaderPanel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分隔线在顶部：与上方正文分界
        EInkHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .background(EInkTheme.colorScheme.surface)
                .padding(horizontal = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomAction(label = "返回", weight = 1f, onClick = onBack)
            BottomAction(label = "目录", weight = 1f, onClick = onOpenToc)
            BottomAction(
                label = if (state.autoPlay) "停止翻页" else "自动翻页",
                selected = state.autoPlay,
                weight = 1.3f,
                onClick = onToggleAutoPlay,
            )
            BottomAction(label = "排版", weight = 1f, onClick = { onOpenPanel(ReaderPanel.LAYOUT) })
            BottomAction(label = "其它", weight = 1f, onClick = { onOpenPanel(ReaderPanel.OTHER) })
        }
    }
}

@Composable
private fun RowScope.BottomAction(
    label: String,
    weight: Float,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxWidth()
            .height(BarHeight)
            .background(
                color = if (selected) scheme.primary else Color.Transparent,
                shape = EInkShapes.small,
            )
            .staticClickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = label,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            style = EInkTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

// ====================================================================
// 设置面板容器
// ====================================================================

/**
 * 面板覆盖层：底部卡片 + 上方透明点击区。
 *
 * 不加遮罩色：阅读内容保持可见，调整排版参数时可实时预览效果
 * （参数变化触发的重排会保留旧页面直到新页面就绪，不闪白）。
 * 点击面板外任意区域关闭；零动画直接出现/消失。
 */
@Composable
internal fun ReaderPanelContainer(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 面板内容高度封顶为屏幕 45%，保证正文预览区占多数
        val maxContentHeight = maxHeight * 0.45f
        // 透明点击区：关闭面板
        Box(
            modifier = Modifier
                .fillMaxSize()
                .staticClickable(role = Role.Button, onClickLabel = "关闭", onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(EInkTheme.colorScheme.surface)
                // 消费面板内空白处点击，避免透传到关闭层
                .staticClickable(onClick = {}),
        ) {
            EInkHorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = title,
                    style = EInkTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .staticClickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = "×",
                        style = EInkTheme.typography.titleLarge,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EInkHorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
            ) {
                content()
            }
        }
    }
}

// ====================================================================
// 排版参数面板
// ====================================================================

@Composable
internal fun ReaderLayoutPanel(
    style: ReaderTextStyle,
    onAdjustTextSize: (Int) -> Unit,
    onAdjustLetterSpacing: (Float) -> Unit,
    onAdjustLineSpacing: (Int) -> Unit,
    onAdjustParagraphSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onAdjustPaddingH: (Int) -> Unit,
    onAdjustPaddingV: (Int) -> Unit,
    onAdjustHeaderPadding: (Int) -> Unit,
    onAdjustFooterPadding: (Int) -> Unit,
) {
    StepperRow(
        label = "正文字号",
        value = "${style.textSize} sp",
        onDecrement = { onAdjustTextSize(-1) },
        onIncrement = { onAdjustTextSize(1) },
    )
    StepperRow(
        label = "字距",
        value = String.format("%.2f", style.letterSpacing),
        onDecrement = { onAdjustLetterSpacing(-0.05f) },
        onIncrement = { onAdjustLetterSpacing(0.05f) },
    )
    StepperRow(
        label = "行距",
        value = "%.1f倍".format(style.lineSpacing / 10f),
        onDecrement = { onAdjustLineSpacing(-1) },
        onIncrement = { onAdjustLineSpacing(1) },
    )
    StepperRow(
        label = "段距",
        value = "%.1f行".format(style.paragraphSpacing / 10f),
        onDecrement = { onAdjustParagraphSpacing(-1) },
        onIncrement = { onAdjustParagraphSpacing(1) },
    )
    ChipRow(
        label = "缩进",
        options = listOf("无", "1字符", "2字符", "3字符", "4字符"),
        optionValues = listOf(0, 1, 2, 3, 4),
        selectedValue = style.indentChars,
        onSelect = onSetIndent,
    )
    StepperRow(
        label = "边距（左右）",
        value = "${style.paddingH} dp",
        onDecrement = { onAdjustPaddingH(-2) },
        onIncrement = { onAdjustPaddingH(2) },
    )
    StepperRow(
        label = "边距（上下）",
        value = "${style.paddingV} dp",
        onDecrement = { onAdjustPaddingV(-2) },
        onIncrement = { onAdjustPaddingV(2) },
    )
    StepperRow(
        label = "页眉边距",
        value = "${style.headerPadding} dp",
        onDecrement = { onAdjustHeaderPadding(-2) },
        onIncrement = { onAdjustHeaderPadding(2) },
    )
    StepperRow(
        label = "页脚边距",
        value = "${style.footerPadding} dp",
        onDecrement = { onAdjustFooterPadding(-2) },
        onIncrement = { onAdjustFooterPadding(2) },
    )
}

// ====================================================================
// 其它设置面板
// ====================================================================

@Composable
internal fun ReaderOtherPanel(
    state: ReaderUiState,
    onToggleKeepScreenOn: () -> Unit,
    onToggleShowHeader: () -> Unit,
    onToggleTextBold: () -> Unit,
    onAdjustAutoInterval: (Int) -> Unit,
) {
    ToggleRow(label = "保持屏幕常亮", checked = state.keepScreenOn, onToggle = onToggleKeepScreenOn)
    ToggleRow(label = "显示页眉", checked = state.showHeader, onToggle = onToggleShowHeader)
    ToggleRow(label = "正文加粗", checked = state.textBold, onToggle = onToggleTextBold)
    StepperRow(
        label = "自动翻页间隔",
        value = "${state.autoPlayIntervalSec} 秒",
        onDecrement = { onAdjustAutoInterval(-5) },
        onIncrement = { onAdjustAutoInterval(5) },
    )
}

// ====================================================================
// 缓存面板
// ====================================================================

@Composable
internal fun ReaderCachePanel(onCache: (Int) -> Unit) {
    OptionRow(label = "缓存后 50 章") { onCache(50) }
    OptionRow(label = "缓存后 100 章") { onCache(100) }
    OptionRow(label = "缓存后 200 章") { onCache(200) }
    OptionRow(label = "缓存全本") { onCache(CACHE_ALL) }
}

// ====================================================================
// 通用行组件
// ====================================================================

/** 步进行：标签在左，[-] 值 [+] 在右。 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
        )
        StepButton(glyph = "−", onClickLabel = "减小", onClick = onDecrement)
        Box(
            modifier = Modifier.width(76.dp),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = value,
                style = EInkTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        StepButton(glyph = "＋", onClickLabel = "增大", onClick = onIncrement)
    }
}

@Composable
private fun StepButton(glyph: String, onClickLabel: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(StepTouchTarget)
            .staticClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = glyph,
            style = EInkTheme.typography.titleLarge,
            color = EInkTheme.colorScheme.onSurface,
        )
    }
}

/** 开关行：标签在左，状态块在右（反白表示开启）。 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val scheme = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .staticClickable(role = Role.Switch, onClick = onToggle)
            .padding(end = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 36.dp)
                .background(
                    color = if (checked) scheme.primary else Color.Transparent,
                    shape = EInkShapes.small,
                )
                .border(
                    width = 1.dp,
                    color = scheme.outline,
                    shape = EInkShapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = if (checked) "开" else "关",
                color = if (checked) scheme.onPrimary else scheme.onSurfaceVariant,
                style = EInkTheme.typography.labelLarge,
            )
        }
    }
}

/** 选项行（整行点击）。 */
@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .staticClickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(text = label, style = EInkTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        EInkText(
            text = "›",
            style = EInkTheme.typography.titleLarge,
            color = EInkTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单选 chip 行（用于缩进等离散参数）。 */
@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    optionValues: List<Int>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = EInkSpacing.s),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = EInkSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        ) {
            options.forEachIndexed { index, option ->
                val selected = optionValues[index] == selectedValue
                val scheme = EInkTheme.colorScheme
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .background(
                            color = if (selected) scheme.primary else Color.Transparent,
                            shape = EInkShapes.small,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) scheme.primary else scheme.outline,
                            shape = EInkShapes.small,
                        )
                        .staticClickable(role = Role.RadioButton, onClick = { onSelect(optionValues[index]) }),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = option,
                        color = if (selected) scheme.onPrimary else scheme.onSurface,
                        style = EInkTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
