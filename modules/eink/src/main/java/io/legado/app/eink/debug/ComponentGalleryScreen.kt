package io.legado.app.eink.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkInfoRow
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkSearchHintBar
import io.legado.app.eink.designsystem.control.EInkSearchInputBar
import io.legado.app.eink.designsystem.control.EInkSteppedSlider
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkOperationTab
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkPageIndicator
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover
import kotlinx.coroutines.launch

/**
 * 组件预览页（Design System Gallery，规范 §72 视觉测试面）。
 *
 * 不依赖任何产品界面独立展示全部在用组件，供微调 Token/组件参数后
 * 即时目视验证：灰阶与语义色、形状、按压/选中/禁用交互、内容组件、
 * 滑条与搜索条、顶栏两种动作模式、翻页箭头/页码、底部操作栏、
 * 固定页分页联动、封面占位。
 *
 * 仅 debug 变体入口可见（MineScreen 内 BuildConfig.DEBUG 门控）。
 * 翻页手势（EInkPageSwipe）与真实屏幕同款接线，含 PageTurn 意图上报。
 */
@Composable
fun ComponentGalleryRoute(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EInkTopBar(title = "组件预览", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "foundation-grayscale") {
                SectionHeader("Foundation · 灰阶 Token")
                GrayscaleSwatches()
            }
            item(key = "foundation-semantic") {
                SectionHeader("Foundation · 语义色角色")
                SemanticSwatches()
            }
            item(key = "foundation-shapes") {
                SectionHeader("Foundation · 形状")
                ShapeSamples()
            }
            item(key = "interaction") {
                SectionHeader("Interaction · 按压 / 选中 / 禁用")
                InteractionSamples()
            }
            item(key = "content") {
                SectionHeader("Content · 文本 / 分隔线 / 信息行 / 加载")
                ContentSamples()
            }
            item(key = "control-slider") {
                SectionHeader("Control · 离散滑条（抬手提交）")
                SliderSample()
            }
            item(key = "control-search") {
                SectionHeader("Control · 搜索条")
                SearchSamples()
            }
            item(key = "nav-topbar") {
                SectionHeader("Navigation · 顶栏（内边距动作 / 贴右图标 / 可点击标题）")
                TopBarSamples()
            }
            item(key = "nav-arrows") {
                SectionHeader("Navigation · 翻页箭头 / 页码指示器")
                ArrowsAndIndicatorSample()
            }
            item(key = "nav-operationbar") {
                SectionHeader("Navigation · 底部操作栏（Tab + 翻页）")
                OperationBarSample()
            }
            item(key = "pager") {
                SectionHeader("Pager · 固定页列表（滑动翻页 + 页码联动）")
                PagerSample()
            }
            item(key = "feature-cover") {
                SectionHeader("Feature · 封面占位（bookshelf 层）")
                CoverSample()
                Spacer(modifier = Modifier.height(EInkSpacing.xxl))
            }
        }
    }
}

// ---------------------------------------------------------------------
// Foundation
// ---------------------------------------------------------------------

@Composable
private fun SectionHeader(label: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EInkText(
            text = label,
            style = EInkTheme.typography.titleMedium,
            color = EInkTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = EInkSpacing.m,
                end = EInkSpacing.m,
                top = EInkSpacing.l,
                bottom = EInkSpacing.s
            )
        )
        EInkHorizontalDivider()
    }
}

@Composable
private fun SwatchRow(name: String, color: Color, note: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = SwatchWidth, height = SwatchHeight)
                .background(color)
                .border(width = 1.dp, color = EInkTheme.colorScheme.outline)
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        EInkText(text = name, style = EInkTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        if (note != null) {
            EInkText(
                text = note,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.secondaryContent
            )
        }
    }
}

@Composable
private fun GrayscaleSwatches() {
    val gs = EInkTheme.grayscale
    Column {
        SwatchRow("black", gs.black)
        SwatchRow("gray900", gs.gray900)
        SwatchRow("gray700", gs.gray700)
        SwatchRow("gray500", gs.gray500)
        SwatchRow("gray400", gs.gray400)
        SwatchRow("gray300", gs.gray300)
        SwatchRow("gray200", gs.gray200)
        SwatchRow("gray100", gs.gray100)
        SwatchRow("gray50", gs.gray50)
        SwatchRow("white", gs.white)
    }
}

@Composable
private fun SemanticSwatches() {
    val scheme = EInkTheme.colorScheme
    Column {
        SwatchRow("surface", scheme.surface, "页面表面")
        SwatchRow("surfaceVariant", scheme.surfaceVariant, "次级表面")
        SwatchRow("outline", scheme.outline, "1dp 标准边界")
        SwatchRow("borderStrong", scheme.borderStrong, "2dp 强边界/焦点")
        SwatchRow("divider", scheme.divider, "分隔线实灰")
        SwatchRow("disabledContent", scheme.disabledContent, "禁用内容（实灰非 alpha）")
        SwatchRow("selected", scheme.selected, "选中容器")
        SwatchRow("selectedContent", scheme.selectedContent, "选中内容")
    }
}

