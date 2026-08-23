package io.legado.app.eink.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.legado.app.eink.component.EInkBackButton
import io.legado.app.eink.component.EInkHorizontalDivider
import io.legado.app.eink.component.EInkSteppedSlider
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.component.eInkActionColors
import io.legado.app.eink.modifier.rememberImmediatePressState
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import kotlin.math.roundToInt

/** 设置面板类型（UI 局部状态，见 Route 中的 remember）。 */
internal enum class ReaderPanel { LAYOUT, OTHER, CACHE }

/** 操作条高度（与全局顶/底栏一致）。 */
private val BarHeight = 56.dp

/** 底部操作条总占位（操作条 + 顶部分隔线），面板/弹框覆盖层据此避让，保持操作条可见可点。 */
internal val ReaderBottomBarInset = BarHeight + 1.dp

/** 步进器加减按钮触控目标。 */
private val StepTouchTarget = 44.dp

/** 档位滑条行标签列宽（容纳"上边距"三字并对齐各行滑条起点）。 */
private val SliderLabelWidth = 64.dp

/** 边距滑条刻度间隔（dp）。 */
private const val MarginTickStep = 8

// ====================================================================
// 顶部操作条：换源 / 刷新 / 缓存 / 添加书架或移出书架
// ====================================================================

@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    onOpenDetail: () -> Unit,
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
            // 书名点击进详情；按下瞬时反色（规范 §35）。
            // 触控高度与右侧图标操作一致（44dp）
            val detailPress = rememberImmediatePressState()
            val detailEnabled = state.bookUrl.isNotEmpty()
            val detailColors = eInkActionColors(
                pressed = detailPress.isPressed,
                enabled = detailEnabled,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .then(detailPress.modifier)
                    .background(detailColors.containerColor)
                    .staticClickable(
                        enabled = detailEnabled,
                        role = Role.Button,
                        onClickLabel = "书籍详情",
                        onClick = onOpenDetail,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                EInkText(
                    text = state.bookName,
                    color = detailColors.contentColor,
                    style = EInkTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    // 按压反色（共享配色解析 + 120ms 最短保持，规范 §35）
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed, enabled = enabled)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .then(press.modifier)
            .background(colors.containerColor)
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
            colorFilter = ColorFilter.tint(colors.contentColor),
        )
    }
}

// ====================================================================
// 底部操作条：返回 / 目录 / 自动翻页 / 排版 / 其它
// ====================================================================

/**
 * 底部操作条：返回 / 目录 / 自动翻页 / 排版 / 其它，全部为图标按钮。
 *
 * 图标沿用 View 版：目录 ic_toc、自动翻页 ic_auto_page(_stop)、
 * 排版（View 版"界面"）ic_interface_setting、其它（设置）ic_settings、
 * 返回统一 arrow_back：关闭设置面板 → 退出阅读。
 * 五枚图标均匀分布；设置面板打开期间操作条保持可见：排版/其它按钮
 * 呈选中态（实心色块），面板在操作条上方展开（覆盖层按
 * [ReaderBottomBarInset] 避让）。边距调整弹框例外：操作条整体隐藏，
 * 保证正文四周边距实时可见。
 */
@Composable
internal fun ReaderBottomBar(
    state: ReaderUiState,
    selectedPanel: ReaderPanel?,
    onBarBack: () -> Unit,
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
            // 首枚图标离屏幕边缘 16dp（与目录页一致，避开设备圆角区），
            // 五枚图标均匀分布
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 分层返回：关闭设置面板 → 退出阅读
            EInkBackButton(onClick = onBarBack)
            BottomIconAction(
                iconRes = R.drawable.ic_toc,
                contentDescription = "目录",
                onClick = onOpenToc,
            )
            BottomIconAction(
                iconRes = if (state.autoPlay) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page,
                contentDescription = if (state.autoPlay) "停止翻页" else "自动翻页",
                selected = state.autoPlay,
                onClick = onToggleAutoPlay,
            )
            BottomIconAction(
                iconRes = R.drawable.ic_interface_setting,
                contentDescription = "排版",
                selected = selectedPanel == ReaderPanel.LAYOUT,
                onClick = { onOpenPanel(ReaderPanel.LAYOUT) },
            )
            BottomIconAction(
                iconRes = R.drawable.ic_settings,
                contentDescription = "其它设置",
                selected = selectedPanel == ReaderPanel.OTHER,
                onClick = { onOpenPanel(ReaderPanel.OTHER) },
            )
        }
    }
}

