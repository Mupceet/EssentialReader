package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 书架选择器收起态 chip：跟在顶栏「书架」标题后，显示当前分组名。
 *
 * 样式为描边 + 标题级字重（用户 2026-09-16 定案偏好反色实心之外
 * 的加重形态；2026-09-17 真机反馈 2dp 描边过重，回调常规 1dp，
 * 以标题字重与常规元素区分）：▾/▴ 指示展开态。
 * E-Ink 约束：静态绘制，零动画零阴影。
 */
@Composable
fun ShelfGroupChip(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(start = EInkSpacing.s)
            .border(width = 1.dp, color = colors.onSurface, shape = EInkShapes.medium)
            .einkClickable(
                role = Role.Button,
                onClickLabel = "选择分组",
                onClick = onClick,
            )
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = if (expanded) "$text ▴" else "$text ▾",
            style = EInkTheme.typography.titleSmall,
            // 顶栏高度固定 56dp：长组名单行截断省略，不换行撑高
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 面板每页分组 chip 数（整页兜底：分组按此值 chunked 分页，
 * 翻页 = 整页替换，零动画）。取值由 60% 高度上限反推（约 7 行 ×
 * 每行 3 chip；组名偏长每行 2 个时尾部可能触底裁切，同既有边缘）。
 */
private const val PAGE_SIZE = 24

/**
 * 书架选择器面板（顶栏下方锚定浮层）：分组流式 chip 平铺。
 *
 * - 每个分组一个 chip（文案 `组名·N本`，N=书数，含「全部」）：当前选中
 *   组反色实心（onSurface 底 + background 字），其余 1dp 描边；点选任意
 *   chip 即切组并收起；
 * - 整页兜底：分组按 [PAGE_SIZE] 每页 24 个 chip 平铺，翻页 = 整页替换
 *   （零动画，弹层禁自由滚动同书架铁律）；页脚（页码+箭头）仅多于一页
 *   时渲染，单页不渲染不占位；
 * - 浮层锚定内容区顶部（挂在 HomeScreen 内容 Box 内，位于顶栏之下、
 *   底部操作栏之上），面板外点击收起；书列表不重排、书架分页状态不动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfSelectorPanel(
    groups: List<BookshelfGroupUiModel>,
    selectedGroupId: Long,
    onSelectGroup: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val pages = groups.chunked(PAGE_SIZE)
    // 页索引随面板卸载即复位（弹层局部状态，无需 saveable）；
    // 空列表为不可达防御（引擎注册时「全部」恒在），页数兜底为 1
    val pageCount = pages.size.coerceAtLeast(1)
    var pageInput by remember { mutableIntStateOf(0) }
    // 分组数变化（完整模式并发删组）时夹回有效区间防越界
    val pageIndex = pageInput.coerceIn(0, pageCount - 1)
    val page = pages.getOrNull(pageIndex).orEmpty()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 不变量：content Box 内浮层的 zIndex 必须高于 HomePane 可见态
            // 的 1f，否则被书架 Pane 盖住绘制且点击穿透到书籍条目
            .zIndex(2f)
    ) {
        // 透明点击层：点面板外空白收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(role = Role.Button, onClickLabel = "收起面板", onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.6f)
                .background(EInkTheme.colorScheme.surface)
                // 消费面板内空白点击，避免透传到关闭层
                .einkClickable(onClick = {}),
        ) {
            // 当前页分组 chip 流式平铺（weight fill=false：内容不足一页时
            // 面板收缩包裹，超限时先压缩此区保页脚可见；clip 防越界绘制）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clipToBounds()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
            ) {
                page.forEach { group ->
                    GroupChip(
                        group = group,
                        selected = group.groupId == selectedGroupId,
                        onClick = {
                            onSelectGroup(group.groupId)
                            onDismiss()
                        },
                    )
                }
            }
            // 页脚：右下角页码小字 + 整页翻页箭头，仅一页时不渲染不占位
            if (pageCount > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EInkText(
                        text = "${pageIndex + 1}/$pageCount 页",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                    )
                    EInkPageArrows(
                        pageUpEnabled = pageIndex > 0,
                        pageDownEnabled = pageIndex < pageCount - 1,
                        onPageUp = { pageInput = pageIndex - 1 },
                        onPageDown = { pageInput = pageIndex + 1 },
                    )
                }
            }
            // 面板底部收边
            EInkHorizontalDivider()
        }
    }
}

/**
 * 分组 chip：文案 `组名 ·N`（N=书数）。当前选中组反色实心（onSurface 底 +
 * background 字，无描边），其余 1dp 描边常规字重；最小高 36dp 触控目标，
 * 长组名单行截断省略。E-Ink 约束：静态绘制，零动画零阴影。
 */
@Composable
private fun GroupChip(
    group: BookshelfGroupUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .then(
                if (selected) {
                    Modifier.background(colors.onSurface, EInkShapes.medium)
                } else {
                    Modifier.border(width = 1.dp, color = colors.outline, shape = EInkShapes.medium)
                }
            )
            .einkClickable(
                role = Role.Button,
                onClickLabel = group.name,
                onClick = onClick,
            )
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = "${group.name}·${group.bookCount}本",
            style = EInkTheme.typography.bodyMedium,
            color = if (selected) colors.background else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
