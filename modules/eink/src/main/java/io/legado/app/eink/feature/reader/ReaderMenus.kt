package io.legado.app.eink.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkSteppedSlider
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** 设置面板类型（UI 局部状态，见 Route 中的 remember）。 */
internal enum class ReaderPanel { LAYOUT, PROGRESS, OTHER, CACHE }

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
// 顶部操作条：换源 / 刷新 / 缓存 / 加书架（仅未加书架时显示；
// 阅读页不提供移出书架，管理书架走书架长按或详情页）
// ====================================================================

@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    onOpenDetail: () -> Unit,
    onChangeSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onAddToBookshelf: () -> Unit,
) {
    // 通用顶栏（贴右动作模式）：书名可点击进详情（按压反色、背景贴
    // 屏幕左缘、禁用中灰），动作按钮直接使用 EInkOperationBarIcon
    EInkTopBar(
        title = state.bookName,
        onTitleClick = onOpenDetail,
        titleEnabled = state.bookUrl.isNotEmpty(),
        titleClickLabel = "书籍详情",
        actionsFillMax = true,
        actions = {
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_exchange),
                contentDescription = "换源",
                enabled = !state.isLocalBook,
                onClick = onChangeSource,
            )
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_refresh_black_24dp),
                contentDescription = "刷新",
                onClick = onRefresh,
            )
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_download_line),
                contentDescription = "缓存",
                enabled = !state.isLocalBook,
                onClick = onOpenCachePanel,
            )
            if (!state.inBookshelf) {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_book_add),
                    contentDescription = "加书架",
                    onClick = onAddToBookshelf,
                )
            }
        },
    )
}

// ====================================================================
// 底部操作条：返回 / 目录 / 自动翻页 / 排版 / 其它
// ====================================================================

/**
 * 底部操作条：返回 / 目录 / 进度与翻页 / 排版 / 其它，全部为图标按钮。
 *
 * 图标沿用 View 版：目录 ic_toc、进度与翻页 ic_progress（水平滑杆
 * 旋钮，Material commit）、排版（View 版"界面"）ic_interface_setting、
 * 其它（设置）ic_settings、返回统一 arrow_back：关闭设置面板 → 退出
 * 阅读。五枚图标复用 [EInkOperationBarIcon]，居左连续排列、贴屏幕
 * 左缘；设置面板打开期间操作条保持可见：排版/其它按钮呈选中态
 * （无素材对，回落实心色块），面板在操作条上方展开（覆盖层按
 * [ReaderBottomBarInset] 避让）。边距调整弹框例外：操作条整体隐藏，
 * 保证正文四周边距实时可见。
 */
@Composable
internal fun ReaderBottomBar(
    state: ReaderUiState,
    selectedPanel: ReaderPanel?,
    onBarBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenPanel: (ReaderPanel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分隔线在顶部：与上方正文分界
        EInkHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .background(EInkTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分层返回：关闭设置面板 → 退出阅读
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_arrow_back),
                contentDescription = "返回",
                onClick = onBarBack,
            )
            BottomIconAction(
                iconRes = R.drawable.eink_ic_toc,
                contentDescription = "目录",
                onClick = onOpenToc,
            )
            BottomIconAction(
                iconRes = R.drawable.eink_ic_progress,
                contentDescription = "进度与翻页",
                selected = selectedPanel == ReaderPanel.PROGRESS,
                onClick = { onOpenPanel(ReaderPanel.PROGRESS) },
            )
            BottomIconAction(
                iconRes = R.drawable.eink_ic_interface_setting,
                contentDescription = "排版",
                selected = selectedPanel == ReaderPanel.LAYOUT,
                onClick = { onOpenPanel(ReaderPanel.LAYOUT) },
            )
            BottomIconAction(
                iconRes = R.drawable.eink_ic_settings,
                contentDescription = "其它设置",
                selected = selectedPanel == ReaderPanel.OTHER,
                onClick = { onOpenPanel(ReaderPanel.OTHER) },
            )
        }
    }
}

/**
 * 底部操作条图标按钮：复用 [EInkOperationBarIcon] 默认尺寸（高度撑满
 * 操作条，宽度自适应 min(屏幕宽/6, 1.7 倍高)，28dp 图标）。
 * 按下瞬时反色；选中无素材对，回落实心色块 + 反白图标（规范 §35/§42），
 * 按压瞬时覆盖选中。
 */
@Composable
private fun BottomIconAction(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    EInkOperationBarIcon(
        icon = painterResource(iconRes),
        contentDescription = contentDescription,
        onClick = onClick,
        selected = selected,
    )
}

// ====================================================================
// 进度与翻页面板：页内进度 / 自动翻页间隔 / 自动翻页开关
// ====================================================================

/**
 * 自动翻页间隔档位（秒）：非线性映射——短时长逐秒细步进，
 * 长时长不常用，5/10/30 秒粗步进直到 120。
 */
private val AutoIntervalStepsSec = listOf(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120
)

/** 秒 → 最近档位索引（完整模式 UI 写入的表外值显示时就近吸附）。 */
private fun autoIntervalStepOf(sec: Int): Int =
    AutoIntervalStepsSec.indices.minByOrNull { abs(AutoIntervalStepsSec[it] - sec) } ?: 0

