package io.legado.app.eink.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkCloseButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.control.EInkSliderRow
import io.legado.app.eink.designsystem.control.EInkSteppedSlider
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.theme.EInkShapes
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

/** 边距滑条刻度间隔（dp）。 */
private const val MarginTickStep = 8

// ====================================================================
// 顶部操作条：书签 / 换源 / 刷新 / 缓存。加/移书架不设入口：未加书架的
// 书在退出阅读时经「加入书架」弹框提示（见 Route 退出门控），在架管理
// 走详情页
// ====================================================================

@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    bookmarkEnabled: Boolean,
    bookmarkBadge: Boolean,
    onOpenDetail: () -> Unit,
    onChangeSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onToggleBookmark: () -> Unit,
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
            // 页面书签切换钮（v2 Task 9，设计 §4）：选中态 = 当前页快照
            // bookmarkBadge（模块不自持书签状态），点击 toggle 当前页书签。
            // 素材对为 bookmark_add / bookmark_remove（未加书签显示「加」、
            // 已加书签显示「减」），选中只换素材、配色保持白底；书签能力
            // 关闭（bookmarkEnabled = false，见 pageBookmarkEnabled）时
            // 整颗不渲染（契约 §3.3 降级语义）
            if (bookmarkEnabled) {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_bookmark_add),
                    selectedIcon = painterResource(R.drawable.eink_ic_bookmark_remove),
                    selected = bookmarkBadge,
                    onClick = onToggleBookmark,
                    contentDescription = if (bookmarkBadge) "移除书签" else "添加书签",
                )
            }
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_exchange),
                contentDescription = "换源",
                enabled = !state.isLocalBook,
                onClick = onChangeSource,
            )
            // 「刷新」= 当前章正文清缓存重载（内容损坏排障用），不是目录
            // 追更检查——后者由进书自动触发 refreshToc（静默无反馈，限频
            // 规格见契约 KDoc），模块侧无对应菜单动作
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_refresh_black_24dp),
                contentDescription = "刷新本章",
                onClick = onRefresh,
            )
            EInkOperationBarIcon(
                icon = painterResource(R.drawable.eink_ic_download_line),
                contentDescription = "缓存",
                enabled = !state.isLocalBook,
                onClick = onOpenCachePanel,
            )
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
                iconRes = R.drawable.eink_ic_typography,
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
            EInkSliderRow(
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
    contentHorizontalPadding: Dp = EInkSpacing.m,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 面板内容高度封顶为屏幕 45%，保证正文预览区占多数
        val maxContentHeight = maxHeight * 0.45f
        // 透明点击区：一次性收起到干净阅读界面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(
                    role = Role.Button,
                    onClickLabel = "收起菜单",
                    onClick = onBackdropClick
                ),
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
                EInkCloseButton(onClose = onClose)
            }
            EInkHorizontalDivider()
            // 内容横向内边距参数化：含满宽行（OptionRow 整行按压块）的面板
            // 传 0 让行天然铺满，行组件自管内容内边距；其余面板维持 m
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = contentHorizontalPadding,
                        vertical = EInkSpacing.s,
                    ),
            ) {
                content()
            }
        }
    }
}

// ====================================================================
// 排版参数面板（5 行档位滑条 + 三入口行）
// ====================================================================

/** 三入口行：一行多枚等宽文本按钮（字体配置/信息配置/边距调整）。 */
@Composable
private fun StyleEntryRow(entries: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        entries.forEach { (label, onClick) ->
            EInkButton(
                text = label,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                style = EInkTheme.typography.bodyMedium,
                role = Role.Button,
            )
        }
    }
}

/**
 * 排版面板：5 行档位滑条（字号/字距/缩进/行距/段距，值域来自协商
 * 目录）+ 一行入口按钮（字体配置/信息配置/边距调整，按目录可用性
 * 显隐）。三个弹层均为居中透明卡片，实时预览不被遮挡。
 */
