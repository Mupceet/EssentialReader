package io.legado.app.eink.reader

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.modifier.rememberImmediatePressState
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
                iconRes = R.drawable.ic_exchange,
                contentDescription = "换源",
                enabled = !state.isLocalBook,
                onClick = onChangeSource,
            )
            BarAction(
                iconRes = R.drawable.ic_refresh_black_24dp,
                contentDescription = "刷新",
                onClick = onRefresh,
            )
            BarAction(
                iconRes = R.drawable.ic_download_line,
                contentDescription = "缓存",
                enabled = !state.isLocalBook,
                onClick = onOpenCachePanel,
            )
            BarAction(
                iconRes = if (state.inBookshelf) R.drawable.ic_outline_delete else R.drawable.ic_add,
                contentDescription = if (state.inBookshelf) "移出书架" else "加书架",
                onClick = onToggleBookshelf,
            )
        }
        // 分隔线在底部：与下方正文分界
        EInkHorizontalDivider()
    }
}

@Composable
private fun BarAction(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    // 按压反色：按下黑底白字，抬起恢复（即时手势跟踪，不受滚动容器影响）
    val press = rememberImmediatePressState()
    val content = if (enabled) {
        if (press.isPressed) scheme.surface else scheme.onSurface
    } else {
        scheme.disabledContent
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .then(press.modifier)
            .background(if (enabled && press.isPressed) scheme.onSurface else Color.Transparent)
            .staticClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(horizontal = EInkSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(content),
        )
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
    // 按压/选中反色：选中常态反白，按下瞬时反色，抬起恢复
    val press = rememberImmediatePressState()
    val container = when {
        press.isPressed -> scheme.onSurface
        selected -> scheme.primary
        else -> Color.Transparent
    }
    val content = when {
        press.isPressed -> scheme.surface
        selected -> scheme.onPrimary
        else -> scheme.onSurface
    }
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxWidth()
            .height(BarHeight)
            .background(color = container, shape = EInkShapes.small)
            .then(press.modifier)
            .staticClickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = label,
            color = content,
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
                CloseButton(onClose = onClose)
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
// 排版参数面板（6 行：字号/字距/缩进/行距/段距/边距调整入口）
// ====================================================================

/**
 * 排版面板。
 *
 * 6 行整行步进：字号、字距、缩进、行距、段距，以及"边距调整"入口。
 * 边距调整在独立的居中弹框（[ReaderMarginDialog]）中进行：
 * 底部面板会遮挡页眉/页脚，居中弹框四周透明，调整时实时可见效果。
 */
@Composable
internal fun ReaderLayoutPanel(
    style: ReaderTextStyle,
    onAdjustTextSize: (Int) -> Unit,
    onAdjustLetterSpacing: (Float) -> Unit,
    onAdjustIndent: (Int) -> Unit,
    onAdjustLineSpacing: (Int) -> Unit,
    onAdjustParagraphSpacing: (Int) -> Unit,
    onOpenMargins: () -> Unit,
) {
    StepperRow(
        label = "字号",
        value = "${style.textSize}sp",
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
        label = "缩进",
        value = "${style.indentChars}字",
        onDecrement = { onAdjustIndent(-1) },
        onIncrement = { onAdjustIndent(1) },
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
    OptionRow(label = "边距调整") { onOpenMargins() }
}

// ====================================================================
// 边距调整弹框（屏幕居中，内含 正文/页眉/页脚 三 Tab）
// ====================================================================

/**
 * 边距调整弹框：屏幕居中的卡片，四周透明 —— 页眉/页脚/正文边距
 * 调整时实时可见效果（±2dp）。
 *
 * 内含三个 Tab：正文 / 页眉 / 页脚，每个 Tab 各 4 行整行步进：
 * 上边距、下边距、左边距、右边距。
 */
@Composable
internal fun ReaderMarginDialog(
    style: ReaderTextStyle,
    onAdjustPaddingTop: (Int) -> Unit,
    onAdjustPaddingBottom: (Int) -> Unit,
    onAdjustPaddingLeft: (Int) -> Unit,
    onAdjustPaddingRight: (Int) -> Unit,
    onAdjustHeaderPaddingTop: (Int) -> Unit,
    onAdjustHeaderPaddingBottom: (Int) -> Unit,
    onAdjustHeaderPaddingLeft: (Int) -> Unit,
    onAdjustHeaderPaddingRight: (Int) -> Unit,
    onAdjustFooterPaddingTop: (Int) -> Unit,
    onAdjustFooterPaddingBottom: (Int) -> Unit,
    onAdjustFooterPaddingLeft: (Int) -> Unit,
    onAdjustFooterPaddingRight: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 透明点击区：关闭弹框
        Box(
            modifier = Modifier
                .fillMaxSize()
                .staticClickable(role = Role.Button, onClickLabel = "关闭", onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.86f)
                .background(EInkTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = EInkTheme.colorScheme.outline,
                )
                // 消费弹框内空白处点击，避免透传到关闭层
                .staticClickable(onClick = {}),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = "边距调整",
                    style = EInkTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onClose = onClose)
            }
            EInkHorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.l, vertical = EInkSpacing.s),
            ) {
                PanelTabRow(
                    labels = listOf("正文", "页眉", "页脚"),
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )
                when (selectedTab) {
                    0 -> MarginRows(
                        topDp = style.paddingTop,
                        bottomDp = style.paddingBottom,
                        leftDp = style.paddingLeft,
                        rightDp = style.paddingRight,
                        onAdjustTop = onAdjustPaddingTop,
                        onAdjustBottom = onAdjustPaddingBottom,
                        onAdjustLeft = onAdjustPaddingLeft,
                        onAdjustRight = onAdjustPaddingRight,
                    )

                    1 -> MarginRows(
                        topDp = style.headerPaddingTop,
                        bottomDp = style.headerPaddingBottom,
                        leftDp = style.headerPaddingLeft,
                        rightDp = style.headerPaddingRight,
                        onAdjustTop = onAdjustHeaderPaddingTop,
                        onAdjustBottom = onAdjustHeaderPaddingBottom,
                        onAdjustLeft = onAdjustHeaderPaddingLeft,
                        onAdjustRight = onAdjustHeaderPaddingRight,
                    )

                    else -> MarginRows(
                        topDp = style.footerPaddingTop,
                        bottomDp = style.footerPaddingBottom,
                        leftDp = style.footerPaddingLeft,
                        rightDp = style.footerPaddingRight,
                        onAdjustTop = onAdjustFooterPaddingTop,
                        onAdjustBottom = onAdjustFooterPaddingBottom,
                        onAdjustLeft = onAdjustFooterPaddingLeft,
                        onAdjustRight = onAdjustFooterPaddingRight,
                    )
                }
            }
        }
    }
}

