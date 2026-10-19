package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkCloseButton
import io.legado.app.eink.designsystem.control.EInkSliderRow
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/** 书架底部操作条高度避让（操作条 56dp + 1dp 防重叠，对齐阅读页面板挂载）。 */
private val BookshelfBottomBarInset = 57.dp

/**
 * 书架个性化配置面板（决策补充 7）：底部锚定卡片、悬于底部操作条上方、
 * 关 scrim 实时预览——容器形态与阅读页排版/边距面板一致（ReaderPanelContainer
 * 同款结构）。每档改动即提交（[onStyleChange] 传改后完整快照，VM 乐观层
 * 承接）。
 *
 * 行构成与布局联动：布局（书架布局/网格列表双按钮，选中高亮）与未读角标 /
 * 新章高亮恒显；网格布局追加封面宽度（40..150dp 步进 5）与书名行数
 * （1..5 步进 1），列表布局追加最新章节。全部布尔/二选一行统一为
 * 双按钮并排、选中高亮，同值点击不提交。
 */
@Composable
fun BookshelfStylePanel(
    style: BookshelfStyle,
    onStyleChange: (BookshelfStyle) -> Unit,
    onGroupsEnabledChange: (Boolean) -> Unit,
    showGroupsToggle: Boolean,
    onDismiss: () -> Unit,
) {
    // 样式改动即提交，返回键只需收面板；不拦截会透传系统退出应用
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // 模态在场登记：面板打开期间音量键翻页放行系统，不穿透翻动书架
    androidx.compose.runtime.DisposableEffect(Unit) {
        io.legado.app.eink.designsystem.control.EInkModalPresence.acquire()
        onDispose { io.legado.app.eink.designsystem.control.EInkModalPresence.release() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 底部避让操作条：面板与其背后的点击收起层都不遮盖操作条，
            // 保持其可见可点（对齐阅读页面板挂载方式）
            .padding(bottom = BookshelfBottomBarInset)
    ) {
        // 面板内容高度封顶 60%（书架面板行数多于阅读排版面板），防小屏溢出
        val maxContentHeight = maxHeight * 0.6f
        // 透明点击层：点面板外空白处一次性收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(
                    role = Role.Button,
                    onClickLabel = "收起面板",
                    onClick = onDismiss
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
                    text = "书架样式",
                    style = EInkTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                EInkCloseButton(onClose = onDismiss)
            }
            EInkHorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
            ) {
                // 首项：支持分组展示（仅在筛选视图显示——分组展示态（无筛选/
                // 组视图）不显示本项，避免就地撕裂当前视图的交互）
                if (showGroupsToggle) {
                    ChoiceRow("支持分组展示", style.groupsEnabled, onText = "开启", offText = "关闭") { target ->
                        onGroupsEnabledChange(target)
                    }
                }
                ChoiceRow("书架布局", style.isGridLayout, onText = "网格", offText = "列表") { target ->
                    onStyleChange(style.copy(isGridLayout = target))
                }
                // 未读角标 / 新章高亮两种布局都生效，恒显
                ChoiceRow("未读角标", style.showUnreadBadge, onText = "显示", offText = "隐藏") { value ->
                    onStyleChange(style.copy(showUnreadBadge = value))
                }
                ChoiceRow("新章高亮", style.highlightNewChapter, onText = "显示", offText = "隐藏") { value ->
                    onStyleChange(style.copy(highlightNewChapter = value))
                }
                if (style.isGridLayout) {
                    // 网格专属：封面宽度 / 书名行数（四字标签，标签槽放宽不折行）
                    EInkSliderRow(
                        label = "封面宽度",
                        value = style.gridCoverWidth,
                        valueRange = 40..150,
                        thumbLabel = { "$it" },
                        onSetValue = { onStyleChange(style.copy(gridCoverWidth = it)) },
                        labelWidth = 80.dp,
                    )
                    EInkSliderRow(
                        label = "书名行数",
                        value = style.titleMaxLines,
                        valueRange = 1..5,
                        thumbLabel = { "${it}行" },
                        onSetValue = { onStyleChange(style.copy(titleMaxLines = it)) },
                        labelWidth = 80.dp,
                    )
                } else {
                    // 列表专属：最新章节行
                    ChoiceRow("最新章节", style.showLatestChapter, onText = "显示", offText = "隐藏") { value ->
                        onStyleChange(style.copy(showLatestChapter = value))
                    }
                }
            }
        }
    }
}

/**
 * 二选一配置行（2026-10-17 统一）：标签 + [EInkSegmentedButton]
 * 多状态组合按钮（轨道相连两端圆角、选中圆角矩形实心高亮）；
 * 同值点击不提交。
 */
@Composable
private fun ChoiceRow(
    label: String,
    checked: Boolean,
    onText: String,
    offText: String,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        io.legado.app.eink.designsystem.control.EInkSegmentedButton(
            options = listOf(onText, offText),
            selectedIndex = if (checked) 0 else 1,
            onSelect = { index -> onSelect(index == 0) },
            modifier = Modifier.width(128.dp),
        )
    }
}