@Composable
internal fun ReaderProgressPanel(
    state: ReaderUiState,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSkipToPage: (Int) -> Unit,
    onSetAutoInterval: (Int) -> Unit,
    onToggleAutoPlay: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        PageProgressRow(
            state = state,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
            onSkipToPage = onSkipToPage,
        )
        // 间隔滑条仅在自动翻页开启后出现，供运行中调节
        // （收起菜单时按新时长启动/重启倒计时）
        if (state.autoPlay) {
            SliderRow(
                label = null,
                value = autoIntervalStepOf(state.autoPlayIntervalSec),
                valueRange = 0..AutoIntervalStepsSec.lastIndex,
                thumbLabel = { "${AutoIntervalStepsSec[it]}s" },
                tickStep = 0,
                onSetValue = { step -> onSetAutoInterval(AutoIntervalStepsSec[step]) },
            )
        }
        // 自动翻页动作：横向占满；运行中（含菜单打开时的暂停）实心反白
        EInkButton(
            text = if (state.autoPlay) "停止自动翻页" else "开启自动翻页",
            onClick = onToggleAutoPlay,
            modifier = Modifier.fillMaxWidth(),
            selected = state.autoPlay,
            style = EInkTheme.typography.bodyMedium,
            onClickLabel = if (state.autoPlay) "停止自动翻页" else "开启自动翻页",
        )
    }
}

@Composable
private fun PageProgressRow(
    state: ReaderUiState,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSkipToPage: (Int) -> Unit,
) {
    val maxPage = (state.pageCount - 1).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EInkButton(
            text = "上一章",
            onClick = onPrevChapter,
            enabled = state.chapterIndex > 0,
            bordered = false,
            style = EInkTheme.typography.bodyMedium,
            contentPadding = PaddingValues(horizontal = 14.dp),
        )
        EInkSteppedSlider(
            value = state.pageIndex.coerceIn(0, maxPage),
            onValueChange = onSkipToPage,
            valueRange = 0..(if (maxPage > 0) maxPage else 0),
            modifier = Modifier.weight(1f),
            enabled = maxPage > 0,
            thumbLabel = { "${it + 1}" },
            tickStep = 0,
        )
        EInkButton(
            text = "下一章",
            onClick = onNextChapter,
            enabled = state.chapterIndex < state.chapterSize - 1,
            bordered = false,
            style = EInkTheme.typography.bodyMedium,
            contentPadding = PaddingValues(horizontal = 14.dp),
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
 * 逐级回退（× / 系统返回 / 操作条返回）经 [onClose] 只关本面板；
 * 点击面板外空白区域经 [onBackdropClick] 一次性收起到干净阅读界面。
 * 零动画直接出现/消失。
 */
@Composable
internal fun ReaderPanelContainer(
    title: String,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 面板内容高度封顶为屏幕 45%，保证正文预览区占多数
        val maxContentHeight = maxHeight * 0.45f
        // 透明点击区：一次性收起到干净阅读界面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(role = Role.Button, onClickLabel = "收起菜单", onClick = onBackdropClick),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(EInkTheme.colorScheme.surface)
                // 消费面板内空白处点击，避免透传到关闭层
                .einkClickable(onClick = {}),
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
 * 上边距、下边距、左边距、右边距。× / 系统返回经 [onClose] 回到
 * 排版展开态；点击弹框外空白区域经 [onBackdropClick] 一次性收起
 * 到干净阅读界面。
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
    onBackdropClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 透明点击区：一次性收起到干净阅读界面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(role = Role.Button, onClickLabel = "收起菜单", onClick = onBackdropClick),
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
                .einkClickable(onClick = {}),
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
            .einkClickable(role = Role.Button, onClick = onClose),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        labels.forEachIndexed { index, label ->
            EInkButton(
                text = label,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                selected = index == selected,
                height = 40.dp,
                role = Role.Tab,
            )
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
) {
    ToggleRow(label = "保持屏幕常亮", checked = state.keepScreenOn, onToggle = onToggleKeepScreenOn)
    ToggleRow(label = "正文加粗", checked = state.textBold, onToggle = onToggleTextBold)
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

/**
 * 档位滑条行：标签在左（可空，空时滑条占满），[−] 滑条 [+] 在右，
 * 当前数值印在滑块上。
 *
 * 滑条支持拖动选值与点按轨道跳档，[−]/[+] 为逐档精调（行内按值域钳制）。
 */
@Composable
private fun SliderRow(
    label: String?,
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
        if (label != null) {
            EInkText(
                text = label,
                modifier = Modifier.width(SliderLabelWidth),
                style = EInkTheme.typography.bodyMedium,
            )
        }
        EInkButton(
            text = "−",
            onClick = { onSetValue((value - 1).coerceIn(valueRange.first, valueRange.last)) },
            modifier = Modifier.size(StepTouchTarget),
            bordered = false,
            height = null,
            style = EInkTheme.typography.titleLarge,
            onClickLabel = "减小",
        )
        EInkSteppedSlider(
            value = value,
            onValueChange = onSetValue,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            thumbLabel = thumbLabel,
            tickStep = tickStep,
        )
        EInkButton(
            text = "＋",
            onClick = { onSetValue((value + 1).coerceIn(valueRange.first, valueRange.last)) },
            modifier = Modifier.size(StepTouchTarget),
            bordered = false,
            height = null,
            style = EInkTheme.typography.titleLarge,
            onClickLabel = "增大",
        )
    }
}

/** 开关行：标签在左（纯展示），开/关块在右（EInkButton，开启实心）。 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(end = EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            modifier = Modifier.weight(1f),
            style = EInkTheme.typography.bodyMedium,
        )
        EInkButton(
            text = if (checked) "开" else "关",
            onClick = onToggle,
            modifier = Modifier.width(64.dp),
            height = 44.dp,
            selected = checked,
            role = Role.Switch,
        )
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
            .einkClickable(role = Role.Button, onClick = onClick),
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