/** 操作条图标按钮：48dp 触控目标，按下瞬时反色；选中为实心色块 + 反白图标，按压瞬时覆盖选中。 */
@Composable
private fun BottomIconAction(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    // 分层反馈（规范 §35/§42）
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed, selected = selected)
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(colors.contentColor),
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
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
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
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
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
 * 6 行：字号、字距、缩进、行距、段距为档位滑条行（拖动选值 + ±1 逐级精调），
 * 以及"边距调整"入口。边距调整在独立的居中弹框（[ReaderMarginDialog]）中进行：
 * 底部面板会遮挡页眉/页脚，居中弹框四周透明，调整时实时可见效果。
 */
@Composable
internal fun ReaderLayoutPanel(
    style: ReaderTextStyle,
    onSetTextSize: (Int) -> Unit,
    onSetLetterSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onSetLineSpacing: (Int) -> Unit,
    onSetParagraphSpacing: (Int) -> Unit,
    onOpenMargins: () -> Unit,
) {
    SliderRow(
        label = "字号",
        value = style.textSize,
        valueRange = MIN_TEXT_SIZE..MAX_TEXT_SIZE,
        thumbLabel = { "${it}sp" },
        tickStep = 4,
        onSetValue = onSetTextSize,
    )
    SliderRow(
        label = "字距",
        value = (style.letterSpacing / LETTER_SPACING_STEP).roundToInt()
            .coerceIn(0, LETTER_SPACING_STEPS),
        valueRange = 0..LETTER_SPACING_STEPS,
        thumbLabel = { "%.2f".format(it * LETTER_SPACING_STEP) },
        tickStep = 2,
        onSetValue = onSetLetterSpacing,
    )
    SliderRow(
        label = "缩进",
        value = style.indentChars,
        valueRange = MIN_INDENT_CHARS..MAX_INDENT_CHARS,
        thumbLabel = { "${it}字" },
        tickStep = 1,
        onSetValue = onSetIndent,
    )
    SliderRow(
        label = "行距",
        value = style.lineSpacing,
        valueRange = 0..MAX_LINE_SPACING,
        thumbLabel = { "%.1f倍".format(it / 10f) },
        tickStep = 5,
        onSetValue = onSetLineSpacing,
    )
    SliderRow(
        label = "段距",
        value = style.paragraphSpacing,
        valueRange = 0..MAX_PARAGRAPH_SPACING,
        thumbLabel = { "%.1f行".format(it / 10f) },
        tickStep = 2,
        onSetValue = onSetParagraphSpacing,
    )
    OptionRow(label = "边距调整") { onOpenMargins() }
}

// ====================================================================
// 边距调整弹框（屏幕居中，内含 正文/页眉/页脚 三 Tab）
// ====================================================================

/**
 * 边距调整弹框：屏幕居中的卡片，四周透明 —— 页眉/页脚/正文边距
 * 调整时实时可见效果（档位滑条，逐 dp 可调）。
 *
 * 内含三个 Tab：正文 / 页眉 / 页脚，每个 Tab 各 4 行档位滑条：
 * 上边距、下边距、左边距、右边距。
 */
@Composable
internal fun ReaderMarginDialog(
    style: ReaderTextStyle,
    onSetPaddingTop: (Int) -> Unit,
    onSetPaddingBottom: (Int) -> Unit,
    onSetPaddingLeft: (Int) -> Unit,
    onSetPaddingRight: (Int) -> Unit,
    onSetHeaderPaddingTop: (Int) -> Unit,
    onSetHeaderPaddingBottom: (Int) -> Unit,
    onSetHeaderPaddingLeft: (Int) -> Unit,
    onSetHeaderPaddingRight: (Int) -> Unit,
    onSetFooterPaddingTop: (Int) -> Unit,
    onSetFooterPaddingBottom: (Int) -> Unit,
    onSetFooterPaddingLeft: (Int) -> Unit,
    onSetFooterPaddingRight: (Int) -> Unit,
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
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
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
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
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
                        maxVertical = MAX_PADDING_VERTICAL,
                        maxHorizontal = MAX_PADDING_HORIZONTAL,
                        onSetTop = onSetPaddingTop,
                        onSetBottom = onSetPaddingBottom,
                        onSetLeft = onSetPaddingLeft,
                        onSetRight = onSetPaddingRight,
                    )

                    1 -> MarginRows(
                        topDp = style.headerPaddingTop,
                        bottomDp = style.headerPaddingBottom,
                        leftDp = style.headerPaddingLeft,
                        rightDp = style.headerPaddingRight,
                        maxVertical = MAX_PADDING_VERTICAL,
                        maxHorizontal = MAX_PADDING_VERTICAL,
                        onSetTop = onSetHeaderPaddingTop,
                        onSetBottom = onSetHeaderPaddingBottom,
                        onSetLeft = onSetHeaderPaddingLeft,
                        onSetRight = onSetHeaderPaddingRight,
                    )

                    else -> MarginRows(
                        topDp = style.footerPaddingTop,
                        bottomDp = style.footerPaddingBottom,
                        leftDp = style.footerPaddingLeft,
                        rightDp = style.footerPaddingRight,
                        maxVertical = MAX_PADDING_VERTICAL,
                        maxHorizontal = MAX_PADDING_VERTICAL,
                        onSetTop = onSetFooterPaddingTop,
                        onSetBottom = onSetFooterPaddingBottom,
                        onSetLeft = onSetFooterPaddingLeft,
                        onSetRight = onSetFooterPaddingRight,
                    )
                }
            }
        }
    }
}