/** 单个区域的边距 4 行：上边距、下边距、左边距、右边距。 */
@Composable
private fun MarginRows(
    topDp: Int,
    bottomDp: Int,
    leftDp: Int,
    rightDp: Int,
    onAdjustTop: (Int) -> Unit,
    onAdjustBottom: (Int) -> Unit,
    onAdjustLeft: (Int) -> Unit,
    onAdjustRight: (Int) -> Unit,
) {
    StepperRow(
        label = "上边距",
        value = "${topDp}dp",
        onDecrement = { onAdjustTop(-2) },
        onIncrement = { onAdjustTop(2) },
    )
    StepperRow(
        label = "下边距",
        value = "${bottomDp}dp",
        onDecrement = { onAdjustBottom(-2) },
        onIncrement = { onAdjustBottom(2) },
    )
    StepperRow(
        label = "左边距",
        value = "${leftDp}dp",
        onDecrement = { onAdjustLeft(-2) },
        onIncrement = { onAdjustLeft(2) },
    )
    StepperRow(
        label = "右边距",
        value = "${rightDp}dp",
        onDecrement = { onAdjustRight(-2) },
        onIncrement = { onAdjustRight(2) },
    )
}

// ====================================================================
// 边距调整弹框（屏幕居中）
// ====================================================================