@Composable
internal fun ReaderLayoutPanel(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTextSize: (Int) -> Unit,
    onSetLetterSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onSetLineSpacing: (Int) -> Unit,
    onSetParagraphSpacing: (Int) -> Unit,
    onOpenFonts: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenMargins: () -> Unit,
) {
    EInkSliderRow(
        label = "字号",
        value = style.textSize,
        valueRange = catalog.intRange(Ids.BODY_SIZE),
        thumbLabel = { "${it}sp" },
        tickStep = 4,
        onSetValue = onSetTextSize,
    )
    val lsRange = catalog.floatStepIndexRange(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP)
    EInkSliderRow(
        label = "字距",
        value = (style.letterSpacing / LETTER_SPACING_STEP).roundToInt()
            .coerceIn(lsRange.first, lsRange.last),
        valueRange = lsRange,
        thumbLabel = { "%.2f".format(it * LETTER_SPACING_STEP) },
        tickStep = 2,
        onSetValue = onSetLetterSpacing,
    )
    EInkSliderRow(
        label = "缩进",
        value = style.indentChars,
        valueRange = catalog.intRange(Ids.BODY_INDENT),
        thumbLabel = { "${it}字" },
        tickStep = 1,
        onSetValue = onSetIndent,
    )
    EInkSliderRow(
        label = "行距",
        value = style.lineSpacing,
        valueRange = catalog.intRange(Ids.BODY_LINE_SPACING),
        thumbLabel = { "%.1f倍".format(it / 10f) },
        tickStep = 2,
        onSetValue = onSetLineSpacing,
    )
    EInkSliderRow(
        label = "段距",
        value = style.paragraphSpacing,
        valueRange = catalog.intRange(Ids.BODY_PARAGRAPH_SPACING),
        thumbLabel = { "%.1f行".format(it / 10f) },
        tickStep = 2,
        onSetValue = onSetParagraphSpacing,
    )
    val entries = buildList {
        val fontReady = catalog.available(Ids.BODY_FONT) || catalog.available(Ids.TITLE_FONT) ||
            catalog.available(Ids.HEADER_FONT)
        val infoReady = catalog.available(Ids.TITLE_MODE) || catalog.available(Ids.TITLE_SIZE) ||
            catalog.available(Ids.HEADER_SIZE) || catalog.available(Ids.HEADER_VISIBILITY) ||
            catalog.available(Ids.FOOTER_SIZE)
        if (fontReady) add("字体配置" to onOpenFonts)
        if (infoReady) add("信息配置" to onOpenInfo)
        add("边距调整" to onOpenMargins)
    }
    StyleEntryRow(entries)
}

// ====================================================================
// 边距调整弹框（屏幕居中，内含 正文/页眉/页脚 三 Tab）
// ====================================================================

/**
 * 边距调整弹框：屏幕居中的卡片，四周透明 —— 页眉/页脚/正文边距
 * 调整时实时可见效果（档位滑条，逐 dp 可调）。经 [EInkDialog] 面板
 * 形态承载（关 scrim 透出阅读内容）。
 *
 * 内含三个 Tab：正文 / 页眉 / 页脚，每个 Tab 各 4 行档位滑条：
 * 上边距、下边距、左边距、右边距。× / 系统返回经 [onClose] 回到
 * 排版展开态；点击弹框外空白区域经 [onBackdropClick] 一次性收起
 * 到干净阅读界面。
 */
@Composable
internal fun ReaderMarginDialog(
    catalog: ReaderStyleCatalog,
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
    EInkDialog(
        onDismiss = onClose,
        title = "边距调整",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
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
                maxVertical = catalog.intRange(Ids.BODY_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.BODY_PADDING_LEFT).last,
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
                maxVertical = catalog.intRange(Ids.HEADER_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.HEADER_PADDING_LEFT).last,
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
                maxVertical = catalog.intRange(Ids.FOOTER_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.FOOTER_PADDING_LEFT).last,
                onSetTop = onSetFooterPaddingTop,
                onSetBottom = onSetFooterPaddingBottom,
                onSetLeft = onSetFooterPaddingLeft,
                onSetRight = onSetFooterPaddingRight,
            )
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
    EInkSliderRow(
        label = "上边距",
        value = topDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetTop,
    )
    EInkSliderRow(
        label = "下边距",
        value = bottomDp,
        valueRange = 0..maxVertical,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetBottom,
    )
    EInkSliderRow(
        label = "左边距",
        value = leftDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetLeft,
    )
    EInkSliderRow(
        label = "右边距",
        value = rightDp,
        valueRange = 0..maxHorizontal,
        thumbLabel = { "${it}dp" },
        tickStep = MarginTickStep,
        onSetValue = onSetRight,
    )
}

/** 面板 Tab 行：选中项反白，按压瞬时反色，零动画直接切换。 */
@Composable
internal fun PanelTabRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
                style = EInkTheme.typography.bodyMedium,
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
    onToggleVolumeKeyPage: () -> Unit,
    onTogglePullDownBookmark: () -> Unit,
    onToggleHideStatusBar: () -> Unit,
    onToggleShowReviewBubbles: () -> Unit,
    onOpenTapZones: () -> Unit,
) {
    // 音量键翻页纯按键语义（阅读页按键处理器实时读端口值），切换不触发重排
    ToggleRow(label = "音量键翻页", checked = state.volumeKeyPage, onToggle = onToggleVolumeKeyPage)
    ToggleRow(label = "隐藏状态栏", checked = state.hideStatusBar, onToggle = onToggleHideStatusBar)
    // 能力门控（0.6.0）：宿主未声明书签能力时隐藏下拉书签开关，
    // 未声明段评能力时隐藏段评开关——不留点了无效的死开关
    if (io.legado.app.eink.contract.EInkEngineRegistry.marksEngine?.supportsBookmarks == true) {
        ToggleRow(label = "下拉添加书签", checked = state.pullDownBookmark, onToggle = onTogglePullDownBookmark)
    }
    if (io.legado.app.eink.contract.EInkEngineRegistry.globalSettings.supportsReviewBubbles) {
        ToggleRow(label = "显示段评气泡", checked = state.showReviewBubbles, onToggle = onToggleShowReviewBubbles)
    }
    OptionRow(label = "点击区域设置", onClick = onOpenTapZones)
}

