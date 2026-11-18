package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 归一弹框聚合轴：按功能（Tab 切换）/ 按区域（分组全景 + 锚点直达）。 */
internal enum class StyleAxis { FUNCTION, REGION }

/** 功能轴 Tab（三入口直达目标）。 */
internal enum class StyleFunctionTab(val displayLabel: String) {
    FONTS("字体"),
    INFO("信息"),
    MARGIN("边距"),
}

/** 区域轴分组（spec §4.3 行清单归组）。 */
internal enum class StyleRegionGroup(val displayLabel: String) {
    BODY("正文"),
    TITLE("标题"),
    HEADER("页眉"),
    FOOTER("页脚"),
}

/**
 * 区域分组可见性：组内任一参数行可用即可见（锚点与组同步隐藏，spec §4.3）。
 * 行→参数 id 对应 spec §4.3 现状行清单。
 */
internal fun regionGroupVisible(catalog: ReaderStyleCatalog, group: StyleRegionGroup): Boolean {
    val ids = when (group) {
        // 五滑条 + 正文四边距
        StyleRegionGroup.BODY -> listOf(
            Ids.BODY_SIZE, Ids.BODY_LETTER_SPACING, Ids.BODY_INDENT,
            Ids.BODY_LINE_SPACING, Ids.BODY_PARAGRAPH_SPACING,
            Ids.BODY_PADDING_TOP, Ids.BODY_PADDING_BOTTOM,
            Ids.BODY_PADDING_LEFT, Ids.BODY_PADDING_RIGHT,
        )
        // 标题位置 + 标题字号
        StyleRegionGroup.TITLE -> listOf(Ids.TITLE_MODE, Ids.TITLE_SIZE)
        // 页眉显示 + 页眉页脚字号 + 页眉四边距
        StyleRegionGroup.HEADER -> listOf(
            Ids.HEADER_MODE, Ids.HEADER_SIZE,
            Ids.HEADER_PADDING_TOP, Ids.HEADER_PADDING_BOTTOM,
            Ids.HEADER_PADDING_LEFT, Ids.HEADER_PADDING_RIGHT,
        )
        // 页脚显示 + 页眉页脚字号 + 页脚四边距
        StyleRegionGroup.FOOTER -> listOf(
            Ids.FOOTER_VISIBILITY, Ids.FOOTER_SIZE,
            Ids.FOOTER_PADDING_TOP, Ids.FOOTER_PADDING_BOTTOM,
            Ids.FOOTER_PADDING_LEFT, Ids.FOOTER_PADDING_RIGHT,
        )
    }
    return ids.any { catalog.available(it) }
}

/** 归一弹框全部参数写回调（功能轴三内容 + 区域视图共用；spec §4.2 VM 零新增状态）。 */
internal class ReaderStyleActions(
    val setTextSize: (Int) -> Unit,
    val setLetterSpacing: (Int) -> Unit,
    val setIndent: (Int) -> Unit,
    val setLineSpacing: (Int) -> Unit,
    val setParagraphSpacing: (Int) -> Unit,
    val setReaderFont: (ReaderFontSelection) -> Unit,
    val setBodyWeight: (Int) -> Unit,
    val setTitleWeight: (Int) -> Unit,
    val setChineseConverterType: (Int) -> Unit,
    val setTitleMode: (Int) -> Unit,
    val setTitleSizeFollowBody: (Boolean) -> Unit,
    val setTitleSize: (Int) -> Unit,
    val setHeaderMode: (Int) -> Unit,
    val setFooterVisible: (Boolean) -> Unit,
    val setTipSize: (Int) -> Unit,
    val setPaddingTop: (Int) -> Unit,
    val setPaddingBottom: (Int) -> Unit,
    val setPaddingLeft: (Int) -> Unit,
    val setPaddingRight: (Int) -> Unit,
    val setHeaderPaddingTop: (Int) -> Unit,
    val setHeaderPaddingBottom: (Int) -> Unit,
    val setHeaderPaddingLeft: (Int) -> Unit,
    val setHeaderPaddingRight: (Int) -> Unit,
    val setFooterPaddingTop: (Int) -> Unit,
    val setFooterPaddingBottom: (Int) -> Unit,
    val setFooterPaddingLeft: (Int) -> Unit,
    val setFooterPaddingRight: (Int) -> Unit,
)

