package io.legado.app.eink.feature.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.designsystem.content.EInkLoading
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkTopBar
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/** 当前阅读章节左侧实心标记尺寸（规范 §42 列表行持久选中，additive inking）。 */
private val CurrentMarkWidth = 4.dp
private val CurrentMarkHeight = 16.dp

/**
 * 无状态笔记 Screen。
 *
 * 结构：顶栏（书名 + 导出按钮）→ 内容区（划线/想法混合列表固定页分页）
 * → 底部操作栏（返回 / 回到当前 居左连续 + 翻页胶囊，统一
 * [EInkOperationBar]，无 Tab）。
 *
 * [pageArrows] 为翻页箭头槽：由承载层在其中读取分页状态并组合翻页
 * 箭头，使翻页可用状态的读取收敛到箭头叶作用域。
 *
 * 根为 Box：跳转确认弹层（[EInkDialog]）必须组合在全屏容器子级
 * （其组合契约），作为内容 Column 的兄弟覆盖整屏。
 */
@Composable
internal fun NoteScreen(
    state: NoteUiState,
    listState: LazyListState,
    pageArrows: @Composable () -> Unit,
    onBack: () -> Unit,
    onBackToCurrent: () -> Unit,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onMarkingClick: (String) -> Unit,
    onConfirmJump: () -> Unit,
    onDismissJump: () -> Unit,
    onExport: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：书名居左，导出按钮撑满顶栏高、贴右屏
            EInkTopBar(
                title = state.bookName.ifBlank { "笔记" },
                actionsFillMax = true,
                actions = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_note_export),
                        contentDescription = "导出",
                        // 空列表/导出中不动作（canExport=false）
                        onClick = { if (state.canExport) onExport() },
                    )
                },
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                    state.markings.isEmpty() -> CenterMessage("暂无划线或想法")
                    else -> MarkingList(
                        state = state,
                        listState = listState,
                        onPageUp = onPageUp,
                        onPageDown = onPageDown,
                        onMarkingClick = onMarkingClick,
                    )
                }
            }
            // 底部操作栏：返回 / 回到当前 居左连续 + 翻页胶囊（无 Tab）
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
                navigationIcon = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_arrow_back),
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                },
                actions = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_toc_locate),
                        contentDescription = "回到当前",
                        onClick = onBackToCurrent,
                    )
                },
                pageArrows = pageArrows,
            )
        }
        // 跳转目标存疑时的「仍跳转/取消」确认（覆盖整屏，见根 Box 说明）
        state.pendingJump?.let { pending ->
            EInkDialog(
                onDismiss = onDismissJump,
                title = "跳转确认",
                confirmText = "仍跳转",
                cancelText = "取消",
                onConfirm = onConfirmJump,
            ) {
                EInkText(text = pending.message, style = EInkTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 划线/想法混合列表：同款固定页分页（不支持自由滚动，上下滑动手势
 * 识别为整页翻页，与底部 ▲▼ 按钮同一动作）。
 */
@Composable
private fun MarkingList(
    state: NoteUiState,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onMarkingClick: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .EInkPageSwipe(onPageUp = onPageUp, onPageDown = onPageDown),
    ) {
        itemsIndexed(state.markings, key = { _, marking -> marking.id }) { _, marking ->
            MarkingItem(
                marking = marking,
                isCurrent = marking.chapterIndex == state.currentChapterIndex,
                onClick = { onMarkingClick(marking.id) },
            )
        }
    }
}

/**
 * 笔记条目：线型图例 + 章节名 → 划线原文（最多 3 行）→ 想法
 * （「想法：」前缀，最多 3 行）。按压瞬时反色（规范 §35）。
 *
 * 当前阅读章节为左侧实心标记 + 章名加粗（规范 §42 列表行持久选中，
 * additive inking，同目录页书签条目）。
 */
@Composable
private fun MarkingItem(
    marking: MarkingUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    // 图例/标记随按压反色；正文为墨水屏灰度（不做彩色）
    val markColor = if (press.isPressed) scheme.surface else scheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        ) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(width = CurrentMarkWidth, height = CurrentMarkHeight)
                        .background(markColor)
                )
            }
            MarkingLegend(thought = marking.thought, color = markColor)
            EInkText(
                text = marking.chapterName,
                style = EInkTheme.typography.labelMedium,
                color = if (press.isPressed) colors.contentColor else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
            )
        }
        EInkText(
            text = marking.selectedText,
            style = EInkTheme.typography.bodyLarge,
            color = if (press.isPressed) colors.contentColor else scheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (marking.note.isNotBlank()) {
            EInkText(
                text = "想法：${marking.note}",
                style = EInkTheme.typography.bodyMedium,
                color = if (press.isPressed) colors.contentColor else scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 线型图例（16dp 宽、2dp 高，墨水屏灰度）：想法 = 虚线段（3 段），
 * 纯划线 = 实线段；颜色随按压反色由调用方传入。
 */
@Composable
private fun MarkingLegend(thought: Boolean, color: Color) {
    if (thought) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.width(16.dp),
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(2.dp)
                        .background(color)
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(2.dp)
                .background(color)
        )
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EInkText(text = message, style = EInkTheme.typography.bodyLarge)
    }
}