// ====================================================================
// 点击区域蒙层（九宫格简化版）
// ====================================================================

/**
 * 点击区域蒙层（完整模式「点击区域设置」的 E-Ink 简化版）：全屏覆盖
 * 阅读界面（含操作条与面板），阅读手势（点按/滑动/长按）被整体遮挡；
 * 中心格固定菜单不可改（静态展示），其余每格点击在 上一页/下一页 间
 * 切换，返回键或「完成」退出并经 [onApply] 落盘生效（不分保存/放弃
 * 路径，退出即生效）。
 *
 * 纯色不透明背景（E-Ink 半透明叠层会灰化拖影），可切格为描边
 * [EInkButton]（height=null 由布局撑满），中心格为同规格静态描边盒。
 */
@Composable
internal fun ReaderTapZoneOverlay(
    initial: ReaderTapZoneGrid,
    onApply: (ReaderTapZoneGrid) -> Unit,
) {
    var grid by remember { mutableStateOf(initial) }
    val exit = { onApply(grid) }
    BackHandler(onBack = exit)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(EInkSpacing.m),
    ) {
        EInkText(
            text = "点击区域设置",
            style = EInkTheme.typography.titleMedium,
        )
        EInkText(
            text = "点击格子切换上一页/下一页，中心区为菜单。",
            style = EInkTheme.typography.bodySmall,
            modifier = Modifier.padding(top = EInkSpacing.xs),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = EInkSpacing.m),
        ) {
            for (row in 0..2) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    for (column in 0..2) {
                        val cellIndex = row * 3 + column
                        val action = grid.cells[cellIndex]
                        if (cellIndex == ReaderTapZoneGrid.CENTER_INDEX) {
                            // 中心格固定菜单：静态展示，同规格描边但不可点
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(EInkSpacing.xs)
                                    .border(
                                        width = 1.dp,
                                        color = EInkTheme.colorScheme.outline,
                                        shape = EInkShapes.small,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                EInkText(
                                    text = action.label(),
                                    style = EInkTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            EInkButton(
                                text = action.label(),
                                onClick = { grid = grid.toggledPageAt(cellIndex) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(EInkSpacing.xs),
                                height = null,
                                style = EInkTheme.typography.bodyMedium,
                                onClickLabel = "切换为${action.toggledPage().label()}",
                            )
                        }
                    }
                }
            }
        }
        EInkButton(
            text = "完成",
            onClick = exit,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
    }
}

/** 蒙层格子的动作文案（与完整模式点击区域动作名对齐）。 */
private fun ReaderTapZoneAction.label(): String = when (this) {
    ReaderTapZoneAction.MENU -> "菜单"
    ReaderTapZoneAction.NEXT_PAGE -> "下一页"
    ReaderTapZoneAction.PREVIOUS_PAGE -> "上一页"
}

/** 可切格点击后的对侧动作（中心格不经此路径）。 */
private fun ReaderTapZoneAction.toggledPage(): ReaderTapZoneAction =
    if (this == ReaderTapZoneAction.PREVIOUS_PAGE) {
        ReaderTapZoneAction.NEXT_PAGE
    } else {
        ReaderTapZoneAction.PREVIOUS_PAGE
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

/** 开关行：标签在左（纯展示），开/关块在右（EInkButton，开启实心）。
 *  所在面板（其它）以 contentHorizontalPadding = 0 装载，行自管内边距：
 *  标签左缘 16dp、开/关块右边框 24dp（m+s，与 OptionRow 箭头对齐）。 */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = EInkSpacing.m, end = EInkSpacing.m + EInkSpacing.s),
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

/**
 * 选项行（整行点击，按压反色）：所在面板（其它/缓存）以
 * contentHorizontalPadding = 0 装载，行天然铺满屏幕宽度——按压块与
 * 点击区随之满宽；行内内容自管内边距：标签左缘对齐面板内容（m），
 * 箭头右缘对齐开关行按钮边框（m+s，见 [ToggleRow]）。
 */
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
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(start = EInkSpacing.m, end = EInkSpacing.m + EInkSpacing.s),
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
