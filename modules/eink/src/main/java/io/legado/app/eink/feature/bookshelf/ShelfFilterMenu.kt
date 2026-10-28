package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.R
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.EInkPressBlock
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 标题旁筛选内容片（spec D4 2026-10-17 修订；2026-10-28 起「书架 +
 * 图标 + 筛选名」整块即筛选按钮）：本片仅展示（图标 + 激活筛选名），
 * 不自带点击/按压——随可点击标题（[EInkButton] trailing 槽）整块按压
 * 反色，点击行为在标题上、由宿主打开 [ShelfFilterMenu] 下拉。
 */
@Composable
fun FilterMenuTitleChip(
    activeFilterName: String?,
    contentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.eink_ic_filter),
            contentDescription = null,
            modifier = Modifier.width(22.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor),
        )
        if (activeFilterName != null) {
            EInkText(
                text = activeFilterName,
                style = EInkTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 筛选下拉菜单（spec D4 2026-10-17 修订）：锚定筛选入口下方（触发钮在
 * 书架标题旁，菜单自内容区左上 TopStart 展开）、透明点击层收起；顶部
 * 一行简略筛选描述，其后筛选选项（选中项 = 左竖线 + 加粗 + 末尾 ✓，
 * 静态单帧），最底「调整筛选器」项（上通栏分隔线）。
 * E-Ink：零动画零阴影，1dp 边框 + surface 底。
 */
@Composable
fun ShelfFilterMenu(
    filters: List<BookshelfGroupUiModel>,
    selectedFilterId: Long,
    onSelectFilter: (Long) -> Unit,
    onOpenManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    val selectedName = filters.firstOrNull { it.groupId == selectedFilterId }?.name

    // 返回键收起菜单（与点面板外同义）；不拦截会透传系统退出应用
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // 模态在场登记：菜单打开期间音量键翻页放行系统，不穿透翻动书架
    androidx.compose.runtime.DisposableEffect(Unit) {
        io.legado.app.eink.designsystem.control.EInkModalPresence.acquire()
        onDispose { io.legado.app.eink.designsystem.control.EInkModalPresence.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(2f)
            .einkClickable(role = Role.Button, onClickLabel = "收起菜单", onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                // 锚定筛选入口下方：触发钮在书架标题旁（左上），菜单从
                // 内容区左上展开，不再贴右缘
                .align(Alignment.TopStart)
                .padding(start = EInkSpacing.m)  // 不紧贴屏幕左缘（小留距）
                .width(240.dp)
                .background(colors.surface)
                .border(1.dp, colors.outline, EInkShapes.medium)
                .einkClickable(onClick = {}), // 消费面板内空白点击，避免透传关闭
        ) {
            filters.forEach { filter ->
                FilterMenuRow(
                    text = filter.name,
                    bookCount = filter.bookCount,
                    selected = filter.groupId == selectedFilterId,
                    onClick = {
                        onSelectFilter(filter.groupId)
                        onDismiss()
                    },
                )
            }
            EInkHorizontalDivider()
            FilterMenuRow(
                text = "调整筛选器",
                bookCount = null,  // 管理入口非筛选器，不显示数量
                selected = false,
                onClick = {
                    onOpenManage()
                    onDismiss()
                },
            )
        }
    }
}

/**
 * 菜单行（2026-10-17 定稿=目录页 ChapterItem 同款）：行恒有左右边距；
 * 选中时行首出现 4×16dp 实心标记**自然把文字向后推**（无占位，未选中
 * 文字位置正常）；文字带书本数量（名(N)）；选中 = 标记 + 加粗 + 末尾 ✓；
 * 按压瞬时反色。
 */
@Composable
private fun FilterMenuRow(
    text: String,
    bookCount: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    EInkPressBlock(
        onClick = onClick,
        onClickLabel = text,
        modifier = Modifier.fillMaxWidth(),
    ) { colors, pressed ->
        val markColor = if (pressed) scheme.surface else scheme.onSurface
        val textColor = when {
            pressed -> colors.contentColor
            selected -> scheme.onSurface
            else -> scheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        ) {
            if (selected) {
                // 选中实心标记（目录页同款 4×16dp）：出现即把文字自然后推
                Box(modifier = Modifier.size(width = 4.dp, height = 16.dp).background(markColor))
            }
            EInkText(
                text = if (bookCount != null) "$text($bookCount)" else text,
                style = EInkTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else null,
                color = textColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (selected) {
                EInkText(
                    text = "✓",
                    style = EInkTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = markColor,
                )
            }
        }
    }
}

/**
 * 调整筛选器面板（2026-10-17 更名自「管理筛选器」并改底部弹框）：
 * 共用 [BottomAdjustPanel] 骨架——全量系统筛选器（含隐藏），行 = 位次 +
 * 名称 + 显隐开关 + ▲▼，退出一次性整表提交 order+show；「无筛选」为
 * 固定默认态不参与管理；翻页由界面底部操作栏 ▲▼ 驱动。
 */
@Composable
fun FilterAdjustPanel(
    filters: List<BookshelfGroupUiModel>,
    pageSize: Int,
    pageIndex: Int,
    onPageSizeMeasured: (Int) -> Unit,
    onApplyAdjustments: (orderedFilterIds: List<Long>, shownFilterIds: Set<Long>) -> Unit,
    onHideBlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomAdjustPanel(
        title = "调整筛选器",
        items = filters,
        pageSize = pageSize,
        pageIndex = pageIndex,
        onPageSizeMeasured = onPageSizeMeasured,
        onApplyAdjustments = onApplyAdjustments,
        onHideBlocked = onHideBlocked,
        onDismiss = onDismiss,
    )
}
