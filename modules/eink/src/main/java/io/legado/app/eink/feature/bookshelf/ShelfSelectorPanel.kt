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
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 书架选择器收起态 chip：跟在顶栏「书架」标题后，显示当前分组名。
 *
 * 样式为描边加重（加粗边框 + 标题级字重，用户 2026-09-16 定案偏好，
 * 不用反色实心）：2dp 描边区别于常规 1dp 元素，▾/▴ 指示展开态。
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
            .border(width = 2.dp, color = colors.onSurface, shape = EInkShapes.medium)
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
 * 面板分组 chip 展示上限（分组按此值截断，超出部分不展示）。
 */
private const val PAGE_SIZE = 16

/**
 * 书架选择器面板（顶栏下方锚定浮层）：分组流式 chip 平铺。
 *
 * - 每个分组一个 chip（文案 `组名 ·N`，N=书数，含「全部」）：当前选中
 *   组反色实心（onSurface 底 + background 字），其余 1dp 描边；点选任意
 *   chip 即切组并收起；
 * - 分组数以 [PAGE_SIZE] 为展示上限，超出部分不展示（面板高度上限
 *   60%、超出裁切，弹层禁自由滚动同书架铁律）；
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
            // 分组 chip 流式平铺（weight fill=false：内容不足时面板收缩
            // 包裹，超限裁切；clip 防越界绘制）
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