/** 单个区域的边距 4 行档位滑条：上边距、下边距、左边距、右边距。 */
@Composable
private fun MarginRows(
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
    SliderRow(
        label = "上边距",
        value = topDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetTop,
    )
    SliderRow(
        label = "下边距",
        value = bottomDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetBottom,
    )
    SliderRow(
        label = "左边距",
        value = leftDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetLeft,
    )
    SliderRow(
        label = "右边距",
        value = rightDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetRight,
    )
}

// ====================================================================
// 边距调整弹框（屏幕居中）
// ====================================================================

/** 关闭按钮（×）：按压反色。 */
@Composable
private fun CloseButton(onClose: () -> Unit) {
    // 按压反色；× 常态为次级色（onSurfaceVariant），按压反色为白
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = "×",
            style = EInkTheme.typography.titleLarge,
            color = colors.secondaryContentColor,
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
            val press = rememberImmediatePressState()
            val colors = eInkActionColors(pressed = press.isPressed, selected = index == selected)
            // 按压/选中时边框取容器色（黑），常态为轮廓线
            val borderColor = if (colors.containerColor != Color.Transparent) {
                colors.containerColor
            } else {
                scheme.outline
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .then(press.modifier)
                    .background(color = colors.containerColor, shape = EInkShapes.small)
                    .border(width = 1.dp, color = borderColor, shape = EInkShapes.small)
                    .staticClickable(role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(
                    text = label,
                    color = colors.contentColor,
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

/**
 * 档位滑条行：标签在左，[−] 滑条 [+] 在右，当前数值印在滑块上。
 *
 * 滑条支持拖动选值与点按轨道跳档，[−]/[+] 为 ±1 逐级精调（行内按值域钳制）。
 */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    thumbLabel: (Int) -> String,
    tickStep: Int,
    onSetValue: (Int) -> Unit,
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
        StepButton(
            glyph = "−",
            onClickLabel = "减小",
            onClick = { onSetValue((value - 1).coerceIn(valueRange.first, valueRange.last)) },
        )
        EInkSteppedSlider(
            value = value,
            onValueChange = onSetValue,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            thumbLabel = thumbLabel,
            tickStep = tickStep,
        )
        StepButton(
            glyph = "＋",
            onClickLabel = "增大",
            onClick = { onSetValue((value + 1).coerceIn(valueRange.first, valueRange.last)) },
        )
    }
}

@Composable
private fun StepButton(glyph: String, onClickLabel: String, onClick: () -> Unit) {
    // 按压反色（共享配色解析 + 120ms 最短保持，规范 §35）
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(StepTouchTarget)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = glyph,
            style = EInkTheme.typography.titleLarge,
            color = colors.contentColor,
        )
    }
}

/** 开关行：标签在左，状态块在右（开启实心黑）；按压时整行反色。 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val scheme = EInkTheme.colorScheme
    // 按压反色：整行黑底、文字白字，状态块同步反转（块内配色是反色的反色，属特例）
    val press = rememberImmediatePressState()
    val isPressed = press.isPressed
    val rowColors = eInkActionColors(pressed = isPressed)
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
            .background(rowColors.containerColor)
            .staticClickable(role = Role.Switch, onClick = onToggle)
            .padding(end = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
            color = rowColors.contentColor,
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
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .staticClickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            color = colors.contentColor,
        )
        Spacer(modifier = Modifier.weight(1f))
        EInkText(
            text = "›",
            style = EInkTheme.typography.titleLarge,
            color = colors.secondaryContentColor,
        )
    }
}