/**
 * 排版设置归一弹框（spec §4）：居中透明卡片，顶部聚合切换 [按功能|按区域]；
 * 功能轴 = Tab 行（字体/信息/边距，三入口直达）；区域轴 = 锚点行 + 四分组
 * 全景滚动（锚点无动画直达）。切轴/切 Tab 不关闭弹框。
 *
 * [onFontTabShown]：字体 Tab 可见时拉取字体列表（入口直达与框内切换统一预取路径）。
 */
@Composable
internal fun ReaderStyleConfigDialog(
    initialTab: StyleFunctionTab,
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    fontOptions: List<ReaderFontOption>,
    recentFontPaths: List<String>,
    chineseConverterType: Int,
    actions: ReaderStyleActions,
    onOpenFontSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onFontTabShown: () -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var axis by rememberSaveable { mutableStateOf(StyleAxis.FUNCTION) }
    var functionTab by rememberSaveable { mutableStateOf(initialTab) }
    // 字体数据预取：功能轴·字体 Tab 可见即拉取（入口直达与框内切换统一路径；
    // 只在字体 Tab 真正出现时触发，不经字体 Tab 不浪费枚举 IO）
    LaunchedEffect(axis, functionTab) {
        if (axis == StyleAxis.FUNCTION && functionTab == StyleFunctionTab.FONTS) {
            onFontTabShown()
        }
    }
    EInkDialog(
        onDismiss = onClose,
        title = "排版设置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        Column {
            PanelTabRow(
                labels = listOf("按功能", "按区域"),
                selected = if (axis == StyleAxis.FUNCTION) 0 else 1,
                onSelect = { axis = if (it == 0) StyleAxis.FUNCTION else StyleAxis.REGION },
            )
            when (axis) {
                StyleAxis.FUNCTION -> {
                    PanelTabRow(
                        labels = StyleFunctionTab.entries.map { it.displayLabel },
                        selected = functionTab.ordinal,
                        onSelect = { functionTab = StyleFunctionTab.entries[it] },
                    )
                    when (functionTab) {
                        StyleFunctionTab.FONTS -> ReaderFontConfigContent(
                            catalog = catalog,
                            style = style,
                            fontOptions = fontOptions,
                            recentFontPaths = recentFontPaths,
                            chineseConverterType = chineseConverterType,
                            onSetFont = actions.setReaderFont,
                            onOpenFontSettings = onOpenFontSettings,
                            onSetBodyWeight = actions.setBodyWeight,
                            onSetTitleWeight = actions.setTitleWeight,
                            onSetChineseConverterType = actions.setChineseConverterType,
                            onPickFolder = onPickFolder,
                        )
                        StyleFunctionTab.INFO -> ReaderInfoConfigContent(
                            catalog = catalog,
                            style = style,
                            titleSizeFollowBody = titleSizeFollowBody,
                            onSetTitleMode = actions.setTitleMode,
                            onSetTitleSizeFollowBody = actions.setTitleSizeFollowBody,
                            onSetTitleSize = actions.setTitleSize,
                            onSetHeaderMode = actions.setHeaderMode,
                            onSetFooterVisible = actions.setFooterVisible,
                            onSetTipSize = actions.setTipSize,
                        )
                        StyleFunctionTab.MARGIN -> ReaderMarginConfigContent(
                            catalog = catalog,
                            style = style,
                            onSetPaddingTop = actions.setPaddingTop,
                            onSetPaddingBottom = actions.setPaddingBottom,
                            onSetPaddingLeft = actions.setPaddingLeft,
                            onSetPaddingRight = actions.setPaddingRight,
                            onSetHeaderPaddingTop = actions.setHeaderPaddingTop,
                            onSetHeaderPaddingBottom = actions.setHeaderPaddingBottom,
                            onSetHeaderPaddingLeft = actions.setHeaderPaddingLeft,
                            onSetHeaderPaddingRight = actions.setHeaderPaddingRight,
                            onSetFooterPaddingTop = actions.setFooterPaddingTop,
                            onSetFooterPaddingBottom = actions.setFooterPaddingBottom,
                            onSetFooterPaddingLeft = actions.setFooterPaddingLeft,
                            onSetFooterPaddingRight = actions.setFooterPaddingRight,
                        )
                    }
                }
                StyleAxis.REGION -> ReaderStyleRegionView(
                    catalog = catalog,
                    style = style,
                    titleSizeFollowBody = titleSizeFollowBody,
                    actions = actions,
                )
            }
        }
    }
}