/** 关闭按钮（×）：按压反色。 */
@Composable
private fun CloseButton(onClose: () -> Unit) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(press.modifier)
            .background(if (press.isPressed) scheme.onSurface else Color.Transparent)
            .staticClickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = "×",
            style = EInkTheme.typography.titleLarge,
            color = if (press.isPressed) scheme.surface else scheme.onSurfaceVariant,
        )
    }
}

/** 面板 Tab 行：选中项反白，按压瞬时反色，零动画直接切换。 */
@Composable
private fun PanelTabRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val press = rememberImmediatePressState()
            val container = when {
                press.isPressed -> scheme.onSurface
                isSelected -> scheme.primary
                else -> Color.Transparent
            }
            val content = when {
                press.isPressed -> scheme.surface
                isSelected -> scheme.onPrimary
                else -> scheme.onSurface
            }
            val borderColor = if (press.isPressed || isSelected) container else scheme.outline
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .then(press.modifier)
                    .background(color = container, shape = EInkShapes.small)
                    .border(width = 1.dp, color = borderColor, shape = EInkShapes.small)
                    .staticClickable(role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(
                    text = label,
                    color = content,
                    style = EInkTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

// ====================================================================
// 其它设置面板
// ====================================================================

@Composable
internal fun ReaderOtherPanel(
    state: ReaderUiState,
    onToggleKeepScreenOn: () -> Unit,
    onToggleTextBold: () -> Unit,
    onAdjustAutoInterval: (Int) -> Unit,
) {
    ToggleRow(label = "保持屏幕常亮", checked = state.keepScreenOn, onToggle = onToggleKeepScreenOn)
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
    val scheme = EInkTheme.colorScheme
    // 按压反色：按下黑底白字形，抬起恢复（即时手势跟踪，滚动容器内不延迟不滞留）
    val press = rememberImmediatePressState()
    Box(
        modifier = Modifier
            .size(StepTouchTarget)
            .then(press.modifier)
            .background(if (press.isPressed) scheme.onSurface else Color.Transparent)
            .staticClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = glyph,
            style = EInkTheme.typography.titleLarge,
            color = if (press.isPressed) scheme.surface else scheme.onSurface,
        )
    }
}

/** 开关行：标签在左，状态块在右（开启反白）；按压时整行反色。 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val scheme = EInkTheme.colorScheme
    // 按压反色：整行黑底、文字白字，状态块同步反转
    val press = rememberImmediatePressState()
    val isPressed = press.isPressed
    val rowContainer = if (isPressed) scheme.onSurface else Color.Transparent
    val labelColor = if (isPressed) scheme.surface else scheme.onSurface
    val blockContainer = when {
        isPressed -> scheme.surface
        checked -> scheme.primary
        else -> Color.Transparent
    }
    val blockContent = when {
        isPressed -> scheme.onSurface
        checked -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val blockBorder = if (isPressed) scheme.surface else scheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(press.modifier)
            .background(rowContainer)
            .staticClickable(role = Role.Switch, onClick = onToggle)
            .padding(end = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
            color = labelColor,
        )
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 36.dp)
                .background(color = blockContainer, shape = EInkShapes.small)
                .border(width = 1.dp, color = blockBorder, shape = EInkShapes.small),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(
                text = if (checked) "开" else "关",
                color = blockContent,
                style = EInkTheme.typography.labelLarge,
            )
        }
    }
}

/** 选项行（整行点击，按压反色）。 */
@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(press.modifier)
            .background(if (press.isPressed) scheme.onSurface else Color.Transparent)
            .staticClickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            color = if (press.isPressed) scheme.surface else scheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        EInkText(
            text = "›",
            style = EInkTheme.typography.titleLarge,
            color = if (press.isPressed) scheme.surface else scheme.onSurfaceVariant,
        )
    }
}