@Composable
private fun ShapeSamples() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(EInkSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
    ) {
        ShapeSample("none 0dp", EInkShapes.none)
        ShapeSample("small 2dp", EInkShapes.small)
        ShapeSample("medium 4dp", EInkShapes.medium)
        ShapeSample("large 8dp", EInkShapes.large)
    }
}

@Composable
private fun ShapeSample(label: String, shape: androidx.compose.ui.graphics.Shape) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ShapeBoxSize)
                .background(EInkTheme.colorScheme.surfaceVariant, shape)
                .border(width = 1.dp, color = EInkTheme.colorScheme.outline, shape = shape)
        )
        EInkText(
            text = label,
            style = EInkTheme.typography.labelSmall,
            color = EInkTheme.colorScheme.secondaryContent,
            modifier = Modifier.padding(top = EInkSpacing.xs)
        )
    }
}

// ---------------------------------------------------------------------
// Interaction
// ---------------------------------------------------------------------

@Composable
private fun InteractionSamples() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(EInkSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
    ) {
        // weight 须在 RowScope 内应用，经 modifier 下传给样本
        PressSample(modifier = Modifier.weight(1f))
        SelectedSample(modifier = Modifier.weight(1f))
        DisabledSample(modifier = Modifier.weight(1f))
    }
}

/** 按压瞬时反色：共享 ImmediatePress（120ms 最短保持）+ eInkActionColors。 */
@Composable
private fun PressSample(modifier: Modifier = Modifier) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    DemoTile(
        label = "按压",
        containerColor = colors.containerColor,
        contentColor = colors.contentColor,
        modifier = modifier
            .then(press.modifier)
            .einkClickable(onClickLabel = "按压示例", onClick = {})
    )
}

/** 持久选中：点击切换，实心色块（小面积控件语义）。 */
@Composable
private fun SelectedSample(modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(false) }
    val colors = eInkActionColors(pressed = false, selected = selected)
    DemoTile(
        label = if (selected) "选中" else "未选中",
        containerColor = colors.containerColor,
        contentColor = colors.contentColor,
        modifier = modifier
            .einkClickable(onClickLabel = "选中示例") { selected = !selected }
    )
}

/** 禁用：中灰 disabledContent，不反色、不可点。 */
@Composable
private fun DisabledSample(modifier: Modifier = Modifier) {
    val colors = eInkActionColors(pressed = false, enabled = false)
    DemoTile(
        label = "禁用",
        containerColor = Color.Transparent,
        contentColor = colors.contentColor,
        modifier = modifier
    )
}

@Composable
private fun DemoTile(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(DemoTileHeight)
            .background(containerColor)
            .border(width = 1.dp, color = EInkTheme.colorScheme.outline),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.titleMedium,
            color = contentColor
        )
    }
}

// ---------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------

@Composable
private fun ContentSamples() {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = EInkSpacing.m)) {
        EInkText(text = "titleLarge 标题样式", style = EInkTheme.typography.titleLarge)
        EInkText(
            text = "bodyMedium 正文样式：可读性优先，14sp 下限。",
            style = EInkTheme.typography.bodyMedium
        )
        EInkText(
            text = "labelSmall 标签（次级内容色）",
            style = EInkTheme.typography.labelSmall,
            color = EInkTheme.colorScheme.secondaryContent
        )
        Spacer(modifier = Modifier.height(EInkSpacing.s))
        EInkHorizontalDivider()
        Spacer(modifier = Modifier.height(EInkSpacing.s))
        EInkInfoRow(
            iconRes = R.drawable.eink_ic_author,
            text = "作者 · EInkInfoRow 信息行",
            style = EInkTheme.typography.bodyMedium
        )
        EInkInfoRow(
            iconRes = R.drawable.eink_ic_history,
            text = "最近阅读 · 进度元信息行",
            style = EInkTheme.typography.bodyMedium
        )
        EInkLoading(modifier = Modifier.padding(vertical = EInkSpacing.m))
    }
}

// ---------------------------------------------------------------------
// Control
// ---------------------------------------------------------------------

@Composable
private fun SliderSample() {
    // 拖动仅更新预览值，抬手 onValueChangeFinished 才提交（recreate 类设置范式）
    var preview by remember { mutableIntStateOf(SliderDefault) }
    var committed by rememberSaveable { mutableIntStateOf(SliderDefault) }
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = EInkSpacing.m)) {
        EInkSteppedSlider(
            value = preview,
            onValueChange = { preview = it },
            onValueChangeFinished = { committed = preview },
            valueRange = 0..100,
            tickStep = 25,
            markerStep = SliderDefault,
            markerLabel = "默认",
        )
        EInkText(
            text = "预览 $preview · 已提交 $committed",
            style = EInkTheme.typography.bodyMedium,
            color = EInkTheme.colorScheme.secondaryContent
        )
    }
}

@Composable
private fun SearchSamples() {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        EInkSearchHintBar(onClick = {})
        EInkSearchInputBar(
            value = query,
            onValueChange = { query = it },
            hint = "搜索书名 / 作者（输入演示）",
            action = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_settings),
                    contentDescription = "示例动作",
                    onClick = {},
                    height = SearchActionHeight,
                    iconSize = SearchActionIconSize,
                )
            }
        )
    }
}