/**
 * 区域轴视图：锚点行（组可见性过滤后）+ 四分组全景滚动（spec §4.4：
 * 无动画直达、组标题流内非吸顶、组与锚点同步隐藏、切入回顶部）。
 */
@Composable
private fun ReaderStyleRegionView(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    actions: ReaderStyleActions,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val groupOffsets = remember { mutableStateMapOf<StyleRegionGroup, Int>() }
    val visibleGroups = StyleRegionGroup.entries.filter { regionGroupVisible(catalog, it) }
    Column {
        // 锚点行：与功能轴 Tab 行同形态（等宽分段按钮），但语义是「跳转到某组」
        // 的定位动作而非页签选择——按组名给可本地化 contentDescription（spec §4.4）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = EInkSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        ) {
            visibleGroups.forEach { group ->
                EInkButton(
                    text = group.displayLabel,
                    onClick = {
                        scope.launch { scrollState.scrollTo(groupOffsets[group] ?: 0) }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "跳转到${group.displayLabel}" },
                    style = EInkTheme.typography.bodyMedium,
                    role = Role.Button,
                )
            }
        }
        Column(
            modifier = Modifier
                // heightIn 必须在 verticalScroll 外侧：钳制视口 ≤360dp；
                // 顺序颠倒会把滚动内容钳到 360dp（内容截断、maxValue≈0 死滚动）
                .heightIn(max = 360.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            visibleGroups.forEach { group ->
                Column(
                    modifier = Modifier.onGloballyPositioned {
                        groupOffsets[group] = it.positionInParent().y.roundToInt()
                    },
                ) {
                    GroupHeading(group.displayLabel)
                    when (group) {
                        StyleRegionGroup.BODY -> {
                            BodyBasicRows(
                                catalog = catalog, style = style,
                                onSetTextSize = actions.setTextSize,
                                onSetLetterSpacing = actions.setLetterSpacing,
                                onSetIndent = actions.setIndent,
                                onSetLineSpacing = actions.setLineSpacing,
                                onSetParagraphSpacing = actions.setParagraphSpacing,
                            )
                            BodyMarginRows(
                                catalog, style,
                                actions.setPaddingTop, actions.setPaddingBottom,
                                actions.setPaddingLeft, actions.setPaddingRight,
                            )
                        }
                        StyleRegionGroup.TITLE -> TitleInfoRows(
                            catalog = catalog,
                            style = style,
                            titleSizeFollowBody = titleSizeFollowBody,
                            onSetTitleMode = actions.setTitleMode,
                            onSetTitleSizeFollowBody = actions.setTitleSizeFollowBody,
                            onSetTitleSize = actions.setTitleSize,
                        )
                        StyleRegionGroup.HEADER -> {
                            HeaderModeRow(catalog, style, actions.setHeaderMode)
                            // 页眉页脚字号：同一行组件页眉/页脚组双呈现（spec §4.3 注）
                            TipSizeRow(catalog, style, actions.setTipSize)
                            HeaderMarginRows(
                                catalog, style,
                                actions.setHeaderPaddingTop, actions.setHeaderPaddingBottom,
                                actions.setHeaderPaddingLeft, actions.setHeaderPaddingRight,
                            )
                        }
                        StyleRegionGroup.FOOTER -> {
                            FooterModeRow(catalog, style, actions.setFooterVisible)
                            TipSizeRow(catalog, style, actions.setTipSize)
                            FooterMarginRows(
                                catalog, style,
                                actions.setFooterPaddingTop, actions.setFooterPaddingBottom,
                                actions.setFooterPaddingLeft, actions.setFooterPaddingRight,
                            )
                        }
                    }
                }
            }
        }
    }
}
