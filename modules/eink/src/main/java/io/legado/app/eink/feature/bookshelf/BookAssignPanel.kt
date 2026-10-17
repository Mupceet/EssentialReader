package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 长按书籍的上下文菜单（spec D1）：查看详情 / 加入分组。
 * 详情动作由调用方注入（保持既有 BookDetail 导航）。
 */
@Composable
fun BookContextMenu(
    book: BookshelfItemUiModel?,
    onOpenDetail: (BookshelfItemUiModel) -> Unit,
    onOpenAssign: (BookshelfItemUiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    if (book == null) return
    EInkDialog(onDismiss = onDismiss, title = book.name, showActions = false) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuActionRow("查看详情") { onOpenDetail(book) }
            MenuActionRow("加入分组") { onOpenAssign(book) }
        }
    }
}

/**
 * 归组勾选面板（spec D2/4.3）：首项「＋ 创建分组」入口（点击弹与重命名
 * 同款的输入弹框），其后全部用户组勾选行。勾/取消即多组并存写库（乐观
 * 覆盖经 VM）；「移动」语义 = 手动两步。组多时整页翻页（页脚仅多页渲染）。
 */
private const val ASSIGN_PAGE_SIZE = 8

@Composable
fun BookAssignPanel(
    book: BookshelfItemUiModel?,
    groups: List<BookshelfGroupUiModel>,
    membership: Set<Long>,
    onToggle: (groupId: Long, member: Boolean) -> Unit,
    onCreateGroupRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (book == null) return
    var pageInput by remember(book) { mutableIntStateOf(0) }
    val pages = groups.chunked(ASSIGN_PAGE_SIZE)
    val pageCount = pages.size.coerceAtLeast(1)
    val pageIndex = pageInput.coerceIn(0, pageCount - 1)
    val page = pages.getOrNull(pageIndex).orEmpty()

    EInkDialog(
        onDismiss = onDismiss,
        title = "加入分组",
        showActions = false,
        onClose = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 首项：创建分组入口（加号 + 提示）；点击弹与重命名同款的输入弹框
            MenuActionRow("+ 创建分组") { onCreateGroupRequested() }
            EInkHorizontalDivider()
            // 勾选行（当前页）
            page.forEach { group ->
                val checked = membership.contains(group.groupId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .einkClickable(
                            role = Role.Checkbox,
                            onClickLabel = group.name,
                        ) {
                            onToggle(group.groupId, !checked)
                        }
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBoxSquare(checked = checked)
                    EInkText(
                        text = group.name,
                        style = EInkTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = EInkSpacing.s),
                    )
                    EInkText(
                        text = "${group.bookCount} 本",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                    )
                }
            }
            // 页脚：多页才渲染（同筛选面板脚手架）
            if (pageCount > 1) {
                EInkHorizontalDivider()
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
        }
    }
}

/** 勾选方框：选中 = onSurface 实心 + 中心留白方点；未选 = 1dp outline 描边。零动画。 */
@Composable
private fun CheckBoxSquare(checked: Boolean) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (checked) {
                    Modifier.background(colors.onSurface, EInkShapes.small)
                } else {
                    Modifier.border(1.dp, colors.outline, EInkShapes.small)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.background, EInkShapes.small)
            )
        }
    }
}

/** 菜单/动作文本行（长按菜单、归组面板创建入口共用）。 */
@Composable
internal fun MenuActionRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    EInkText(
        text = text,
        style = EInkTheme.typography.bodyLarge,
        color = if (enabled) EInkTheme.colorScheme.onSurface else EInkTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .einkClickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
    )
}