// ---------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------

@Composable
private fun TopBarSamples() {
    Column(modifier = Modifier.fillMaxWidth()) {
        EInkTopBar(
            title = "内边距动作模式（默认）",
            onBack = {},
            actions = {
                EInkText(
                    text = "文本动作",
                    style = EInkTheme.typography.titleMedium,
                    color = EInkTheme.colorScheme.primary
                )
            }
        )
        EInkTopBar(
            title = "贴右图标动作模式",
            actionsFillMax = true,
            actions = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_book_add),
                    contentDescription = "加入书架",
                    onClick = {}
                )
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_settings),
                    contentDescription = "设置",
                    onClick = {}
                )
            }
        )
        // 标题可点击（阅读页书名进详情同款）：按压瞬时反色、背景贴屏幕左缘
        var titleEnabled by rememberSaveable { mutableStateOf(true) }
        EInkTopBar(
            title = if (titleEnabled) "标题可点击（按住看反色贴边）" else "标题禁用（中灰不可点）",
            onTitleClick = { titleEnabled = !titleEnabled },
            titleEnabled = titleEnabled,
            titleClickLabel = "切换标题可用状态",
            actionsFillMax = true,
            actions = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_toc),
                    contentDescription = "目录",
                    onClick = {}
                )
            }
        )
    }
}

@Composable
private fun ArrowsAndIndicatorSample() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
    ) {
        EInkPageArrows(
            pageUpEnabled = true,
            pageDownEnabled = true,
            onPageUp = {},
            onPageDown = {}
        )
        EInkPageArrows(
            pageUpEnabled = false,
            pageDownEnabled = false,
            onPageUp = {},
            onPageDown = {}
        )
        Spacer(modifier = Modifier.weight(1f))
        EInkPageIndicator(currentPage = 3, pageCount = 48)
    }
}

@Composable
private fun OperationBarSample() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    EInkOperationBar(
        tabs = listOf(
            EInkOperationTab(
                icon = painterResource(R.drawable.eink_ic_bottom_books_e),
                selectedIcon = painterResource(R.drawable.eink_ic_bottom_books_s),
                contentDescription = "书架"
            ),
            EInkOperationTab(
                icon = painterResource(R.drawable.eink_ic_bottom_person_e),
                selectedIcon = painterResource(R.drawable.eink_ic_bottom_person_s),
                contentDescription = "我的"
            ),
        ),
        selectedTabIndex = selectedTab,
        onTabSelect = { selectedTab = it },
        pageUpEnabled = true,
        pageDownEnabled = true,
        onPageUp = {},
        onPageDown = {},
    )
}

// ---------------------------------------------------------------------
// Pager
// ---------------------------------------------------------------------

@Composable
private fun PagerSample() {
    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val refresh = LocalEInkRefreshController.current
    val items = remember { (1..PagerDemoItemCount).map { "固定页条目 %02d".format(it) } }
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageDown(items.size) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageSize = pager.pageItemCount.coerceAtLeast(1)
    val currentPage = pager.pageStart / pageSize + 1
    val pageCount = ((items.size - 1) / pageSize) + 1

    Column(modifier = Modifier
        .fillMaxWidth()
        .height(PagerDemoHeight)) {
        LazyColumn(
            state = pager.listState,
            userScrollEnabled = false,
            modifier = Modifier
                .weight(1f)
                .EInkPageSwipe(onPageUp = pageUp, onPageDown = pageDown)
        ) {
            itemsIndexed(items, key = { _, item -> item }) { _, item ->
                EInkText(
                    text = item,
                    style = EInkTheme.typography.bodyMedium,
                    modifier = Modifier.padding(
                        horizontal = EInkSpacing.m,
                        vertical = EInkSpacing.s
                    )
                )
                EInkHorizontalDivider()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EInkPageIndicator(currentPage = currentPage, pageCount = pageCount)
            Spacer(modifier = Modifier.weight(1f))
            EInkPageArrows(
                pageUpEnabled = pager.canPageUp(),
                pageDownEnabled = pager.canPageDown(items.size),
                onPageUp = pageUp,
                onPageDown = pageDown
            )
        }
    }
}

// ---------------------------------------------------------------------
// Feature
// ---------------------------------------------------------------------

@Composable
private fun CoverSample() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(EInkSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.m)
    ) {
        EInkBookCover(url = null, name = "书名示例", author = "作者名")
        EInkBookCover(url = null, name = "只有书名")
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------
// 局部常量
// ---------------------------------------------------------------------

private val SwatchWidth = 64.dp
private val SwatchHeight = 24.dp
private val ShapeBoxSize = 56.dp
private val DemoTileHeight = 56.dp
private val SliderDefault = 40
private val SearchActionHeight = 44.dp
private val SearchActionIconSize = 24.dp
private val PagerDemoHeight = 240.dp
private const val PagerDemoItemCount = 60
